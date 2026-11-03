package com.afterbooth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.afterbooth.ai.AiCallException;
import com.afterbooth.ai.Capability;
import com.afterbooth.ai.HandoverPhotoCounter;
import com.afterbooth.allocation.dto.HoldItemRequest;
import com.afterbooth.allocation.service.ReservationService;
import com.afterbooth.global.domain.BoundingBox;
import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.handover.service.HandoverService;
import com.afterbooth.stats.service.ReuseStatsService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    properties = {"spring.docker.compose.enabled=false", "afterbooth.reservation-sweep-ms=3600000"})
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc(
    print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Import({
  com.afterbooth.AuthenticatedMockMvcConfiguration.class,
  TestcontainersConfiguration.class,
  ReservationIntegrationTest.TimeConfiguration.class
})
class HandoverIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ReservationService reservations;
  @Autowired HandoverService handovers;
  @Autowired ReuseStatsService stats;
  @Autowired ReservationIntegrationTest.MutableClock clock;
  @Autowired JsonMapper json;
  @MockitoBean HandoverPhotoCounter counter;
  static final Instant NOW = ReservationIntegrationTest.NOW;
  byte[] photo;
  private com.afterbooth.global.security.AuthenticatedMember giver;
  private com.afterbooth.global.security.AuthenticatedMember receiver;

  @BeforeEach
  void reset() throws Exception {
    clock.set(NOW);
    giver = DomainAuthTestSupport.seedOwner(jdbc, clock, 1);
    receiver = DomainAuthTestSupport.seedOwner(jdbc, clock, 2);
    jdbc.update("delete from idempotency_record");
    jdbc.update("delete from reservation_transition");
    jdbc.update("delete from handover_log");
    jdbc.update("delete from reservation");
    org.mockito.Mockito.reset(counter);
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
    photo = out.toByteArray();
  }

  Long confirmed(int quantity) {
    Long item =
        jdbc.queryForObject(
            "insert into item(event_id,organization_id,category,label,width_cm,depth_cm,height_cm,condition,total_qty,available_qty,pickup_deadline) values(1,1,'STORAGE_BIN','수납함',20,20,20,'GOOD',?,?,?) returning id",
            Long.class,
            quantity,
            quantity,
            java.sql.Timestamp.from(NOW.plusSeconds(3600)));
    Long request =
        jdbc.queryForObject(
            "insert into pickup_request(organization_id,category,quantity,vehicle_type,created_at) values(2,'STORAGE_BIN',?,'VAN',?) returning id",
            Long.class,
            quantity,
            java.sql.Timestamp.from(NOW));
    DomainAuthTestSupport.use(receiver);
    Long id = reservations.hold(new HoldItemRequest(request, item, quantity)).id();
    reservations.confirm(id);
    DomainAuthTestSupport.use(giver);
    return id;
  }

  void cancelAsReceiver(Long id) {
    DomainAuthTestSupport.use(receiver);
    try {
      reservations.cancel(id);
    } finally {
      DomainAuthTestSupport.use(giver);
    }
  }

  void counted(int count, boolean allSegmented) {
    when(counter.count(any(), anyString(), any()))
        .thenReturn(
            new HandoverPhotoCounter.Result(
                count,
                java.util.stream.IntStream.range(0, count)
                    .mapToObj(i -> new BoundingBox(0.1, 0.1, 0.2, 0.2))
                    .toList(),
                allSegmented));
  }

  org.springframework.test.web.servlet.ResultActions submit(Long id, UUID key, byte[] bytes)
      throws Exception {
    return mvc.perform(
        multipart("/api/reservations/{id}/handover", id)
            .file(new MockMultipartFile("photo", "test.png", "image/png", bytes))
            .header("Idempotency-Key", key));
  }

  org.springframework.test.web.servlet.ResultActions resolve(Long id, UUID key, int accepted)
      throws Exception {
    return mvc.perform(
        post("/api/reservations/{id}/handover/resolve", id)
            .contentType("application/json")
            .content("{\"acceptedQty\":" + accepted + "}")
            .header("Idempotency-Key", key));
  }

  int logCount(Long id) {
    return jdbc.queryForObject(
        "select count(*) from handover_log where reservation_id=?", Integer.class, id);
  }

  int stock(Long id) {
    return jdbc.queryForObject(
        "select i.available_qty from item i join reservation r on r.item_id=i.id where r.id=?",
        Integer.class,
        id);
  }

  ErrorCode attempt(Runnable action) {
    try {
      action.run();
      return null;
    } catch (BusinessException e) {
      return e.errorCode();
    }
  }

  @Test
  void orphanPhotoHandoverRecoversAndReplaysWithoutAnotherRecount() throws Exception {
    Long id = confirmed(2);
    UUID key = UUID.randomUUID();
    String path = "/api/reservations/" + id + "/handover";
    byte[] multipart =
        com.afterbooth.global.idempotency.IdempotencyService.fingerprint(
                "photo", "image/png", photo)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    jdbc.update(
        "insert into idempotency_record(idempotency_key,fingerprint,state,created_at,actor_member_id,related_organization_ids) values (?,?,'IN_PROGRESS',current_timestamp,?,array[1,2]::bigint[])",
        key,
        com.afterbooth.global.idempotency.IdempotencyService.fingerprint("POST", path, multipart),
        giver.memberId());
    counted(2, true);
    submit(id, key, photo).andExpect(status().isOk());
    submit(id, key, photo).andExpect(status().isOk());
    verify(counter, times(1)).count(any(), anyString(), any());
    assertThat(logCount(id)).isEqualTo(1);
    assertThat(reservations.get(id).status().name()).isEqualTo("HANDED_OVER");
  }

  @Test
  void matchedPhotoCompletesOnceAndIncreasesRealHttpStats() throws Exception {
    Long id = confirmed(3);
    counted(3, true);
    UUID key = UUID.randomUUID();
    var first =
        submit(id, key, photo)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.reservation.handedOverQty").value(3))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var retry =
        submit(id, key, photo)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(retry)).isEqualTo(json.readTree(first));
    verify(counter, times(1)).count(any(), anyString(), any());
    assertThat(logCount(id)).isEqualTo(1);
    assertThat(stock(id)).isZero();
    mvc.perform(get("/api/stats/reuse"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalReused").value(3))
        .andExpect(jsonPath("$.reusedVolumeLiters").value(24))
        .andExpect(jsonPath("$.byCategory[0].label").value("수납함"))
        .andExpect(jsonPath("$.byEvent[0].eventId").value(1));
    mvc.perform(get("/api/reservations").param("eventId", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(id));
    mvc.perform(get("/api/events"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(1));
    mvc.perform(get("/api/events/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.teardownDeadline").isString());
  }

  @Test
  void countMismatchAndUnsegmentedEqualCountStayConfirmedUntilPartialResolution() throws Exception {
    Long id = confirmed(3);
    counted(2, true);
    submit(id, UUID.randomUUID(), photo)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("MISMATCH"));
    counted(3, false);
    submit(id, UUID.randomUUID(), photo)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("MISMATCH"));
    assertThat(stats.get(null).totalReused()).isZero();
    assertThat(reservations.get(id).status().name()).isEqualTo("CONFIRMED");
    UUID key = UUID.randomUUID();
    resolve(id, key, 1)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));
    resolve(id, key, 1).andExpect(status().isOk());
    resolve(id, UUID.randomUUID(), 1).andExpect(status().isConflict());
    assertThat(stock(id)).isEqualTo(2);
    assertThat(stats.get(null).totalReused()).isEqualTo(1);
    assertThat(
            jdbc.queryForList(
                "select accepted_qty from handover_log where reservation_id=? order by id",
                Integer.class,
                id))
        .containsExactly(null, 1);
  }

  @Test
  void zeroSparkAndZeroSegmentedRecountsAreResolvable() throws Exception {
    for (int count : new int[] {0, 2}) {
      Long id = confirmed(2);
      counted(count, false);
      submit(id, UUID.randomUUID(), photo)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.countedQty").value(count))
          .andExpect(jsonPath("$.status").value("MISMATCH"));
      resolve(id, UUID.randomUUID(), 1).andExpect(status().isOk());
      assertThat(stock(id)).isEqualTo(1);
    }
    assertThat(stats.get(null).totalReused()).isEqualTo(2);
  }

  @Test
  void failedSparkOrSamNeverWritesEvidenceOrQuantity() throws Exception {
    for (Capability capability : List.of(Capability.SPARK, Capability.SAM)) {
      Long id = confirmed(2);
      doThrow(new AiCallException(capability, "Local failure"))
          .when(counter)
          .count(any(), anyString(), any());
      submit(id, UUID.randomUUID(), photo)
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));
      assertThat(logCount(id)).isZero();
      assertThat(stock(id)).isZero();
      assertThat(reservations.get(id).status().name()).isEqualTo("CONFIRMED");
      resolve(id, UUID.randomUUID(), 1).andExpect(status().isConflict());
    }
  }

  @Test
  void invalidAcceptedQuantityAndNoEvidenceCannotComplete() throws Exception {
    Long id = confirmed(2);
    resolve(id, UUID.randomUUID(), 1).andExpect(status().isConflict());
    counted(0, false);
    submit(id, UUID.randomUUID(), photo).andExpect(status().isOk());
    for (int accepted : new int[] {0, -1, 3})
      resolve(id, UUID.randomUUID(), accepted).andExpect(status().isBadRequest());
    assertThat(stock(id)).isZero();
    assertThat(stats.get(null).totalReused()).isZero();
    assertThat(
            jdbc.queryForObject(
                "select accepted_qty from handover_log where reservation_id=?", Integer.class, id))
        .isNull();
  }

  @Test
  void sameKeyConflictsForChangedPhotoReservationOrResolutionBody() throws Exception {
    Long id = confirmed(2);
    counted(0, false);
    UUID key = UUID.randomUUID();
    submit(id, key, photo).andExpect(status().isOk());
    byte[] changed = photo.clone();
    changed[changed.length - 1] ^= 1;
    submit(id, key, changed)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    submit(confirmed(2), key, photo).andExpect(status().isConflict());
    UUID resolveKey = UUID.randomUUID();
    resolve(id, resolveKey, 1).andExpect(status().isOk());
    resolve(id, resolveKey, 2).andExpect(status().isConflict());
    assertThat(stock(id)).isEqualTo(1);
  }

  @Test
  void deadlineAndStateAreRevalidatedAfterSlowAi() throws Exception {
    Long cancelled = confirmed(2);
    when(counter.count(any(), anyString(), any()))
        .thenAnswer(
            call -> {
              cancelAsReceiver(cancelled);
              return new HandoverPhotoCounter.Result(0, List.of(), false);
            });
    submit(cancelled, UUID.randomUUID(), photo)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
    assertThat(logCount(cancelled)).isZero();
    assertThat(stock(cancelled)).isEqualTo(2);
    Long expired = confirmed(2);
    org.mockito.Mockito.reset(counter);
    when(counter.count(any(), anyString(), any()))
        .thenAnswer(
            call -> {
              clock.set(NOW.plusSeconds(3600));
              return new HandoverPhotoCounter.Result(0, List.of(), false);
            });
    submit(expired, UUID.randomUUID(), photo)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PICKUP_CLOSED"));
    assertThat(logCount(expired)).isZero();
    assertThat(stock(expired)).isZero();
  }

  @Test
  void invalidPhotoAndClosedOrUnconfirmedReservationNeverCallAi() throws Exception {
    Long id = confirmed(2);
    submit(id, UUID.randomUUID(), new byte[] {1, 2}).andExpect(status().isBadRequest());
    clock.set(NOW.plusSeconds(3600));
    submit(id, UUID.randomUUID(), photo).andExpect(status().isConflict());
    verifyNoInteractions(counter);
    clock.set(NOW);
    cancelAsReceiver(id);
    submit(id, UUID.randomUUID(), photo).andExpect(status().isConflict());
    verifyNoInteractions(counter);
  }

  @Test
  void concurrentMatchedSubmissionsCompleteOnlyOnce() throws Exception {
    Long id = confirmed(2);
    counted(2, true);
    try (var pool =
        com.afterbooth.DomainAuthTestSupport.executor(Executors.newFixedThreadPool(2))) {
      var barrier = new CyclicBarrier(2);
      when(counter.count(any(), anyString(), any()))
          .thenAnswer(
              call -> {
                barrier.await(20, TimeUnit.SECONDS);
                return new HandoverPhotoCounter.Result(
                    2,
                    List.of(new BoundingBox(.1, .1, .2, .2), new BoundingBox(.4, .4, .2, .2)),
                    true);
              });
      var first = pool.submit(() -> attempt(() -> handovers.prepare(id, photo, "image/png").get()));
      var second =
          pool.submit(() -> attempt(() -> handovers.prepare(id, photo, "image/png").get()));
      var outcomes =
          java.util.Arrays.asList(
              first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
      assertThat(outcomes).containsExactlyInAnyOrder(null, ErrorCode.INVALID_TRANSITION);
    }
    assertThat(logCount(id)).isEqualTo(1);
    assertThat(stock(id)).isZero();
    assertThat(stats.get(null).totalReused()).isEqualTo(2);
  }

  @Test
  void retakeRacingResolutionUsesOnlyNewestLockedEvidence() throws Exception {
    Long id = confirmed(3);
    counted(0, false);
    handovers.prepare(id, photo, "image/png").get();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    when(counter.count(any(), anyString(), any()))
        .thenAnswer(
            call -> {
              entered.countDown();
              assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
              return new HandoverPhotoCounter.Result(0, List.of(), false);
            });
    try (var pool =
        com.afterbooth.DomainAuthTestSupport.executor(Executors.newSingleThreadExecutor())) {
      var retake =
          pool.submit(() -> attempt(() -> handovers.prepare(id, photo, "image/png").get()));
      assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
      handovers.resolve(id, 1);
      release.countDown();
      assertThat(retake.get(30, TimeUnit.SECONDS)).isEqualTo(ErrorCode.INVALID_TRANSITION);
    } finally {
      release.countDown();
    }
    assertThat(logCount(id)).isEqualTo(1);
    assertThat(stock(id)).isEqualTo(2);
    assertThat(stats.get(null).totalReused()).isEqualTo(1);
  }

  @Test
  void schedulerDuringRecountPreventsLateEvidenceAndRestoresOnce() throws Exception {
    Long id = confirmed(2);
    when(counter.count(any(), anyString(), any()))
        .thenAnswer(
            call -> {
              clock.set(NOW.plusSeconds(3600));
              reservations.expire(id);
              return new HandoverPhotoCounter.Result(0, List.of(), false);
            });
    submit(id, UUID.randomUUID(), photo).andExpect(status().isConflict());
    assertThat(logCount(id)).isZero();
    assertThat(stock(id)).isEqualTo(2);
    assertThat(reservations.expire(id)).isFalse();
    assertThat(stats.get(null).totalReused()).isZero();
  }

  @Test
  void manyConcurrentHttpPreparationsDoNotExhaustTheConnectionPool() throws Exception {
    int count = 12;
    var ids = new java.util.ArrayList<Long>();
    for (int i = 0; i < count; i++) ids.add(confirmed(1));
    var entered = new CountDownLatch(count);
    var release = new CountDownLatch(1);
    when(counter.count(any(), anyString(), any()))
        .thenAnswer(
            call -> {
              entered.countDown();
              assertThat(release.await(25, TimeUnit.SECONDS)).isTrue();
              return new HandoverPhotoCounter.Result(0, List.of(), false);
            });
    try (var pool =
        com.afterbooth.DomainAuthTestSupport.executor(Executors.newFixedThreadPool(count))) {
      var results = new java.util.ArrayList<Future<Integer>>();
      for (Long id : ids)
        results.add(
            pool.submit(
                () -> submit(id, UUID.randomUUID(), photo).andReturn().getResponse().getStatus()));
      assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
      release.countDown();
      for (var result : results) assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);
    } finally {
      release.countDown();
    }
    assertThat(jdbc.queryForObject("select count(*) from handover_log", Integer.class))
        .isEqualTo(count);
  }

  @Test
  void inFlightDuplicateConflictsAndCompletedFailureReplaysWithoutAi() throws Exception {
    Long id = confirmed(2);
    UUID key = UUID.randomUUID();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(counter.count(any(), anyString(), any()))
        .thenAnswer(
            call -> {
              entered.countDown();
              assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
              throw new AiCallException(Capability.SPARK, "Local failure");
            });
    try (var pool =
        com.afterbooth.DomainAuthTestSupport.executor(Executors.newSingleThreadExecutor())) {
      var first = pool.submit(() -> submit(id, key, photo).andReturn().getResponse().getStatus());
      assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
      submit(id, key, photo)
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
      release.countDown();
      assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(503);
    } finally {
      release.countDown();
    }
    submit(id, key, photo).andExpect(status().isServiceUnavailable());
    verify(counter, times(1)).count(any(), anyString(), any());
    assertThat(logCount(id)).isZero();
  }

  @Test
  void statsGroupCategoriesEventsAndReturnEmptyFilteredResult() throws Exception {
    Long first = confirmed(2),
        second = confirmed(3),
        pending = confirmed(4),
        cancelled = confirmed(1);
    Long otherEvent =
        jdbc.queryForObject(
            "insert into event(organization_id,name,venue,teardown_deadline) values(1,'Second event','Seoul',?) returning id",
            Long.class,
            java.sql.Timestamp.from(NOW.plusSeconds(3600)));
    jdbc.update(
        "update item set category='FOLDING_TABLE',label='접이식 테이블',event_id=?,width_cm=15,depth_cm=15,height_cm=16 where id=(select item_id from reservation where id=?)",
        otherEvent,
        second);
    counted(2, true);
    handovers.prepare(first, photo, "image/png").get();
    counted(0, false);
    handovers.prepare(second, photo, "image/png").get();
    handovers.resolve(second, 1);
    handovers.prepare(pending, photo, "image/png").get();
    cancelAsReceiver(cancelled);
    var result = stats.get(null);
    assertThat(result.totalReused()).isEqualTo(3);
    assertThat(result.reusedVolumeLiters()).isEqualTo(19);
    assertThat(result.byCategory()).hasSize(2);
    assertThat(result.byEvent()).hasSize(2);
    assertThat(stats.get(otherEvent).totalReused()).isEqualTo(1);
    assertThat(stats.get(otherEvent).reusedVolumeLiters()).isEqualTo(3);
    mvc.perform(get("/api/stats/reuse").param("eventId", "-1")).andExpect(status().isNotFound());
    Long emptyEvent =
        jdbc.queryForObject(
            "insert into event(organization_id,name,venue,teardown_deadline) values(1,'Empty event','Synthetic venue',?) returning id",
            Long.class,
            java.sql.Timestamp.from(NOW.plusSeconds(3600)));
    mvc.perform(get("/api/stats/reuse").param("eventId", emptyEvent.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalReused").value(0))
        .andExpect(jsonPath("$.reusedVolumeLiters").value(0))
        .andExpect(jsonPath("$.byCategory").isEmpty())
        .andExpect(jsonPath("$.byEvent").isEmpty());
  }
}
