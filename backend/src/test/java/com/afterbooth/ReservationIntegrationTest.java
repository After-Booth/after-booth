package com.afterbooth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.afterbooth.allocation.dto.HoldItemRequest;
import com.afterbooth.allocation.dto.ReservationResponse;
import com.afterbooth.allocation.service.ReservationScheduler;
import com.afterbooth.allocation.service.ReservationService;
import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.idempotency.IdempotencyService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    properties = {
      "spring.docker.compose.enabled=false",
      "afterbooth.reservation-sweep-ms=3600000",
      "afterbooth.vehicle-cargo.sedan.width-cm=100",
      "afterbooth.vehicle-cargo.sedan.depth-cm=100",
      "afterbooth.vehicle-cargo.sedan.height-cm=100"
    })
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc(
    print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Import({
  com.afterbooth.AuthenticatedMockMvcConfiguration.class,
  TestcontainersConfiguration.class,
  ReservationIntegrationTest.TimeConfiguration.class
})
class ReservationIntegrationTest {
  static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @Autowired ReservationService reservations;
  @Autowired ReservationScheduler scheduler;
  @Autowired com.afterbooth.handover.service.HandoverCompletionService handovers;
  @Autowired IdempotencyService idempotency;
  @Autowired JdbcTemplate jdbc;
  @Autowired MutableClock clock;
  @Autowired com.afterbooth.global.security.DomainAccess access;
  private com.afterbooth.global.security.AuthenticatedMember giver;
  private com.afterbooth.global.security.AuthenticatedMember receiver;

  @BeforeEach
  void reset() {
    clock.set(NOW);
    giver = DomainAuthTestSupport.seedOwner(jdbc, clock, 1);
    receiver = DomainAuthTestSupport.seedOwner(jdbc, clock, 2);
    jdbc.update("delete from idempotency_record");
    jdbc.update("delete from reservation_transition");
    jdbc.update("delete from handover_log");
    jdbc.update("delete from reservation");
  }

  Long item(int quantity) {
    return jdbc.queryForObject(
        "insert into item(event_id,organization_id,category,label,width_cm,depth_cm,height_cm,condition,total_qty,available_qty,pickup_deadline) values(1,1,'STORAGE_BIN','수납함',20,20,20,'GOOD',?,?,?) returning id",
        Long.class,
        quantity,
        quantity,
        java.sql.Timestamp.from(NOW.plusSeconds(3600)));
  }

  Long request(int quantity) {
    return jdbc.queryForObject(
        "insert into pickup_request(organization_id,category,quantity,vehicle_type,created_at) values(2,'STORAGE_BIN',?,'SEDAN',?) returning id",
        Long.class,
        quantity,
        java.sql.Timestamp.from(NOW));
  }

  ReservationResponse hold(Long request, Long item) {
    return reservations.hold(new HoldItemRequest(request, item, 1));
  }

  int stock(Long item) {
    return jdbc.queryForObject("select available_qty from item where id=?", Integer.class, item);
  }

  List<ErrorCode> concurrent(int count, IntFunction<ErrorCode> action) throws Exception {
    CountDownLatch ready = new CountDownLatch(count);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<ErrorCode>> futures = new ArrayList<>();
    try (ExecutorService executor =
        com.afterbooth.DomainAuthTestSupport.executor(Executors.newFixedThreadPool(count))) {
      for (int i = 0; i < count; i++) {
        int index = i;
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(30, TimeUnit.SECONDS))
                    throw new AssertionError("Start timed out");
                  return action.apply(index);
                }));
      }
      assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<ErrorCode> result = new ArrayList<>();
      for (var future : futures) result.add(future.get(60, TimeUnit.SECONDS));
      return result;
    }
  }

  ErrorCode attempt(Runnable action) {
    try {
      action.run();
      return null;
    } catch (BusinessException failure) {
      return failure.errorCode();
    }
  }

  @Test
  void exactlyTwoOfOneHundredThreadsCanHoldLastTwoItems() throws Exception {
    Long item = item(2);
    List<Long> requests = new ArrayList<>();
    for (int i = 0; i < 100; i++) requests.add(request(1));
    List<ErrorCode> result =
        concurrent(100, index -> attempt(() -> hold(requests.get(index), item)));
    assertThat(result).filteredOn(Objects::isNull).hasSize(2);
    assertThat(result).filteredOn(code -> code == ErrorCode.QTY_UNAVAILABLE).hasSize(98);
    assertThat(stock(item)).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from reservation where item_id=? and status='HELD'",
                Integer.class,
                item))
        .isEqualTo(2);
  }

  @Test
  void cancelRestoresExactlyOnceAcrossConcurrentCalls() throws Exception {
    Long item = item(2);
    Long id = hold(request(1), item).id();
    List<ErrorCode> results = concurrent(12, index -> attempt(() -> reservations.cancel(id)));
    assertThat(results).filteredOn(Objects::isNull).hasSize(1);
    assertThat(stock(item)).isEqualTo(2);
    assertThat(reservations.get(id).transitions()).hasSize(2);
  }

  @Test
  void expiryRestoresExactlyOnceAndSchedulerReleasesMissedPickups() throws Exception {
    Long item = item(3);
    Long expired = hold(request(1), item).id();
    Long confirmed = hold(request(1), item).id();
    assertThat(reservations.confirm(confirmed).error()).isNull();
    clock.set(NOW.plusSeconds(900));
    concurrent(
        12,
        index -> {
          reservations.expire(expired);
          return null;
        });
    assertThat(stock(item)).isEqualTo(2);
    clock.set(NOW.plusSeconds(3600));
    scheduler.sweep();
    scheduler.sweep();
    assertThat(stock(item)).isEqualTo(3);
    assertThat(reservations.get(expired).status().name()).isEqualTo("EXPIRED");
    assertThat(reservations.get(confirmed).status().name()).isEqualTo("RELEASED");
  }

  @Test
  void orphanHoldRecoversWithoutDoubleDecrement() {
    Long item = item(2);
    Long request = request(2);
    UUID key = UUID.randomUUID();
    byte[] body = holdBody(request, item, 1);
    jdbc.update(
        "insert into idempotency_record(idempotency_key,fingerprint,state,created_at,actor_member_id,related_organization_ids) values (?,?,'IN_PROGRESS',current_timestamp,?,array[1,2]::bigint[])",
        key,
        IdempotencyService.fingerprint("POST", "/api/reservations", body),
        receiver.memberId());
    var recovered =
        idempotency.execute(key, "POST", "/api/reservations", body, 201, () -> hold(request, item));
    var replay =
        idempotency.execute(
            key,
            "POST",
            "/api/reservations",
            body,
            201,
            () -> {
              throw new AssertionError("Replay executed");
            });
    assertThatJson(replay.getBody(), recovered.getBody());
    assertThat(stock(item)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from reservation where request_id=?", Integer.class, request))
        .isEqualTo(1);
  }

  @Test
  void repeatedKeyReplaysStoredResponseWithoutDecrementAndRejectsDifferentBody() {
    Long item = item(2);
    Long request = request(2);
    UUID key = UUID.randomUUID();
    byte[] body = holdBody(request, item, 1);
    var first =
        idempotency.execute(key, "POST", "/api/reservations", body, 201, () -> hold(request, item));
    var second =
        idempotency.execute(
            key,
            "POST",
            "/api/reservations",
            body,
            201,
            () -> {
              throw new AssertionError("Retry executed");
            });
    assertThat(second.getStatusCode()).isEqualTo(first.getStatusCode());
    assertThatJson(second.getBody(), first.getBody());
    assertThat(stock(item)).isEqualTo(1);
    assertThat(
            attempt(
                () ->
                    idempotency.execute(
                        key,
                        "POST",
                        "/api/reservations",
                        holdBody(request, item, 2),
                        201,
                        () -> hold(request, item))))
        .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
  }

  byte[] holdBody(Long request, Long item, int quantity) {
    return ("{\"requestId\":" + request + ",\"itemId\":" + item + ",\"quantity\":" + quantity + "}")
        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  void assertThatJson(String actual, String expected) {
    assertThat(tools.jackson.databind.json.JsonMapper.builder().build().readTree(actual))
        .isEqualTo(tools.jackson.databind.json.JsonMapper.builder().build().readTree(expected));
  }

  @Test
  void inFlightRetryReturnsConflictImmediately() throws Exception {
    UUID key = UUID.randomUUID();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch finish = new CountDownLatch(1);
    try (ExecutorService pool =
        com.afterbooth.DomainAuthTestSupport.executor(Executors.newSingleThreadExecutor())) {
      Future<?> first =
          pool.submit(
              () ->
                  idempotency.execute(
                      key,
                      "POST",
                      "/api/events",
                      new byte[0],
                      200,
                      () -> {
                        entered.countDown();
                        try {
                          if (!finish.await(20, TimeUnit.SECONDS))
                            throw new AssertionError("Release timed out");
                        } catch (InterruptedException error) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(error);
                        }
                        return List.of("saved");
                      }));
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      try {
        assertThat(
                attempt(
                    () ->
                        idempotency.execute(
                            key, "POST", "/api/events", new byte[0], 200, () -> "duplicate")))
            .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
      } finally {
        finish.countDown();
      }
      first.get(30, TimeUnit.SECONDS);
    }
  }

  @Test
  void forbiddenTransitionsAreRejected() {
    Long item = item(2);
    Long id = hold(request(1), item).id();
    assertThat(reservations.confirm(id).error()).isNull();
    assertThat(attempt(() -> reservations.confirm(id))).isEqualTo(ErrorCode.INVALID_TRANSITION);
    reservations.cancel(id);
    assertThat(attempt(() -> reservations.cancel(id))).isEqualTo(ErrorCode.INVALID_TRANSITION);
    assertThat(attempt(() -> reservations.confirm(id))).isEqualTo(ErrorCode.INVALID_TRANSITION);
    assertThat(stock(item)).isEqualTo(2);
  }

  @Test
  void differentItemsForSameRequestCannotExceedRequestQuantity() throws Exception {
    Long first = item(1);
    Long second = item(1);
    Long request = request(1);
    List<ErrorCode> result =
        concurrent(2, index -> attempt(() -> hold(request, index == 0 ? first : second)));
    assertThat(result).filteredOn(Objects::isNull).hasSize(1);
    assertThat(result).contains(ErrorCode.QTY_EXCEEDS_REQUEST);
    assertThat(stock(first) + stock(second)).isEqualTo(1);
  }

  @Test
  void lazyConfirmAndSchedulerExpiryRestoreQuantityExactlyOnceAtDeadline() throws Exception {
    for (int iteration = 0; iteration < 10; iteration++) {
      clock.set(NOW);
      Long item = item(1);
      Long id = hold(request(1), item).id();
      clock.set(NOW.plusSeconds(900));
      concurrent(
          2,
          index -> {
            if (index == 0)
              assertThat(reservations.confirm(id).error()).isEqualTo(ErrorCode.HOLD_EXPIRED);
            else reservations.expire(id);
            return null;
          });
      assertThat(reservations.get(id).status().name()).isEqualTo("EXPIRED");
      assertThat(reservations.get(id).transitions()).hasSize(2);
      assertThat(stock(item)).isEqualTo(1);
    }
  }

  @Test
  void confirmingBeforeExpiryKeepsStockReserved() {
    Long item = item(1);
    Long id = hold(request(1), item).id();
    clock.set(NOW.plusSeconds(899));
    assertThat(reservations.confirm(id).error()).isNull();
    clock.set(NOW.plusSeconds(900));
    assertThat(reservations.expire(id)).isFalse();
    assertThat(stock(item)).isZero();
    assertThat(reservations.get(id).transitions()).hasSize(2);
  }

  @Test
  void holdRulesRejectLateOversizedAndExcessQuantityForUserAndAgent() {
    Long item = item(2);
    Long request = request(1);
    assertThat(attempt(() -> reservations.holdForAgent(new HoldItemRequest(request, item, 2))))
        .isEqualTo(ErrorCode.QTY_EXCEEDS_REQUEST);
    jdbc.update("update item set width_cm=101 where id=?", item);
    assertThat(attempt(() -> hold(request, item))).isEqualTo(ErrorCode.VEHICLE_TOO_SMALL);
    assertThat(attempt(() -> reservations.holdForAgent(new HoldItemRequest(request, item, 1))))
        .isEqualTo(ErrorCode.VEHICLE_TOO_SMALL);
    jdbc.update("update item set width_cm=20 where id=?", item);
    clock.set(NOW.plusSeconds(3600));
    assertThat(attempt(() -> hold(request, item))).isEqualTo(ErrorCode.PICKUP_CLOSED);
    assertThat(stock(item)).isEqualTo(2);
  }

  @Test
  void failedBusinessResponseIsReplayed() {
    UUID key = UUID.randomUUID();
    var first =
        idempotency.execute(
            key,
            "POST",
            "/api/events",
            new byte[0],
            201,
            () -> {
              throw new BusinessException(ErrorCode.QTY_UNAVAILABLE);
            });
    var retry =
        idempotency.execute(
            key,
            "POST",
            "/api/events",
            new byte[0],
            201,
            () -> {
              throw new AssertionError("Retry executed");
            });
    assertThat(retry.getStatusCode().value()).isEqualTo(409);
    assertThatJson(retry.getBody(), first.getBody());
  }

  @Test
  void httpContractReplaysHoldAndIncludesHistoryOnlyInSingleGet() throws Exception {
    Long item = item(2);
    Long request = request(2);
    String body = "{\"requestId\":" + request + ",\"itemId\":" + item + ",\"quantity\":1}";
    String key = UUID.randomUUID().toString();
    var result =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/reservations")
                    .contentType("application/json")
                    .header("Idempotency-Key", key)
                    .content(body))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                    .isCreated())
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.status")
                    .value("HELD"))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.transitions")
                    .doesNotExist())
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.handedOverQty")
                    .doesNotExist())
            .andReturn();
    String first = result.getResponse().getContentAsString();
    Long id =
        tools.jackson.databind.json.JsonMapper.builder().build().readTree(first).get("id").asLong();
    assertThat(first).contains("+09:00");
    var retry =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/reservations")
                    .contentType("application/json")
                    .header("Idempotency-Key", key)
                    .content(body))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                    .isCreated())
            .andReturn();
    assertThatJson(retry.getResponse().getContentAsString(), first);
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/reservations/" + id))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.transitions.length()")
                .value(1));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/reservations")
                .param("requestId", request.toString())
                .param("eventId", "1"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()")
                .value(1))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$[0].transitions")
                .doesNotExist());
    assertThat(stock(item)).isEqualTo(1);
  }

  @Test
  void httpValidationRejectsMissingKeyMalformedBodyAndNullBody() throws Exception {
    for (String body : List.of("null", "{broken", "{\"requestId\":1,\"itemId\":1,\"quantity\":0}"))
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      "/api/reservations")
                  .contentType("application/json")
                  .header("Idempotency-Key", UUID.randomUUID())
                  .content(body))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isBadRequest())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                  .value("INVALID_INPUT"));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/reservations")
                .contentType("application/json")
                .content("{}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isBadRequest())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value("INVALID_INPUT"));
  }

  @Test
  void httpLateConfirmCommitsExpiryAndReplaysError() throws Exception {
    Long item = item(1);
    Long id = hold(request(1), item).id();
    clock.set(NOW.plusSeconds(900));
    String key = UUID.randomUUID().toString();
    for (int i = 0; i < 2; i++)
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      "/api/reservations/" + id + "/confirm")
                  .header("Idempotency-Key", key))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isConflict())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                  .value("HOLD_EXPIRED"));
    assertThat(stock(item)).isEqualTo(1);
    assertThat(reservations.get(id).transitions()).hasSize(2);
  }

  @Test
  void agentAndUserHoldsShareTheSameRequestLock() throws Exception {
    Long first = item(1);
    Long second = item(1);
    Long request = request(1);
    var result =
        concurrent(
            2,
            index ->
                attempt(
                    () -> {
                      if (index == 0) hold(request, first);
                      else reservations.holdForAgent(new HoldItemRequest(request, second, 1));
                    }));
    assertThat(result).filteredOn(Objects::isNull).hasSize(1);
    assertThat(result).contains(ErrorCode.QTY_EXCEEDS_REQUEST);
    assertThat(
            jdbc.queryForObject(
                "select coalesce(sum(quantity),0) from reservation where request_id=? and status in ('HELD','CONFIRMED')",
                Integer.class,
                request))
        .isEqualTo(1);
  }

  @Test
  void agentRejectsDifferentCategoryWhileUserMayHoldAnAlternative() {
    Long item = item(2);
    Long request = request(2);
    jdbc.update("update item set category='FOLDING_TABLE' where id=?", item);
    assertThatThrownBy(() -> reservations.holdForAgent(new HoldItemRequest(request, item, 1)))
        .isInstanceOfSatisfying(
            com.afterbooth.allocation.domain.AgentHoldRejectedException.class,
            rejection ->
                assertThat(rejection.rejection())
                    .isEqualTo(
                        com.afterbooth.allocation.domain.AgentHoldRejection.CATEGORY_MISMATCH));
    assertThat(hold(request, item).status().name()).isEqualTo("HELD");
    assertThat(stock(item)).isEqualTo(1);
  }

  @Test
  void holdExpiryIsCappedAtPickupDeadline() {
    Long item = item(1);
    Instant deadline = NOW.plusSeconds(60);
    jdbc.update(
        "update item set pickup_deadline=? where id=?", java.sql.Timestamp.from(deadline), item);
    var reservation = hold(request(1), item);
    assertThat(reservation.holdExpiresAt().toInstant()).isEqualTo(deadline);
    clock.set(deadline);
    assertThat(reservations.confirm(reservation.id()).error()).isEqualTo(ErrorCode.HOLD_EXPIRED);
    assertThat(stock(item)).isEqualTo(1);
  }

  @Test
  void handedOverStateCannotBeCancelledConfirmedOrExpired() {
    Long item = item(1);
    Long id = hold(request(1), item).id();
    jdbc.update("update reservation set status='HANDED_OVER',handed_over_qty=1 where id=?", id);
    assertThat(attempt(() -> reservations.cancel(id))).isEqualTo(ErrorCode.INVALID_TRANSITION);
    assertThat(attempt(() -> reservations.confirm(id))).isEqualTo(ErrorCode.INVALID_TRANSITION);
    clock.set(NOW.plusSeconds(7200));
    assertThat(reservations.expire(id)).isFalse();
    assertThat(stock(item)).isZero();
  }

  @Test
  void httpRejectsUnsupportedMediaMalformedUuidAndReusesActualPathFingerprint() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/reservations")
                .contentType("text/plain")
                .header("Idempotency-Key", UUID.randomUUID())
                .content("{}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isUnsupportedMediaType());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/reservations")
                .contentType("application/json")
                .header("Idempotency-Key", "invalid")
                .content("{}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isBadRequest())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value("INVALID_INPUT"));
    Long id = hold(request(1), item(1)).id();
    String key = UUID.randomUUID().toString();
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/reservations/0" + id + "/confirm")
                .header("Idempotency-Key", key))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/reservations/" + id + "/confirm")
                .header("Idempotency-Key", key))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value("IDEMPOTENCY_CONFLICT"));
  }

  @Test
  void unexpectedFailureRollsBackHoldHistoryStockAndClaimSoRetryCanSucceed() {
    Long item = item(1);
    Long request = request(1);
    UUID key = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                idempotency.execute(
                    key,
                    "POST",
                    "/api/events",
                    new byte[0],
                    201,
                    () -> {
                      hold(request, item);
                      throw new IllegalStateException("Injected failure");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(stock(item)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from reservation where request_id=?", Integer.class, request))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from idempotency_record where idempotency_key=?",
                Integer.class,
                key))
        .isZero();
    assertThat(jdbc.queryForObject("select count(*) from reservation_transition", Integer.class))
        .isZero();
    assertThat(
            idempotency
                .execute(key, "POST", "/api/events", new byte[0], 201, () -> hold(request, item))
                .getStatusCode()
                .value())
        .isEqualTo(201);
    assertThat(stock(item)).isZero();
  }

  @Test
  void businessFailureAfterHoldRollsBackItsEffectsAndStoresError() {
    Long item = item(1);
    Long request = request(1);
    UUID key = UUID.randomUUID();
    var failure =
        idempotency.execute(
            key,
            "POST",
            "/api/events",
            new byte[0],
            201,
            () -> {
              hold(request, item);
              throw new BusinessException(ErrorCode.INVALID_TRANSITION);
            });
    assertThat(failure.getStatusCode().value()).isEqualTo(409);
    assertThat(stock(item)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from reservation where request_id=?", Integer.class, request))
        .isZero();
    assertThat(jdbc.queryForObject("select count(*) from reservation_transition", Integer.class))
        .isZero();
    var retry =
        idempotency.execute(
            key,
            "POST",
            "/api/events",
            new byte[0],
            201,
            () -> {
              throw new AssertionError("Retry executed");
            });
    assertThatJson(retry.getBody(), failure.getBody());
  }

  @Test
  void lateConfirmThatLosesToEarlierConfirmReportsCurrentConfirmedState() throws Exception {
    Long item = item(1);
    Long id = hold(request(1), item).id();
    clock.set(NOW.plusSeconds(899));
    CountDownLatch sampled = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    try (ExecutorService executor =
        com.afterbooth.DomainAuthTestSupport.executor(Executors.newSingleThreadExecutor())) {
      Future<ErrorCode> late =
          executor.submit(
              () -> {
                clock.override(
                    () -> {
                      sampled.countDown();
                      await(proceed);
                      return NOW.plusSeconds(900);
                    });
                try {
                  return reservations.confirm(id).error();
                } catch (BusinessException error) {
                  return error.errorCode();
                }
              });
      assertThat(sampled.await(10, TimeUnit.SECONDS)).isTrue();
      try {
        assertThat(reservations.confirm(id).error()).isNull();
      } finally {
        proceed.countDown();
      }
      assertThat(late.get(30, TimeUnit.SECONDS)).isEqualTo(ErrorCode.INVALID_TRANSITION);
    }
    assertThat(reservations.get(id).status().name()).isEqualTo("CONFIRMED");
    assertThat(stock(item)).isZero();
    assertThat(reservations.get(id).transitions()).hasSize(2);
  }

  @Test
  void confirmSampledBeforeExpiryLosesToSchedulerWithoutDoubleRestoration() throws Exception {
    Long item = item(1);
    Long id = hold(request(1), item).id();
    clock.set(NOW.plusSeconds(900));
    CountDownLatch sampled = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    try (ExecutorService executor =
        com.afterbooth.DomainAuthTestSupport.executor(Executors.newSingleThreadExecutor())) {
      Future<ReservationService.Confirmation> early =
          executor.submit(
              () -> {
                clock.override(
                    () -> {
                      sampled.countDown();
                      await(proceed);
                      return NOW.plusSeconds(899);
                    });
                return reservations.confirm(id);
              });
      assertThat(sampled.await(10, TimeUnit.SECONDS)).isTrue();
      try {
        assertThat(reservations.expire(id)).isTrue();
      } finally {
        proceed.countDown();
      }
      assertThat(early.get(30, TimeUnit.SECONDS).error()).isEqualTo(ErrorCode.HOLD_EXPIRED);
    }
    assertThat(stock(item)).isEqualTo(1);
    assertThat(reservations.get(id).status().name()).isEqualTo("EXPIRED");
    assertThat(reservations.get(id).transitions()).hasSize(2);
  }

  @Test
  void schedulerQueuedBehindMemberLockedConfirmDoesNotRestoreStock() throws Exception {
    Long item = item(1);
    Long id = hold(request(1), item).id();
    clock.set(NOW.plusSeconds(899));
    var transaction =
        new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    try (ExecutorService executor =
        com.afterbooth.DomainAuthTestSupport.executor(Executors.newSingleThreadExecutor())) {
      AtomicReference<Future<Boolean>> expired = new AtomicReference<>();
      transaction.executeWithoutResult(
          status -> {
            access.lockOrganizations(List.of(1L, 2L));
            expired.set(executor.submit(() -> reservations.expire(id)));
            assertThat(reservations.confirm(id).error()).isNull();
            clock.set(NOW.plusSeconds(900));
          });
      assertThat(expired.get().get(30, TimeUnit.SECONDS)).isFalse();
    }
    assertThat(stock(item)).isZero();
    assertThat(reservations.get(id).status().name()).isEqualTo("CONFIRMED");
    assertThat(reservations.get(id).transitions()).hasSize(2);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(20, TimeUnit.SECONDS))
        throw new AssertionError("Clock coordination timed out");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
  }

  private Long confirmed(Long item, int quantity) {
    DomainAuthTestSupport.use(receiver);
    Long request = request(quantity);
    Long id = reservations.hold(new HoldItemRequest(request, item, quantity)).id();
    assertThat(reservations.confirm(id).error()).isNull();
    DomainAuthTestSupport.use(giver);
    return id;
  }

  private void recount(Long id, int expected, boolean matched) {
    jdbc.update(
        "insert into handover_log(reservation_id,expected_qty,counted_qty,accepted_qty,status,boxes,created_at) values(?,?,?,?,?,cast(? as jsonb),?)",
        id,
        expected,
        matched ? expected : 0,
        matched ? expected : null,
        matched ? "COMPLETED" : "MISMATCH",
        matched
            ? "["
                + String.join(
                    ",",
                    java.util.Collections.nCopies(
                        expected, "{\"x\":0,\"y\":0,\"width\":1,\"height\":1}"))
                + "]"
            : "[]",
        java.sql.Timestamp.from(NOW));
  }

  @Test
  void matchedRecountCompletesHandoverWithoutRestoringReceivedItems() {
    Long item = item(3);
    Long id = confirmed(item, 2);
    recount(id, 2, true);
    var result = handovers.completeMatched(id);
    assertThat(result.status().name()).isEqualTo("HANDED_OVER");
    assertThat(result.handedOverQty()).isEqualTo(2);
    assertThat(stock(item)).isEqualTo(1);
    assertThat(reservations.get(id).transitions()).hasSize(3);
    assertThat(attempt(() -> handovers.completeMatched(id)))
        .isEqualTo(ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void partialResolutionRestoresRemainderExactlyOnceAcrossConcurrentCalls() throws Exception {
    Long item = item(3);
    Long id = confirmed(item, 3);
    recount(id, 3, false);
    var outcomes = concurrent(10, index -> attempt(() -> handovers.resolve(id, 1)));
    assertThat(outcomes).filteredOn(Objects::isNull).hasSize(1);
    assertThat(stock(item)).isEqualTo(2);
    assertThat(reservations.get(id).handedOverQty()).isEqualTo(1);
    assertThat(reservations.get(id).transitions()).hasSize(3);
    assertThat(
            jdbc.queryForObject(
                "select accepted_qty from handover_log where reservation_id=?", Integer.class, id))
        .isEqualTo(1);
  }

  @Test
  void handoverRequiresNewestRecountWithCorrectExpectedQuantity() {
    Long item = item(3);
    Long id = confirmed(item, 2);
    assertThat(attempt(() -> handovers.resolve(id, 1))).isEqualTo(ErrorCode.INVALID_TRANSITION);
    assertThat(attempt(() -> handovers.completeMatched(id)))
        .isEqualTo(ErrorCode.INVALID_TRANSITION);
    recount(id, 1, true);
    assertThat(attempt(() -> handovers.completeMatched(id)))
        .isEqualTo(ErrorCode.INVALID_TRANSITION);
    recount(id, 2, true);
    recount(id, 2, false);
    assertThat(attempt(() -> handovers.completeMatched(id)))
        .isEqualTo(ErrorCode.INVALID_TRANSITION);
    handovers.resolve(id, 1);
    assertThat(stock(item)).isEqualTo(2);
  }

  @Test
  void resolutionRejectsInvalidQuantityAndRollsBackEvidence() {
    Long item = item(3);
    Long id = confirmed(item, 2);
    recount(id, 2, false);
    for (int accepted : new int[] {0, -1, 3})
      assertThat(attempt(() -> handovers.resolve(id, accepted))).isEqualTo(ErrorCode.INVALID_INPUT);
    assertThat(
            jdbc.queryForObject(
                "select accepted_qty from handover_log where reservation_id=?", Integer.class, id))
        .isNull();
    assertThat(stock(item)).isEqualTo(1);
    assertThat(reservations.get(id).status().name()).isEqualTo("CONFIRMED");
  }

  @Test
  void matchedHandoverRejectsPickupDeadlineAndResolutionRequiresMismatch() {
    Long item = item(2);
    Long id = confirmed(item, 2);
    recount(id, 2, true);
    assertThat(attempt(() -> handovers.resolve(id, 1))).isEqualTo(ErrorCode.INVALID_TRANSITION);
    clock.set(NOW.plusSeconds(3600));
    assertThat(attempt(() -> handovers.completeMatched(id))).isEqualTo(ErrorCode.PICKUP_CLOSED);
    assertThat(stock(item)).isZero();
  }

  @Test
  void resolutionRacingCancelPreservesQuantityAndEvidence() throws Exception {
    Long item = item(3);
    Long id = confirmed(item, 3);
    recount(id, 3, false);
    var result =
        concurrent(
            2,
            index ->
                attempt(
                    () -> {
                      if (index == 0) {
                        DomainAuthTestSupport.use(giver);
                        handovers.resolve(id, 1);
                      } else {
                        DomainAuthTestSupport.use(receiver);
                        reservations.cancel(id);
                      }
                    }));
    assertThat(result).filteredOn(Objects::isNull).hasSize(1);
    var finalReservation = reservations.get(id);
    assertThat(finalReservation.transitions()).hasSize(3);
    if (finalReservation.status().name().equals("HANDED_OVER")) {
      assertThat(stock(item)).isEqualTo(2);
      assertThat(
              jdbc.queryForObject(
                  "select accepted_qty from handover_log where reservation_id=?",
                  Integer.class,
                  id))
          .isEqualTo(1);
    } else {
      assertThat(stock(item)).isEqualTo(3);
      assertThat(
              jdbc.queryForObject(
                  "select accepted_qty from handover_log where reservation_id=?",
                  Integer.class,
                  id))
          .isNull();
    }
  }

  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
  @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;

  @Test
  void cancelWaitingBehindMemberLockedConfirmStillReleasesConfirmedReservation() throws Exception {
    Long item = item(1);
    Long id = hold(request(1), item).id();
    var transaction =
        new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    try (ExecutorService executor =
        com.afterbooth.DomainAuthTestSupport.executor(Executors.newSingleThreadExecutor())) {
      AtomicReference<Future<ReservationResponse>> cancelled = new AtomicReference<>();
      transaction.executeWithoutResult(
          status -> {
            access.lockOrganizations(List.of(1L, 2L));
            jdbc.queryForObject("select id from reservation where id=? for update", Long.class, id);
            cancelled.set(executor.submit(() -> reservations.cancel(id)));
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (jdbc.queryForObject(
                    "select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%member%' and pid<>pg_backend_pid()",
                    Integer.class)
                == 0) {
              if (System.nanoTime() > limit)
                throw new AssertionError("Cancellation did not wait for the member row lock");
              jdbc.execute("select pg_stat_clear_snapshot()");
              Thread.yield();
            }
            assertThat(reservations.confirm(id).error()).isNull();
          });
      assertThat(cancelled.get().get(30, TimeUnit.SECONDS).status().name()).isEqualTo("RELEASED");
    }
    assertThat(stock(item)).isEqualTo(1);
    assertThat(reservations.get(id).transitions()).hasSize(3);
  }

  @Test
  void listFiltersRequestAndEventInOneDatabaseQueryWithoutHistory() {
    Long firstItem = item(1);
    Long firstRequest = request(1);
    hold(firstRequest, firstItem);
    Long event =
        jdbc.queryForObject(
            "insert into event(organization_id,name,venue,teardown_deadline) values(1,'Other event','Seoul',?) returning id",
            Long.class,
            java.sql.Timestamp.from(NOW.plusSeconds(3600)));
    Long secondItem = item(1);
    jdbc.update("update item set event_id=? where id=?", event, secondItem);
    Long secondRequest = request(1);
    hold(secondRequest, secondItem);
    var stats = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);
    try {
      stats.clear();
      var result = reservations.list(firstRequest, 1L);
      assertThat(result).hasSize(1);
      assertThat(result.getFirst().itemId()).isEqualTo(firstItem);
      assertThat(result.getFirst().itemLabel()).isEqualTo("수납함");
      assertThat(result.getFirst().transitions()).isNull();
      assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
      assertThat(reservations.list(firstRequest, event)).isEmpty();
      assertThat(reservations.list(null, event))
          .extracting(ReservationResponse::requestId)
          .containsExactly(secondRequest);
    } finally {
      stats.setStatisticsEnabled(false);
    }
  }

  @TestConfiguration
  static class TimeConfiguration {
    @Bean
    @Primary
    MutableClock reservationClock() {
      return new MutableClock();
    }
  }

  static class MutableClock extends Clock {
    private final AtomicReference<Instant> time = new AtomicReference<>(NOW);
    private final ThreadLocal<java.util.function.Supplier<Instant>> override = new ThreadLocal<>();

    void override(java.util.function.Supplier<Instant> source) {
      override.set(source);
    }

    void set(Instant value) {
      time.set(value);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return override.get() == null ? time.get() : override.get().get();
    }
  }
}
