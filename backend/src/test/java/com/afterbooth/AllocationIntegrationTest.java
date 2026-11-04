package com.afterbooth;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.afterbooth.ai.*;
import com.afterbooth.allocation.dto.*;
import com.afterbooth.allocation.service.*;
import com.afterbooth.global.error.*;
import com.afterbooth.global.idempotency.IdempotencyService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.docker.compose.enabled=false",
      "afterbooth.reservation-sweep-ms=3600000",
      "afterbooth.allocation.max-turns=3",
      "afterbooth.allocation.timeout=PT2S"
    })
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Import({
  com.afterbooth.AuthenticatedMockMvcConfiguration.class,
  TestcontainersConfiguration.class,
  AllocationIntegrationTest.Config.class
})
class AllocationIntegrationTest {
  private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
  @Autowired AllocationService allocations;
  @Autowired ReservationService reservations;
  @Autowired IdempotencyService idempotency;
  @Autowired JdbcTemplate jdbc;
  @Autowired StubAi ai;
  @Autowired Clock clock;
  @Autowired MockMvc mvc;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  com.afterbooth.inventory.service.ItemService inventory;

  @Autowired tools.jackson.databind.json.JsonMapper json;

  @BeforeEach
  void reset() {
    DomainAuthTestSupport.seedOwner(jdbc, clock, 1);
    DomainAuthTestSupport.seedOwner(jdbc, clock, 2);
    jdbc.update("delete from idempotency_record");
    jdbc.update("delete from reservation_transition");
    jdbc.update("delete from handover_log");
    jdbc.update("delete from reservation");
    jdbc.update("update item set available_qty=0");
    org.mockito.Mockito.reset(inventory);
    ai.action = ignored -> done();
  }

  long item(String category, int qty, boolean expired, boolean oversized) {
    return jdbc.queryForObject(
        "insert into item(event_id,organization_id,category,label,width_cm,depth_cm,height_cm,condition,total_qty,available_qty,pickup_deadline) values(1,1,?,'테스트 물품',20,20,?,'GOOD',?,?,?) returning id",
        Long.class,
        category,
        oversized ? 1000 : 20,
        qty,
        qty,
        java.sql.Timestamp.from(NOW.plusSeconds(expired ? -1 : 3600)));
  }

  long request(int qty) {
    return jdbc.queryForObject(
        "insert into pickup_request(organization_id,category,quantity,vehicle_type,created_at) values(2,'DISPLAY_RACK',?,'SEDAN',?) returning id",
        Long.class,
        qty,
        java.sql.Timestamp.from(NOW));
  }

  int stock(long id) {
    return jdbc.queryForObject("select available_qty from item where id=?", Integer.class, id);
  }

  static ChatResponse done() {
    return new ChatResponse("배정 결과를 확인해 주세요.", List.of(), "stop", null);
  }

  static ChatResponse call(String name, String arguments) {
    return new ChatResponse(
        null,
        List.of(new ToolCall(UUID.randomUUID().toString(), name, arguments)),
        "tool_calls",
        null);
  }

  static ChatResponse hold(long id, int qty) {
    return call("holdQuantity", "{\"itemId\":" + id + ",\"quantity\":" + qty + "}");
  }

  static boolean first(ChatRequest request) {
    return request.messages().size() == 2;
  }

  void propose(long id, int qty) {
    ai.action = request -> first(request) ? hold(id, qty) : done();
  }

  @Test
  void partialHoldSurvivesModelFailureAndSameKeyReplaysWithoutDuplicate() {
    long item = item("DISPLAY_RACK", 2, false, false);
    long alternative = item("FOLDING_TABLE", 3, false, false);
    long request = request(5);
    ai.action =
        input -> {
          if (first(input)) return hold(item, 2);
          throw new AiCallException(Capability.SPARK, "Local failure");
        };
    UUID key = UUID.randomUUID();
    String path = "/api/pickup-requests/" + request + "/allocation";
    var result =
        idempotency.executePrepared(
            key, "POST", path, new byte[0], 200, () -> allocations.prepare(request));
    assertThat(result.getStatusCode().value()).isEqualTo(200);
    assertThat(result.getBody()).contains("HELD", "Held 2 of 5", "alternatives");
    assertThat(stock(item)).isZero();
    var alternatives = json.readTree(result.getBody()).get("alternatives");
    assertThat(alternatives.size()).isEqualTo(1);
    assertThat(alternatives.get(0).get("item").get("id").asLong()).isEqualTo(alternative);
    var replay =
        idempotency.execute(
            key,
            "POST",
            path,
            new byte[0],
            200,
            () -> {
              throw new AssertionError("Must replay");
            });
    assertThat(replay.getStatusCode()).isEqualTo(result.getStatusCode());
    assertThat(json.readTree(replay.getBody())).isEqualTo(json.readTree(result.getBody()));
    assertThat(reservations.list(request, null)).hasSize(1);
    assertThat(reservations.hold(new HoldItemRequest(request, alternative, 3)).status().name())
        .isEqualTo("HELD");
  }

  @Test
  void partialHoldOffersSameItemForManualTopUpAfterModelFailure() throws Exception {
    long item = item("DISPLAY_RACK", 10, false, false);
    long request = request(5);
    ai.action =
        input -> {
          if (first(input)) return hold(item, 2);
          throw new AiCallException(Capability.SPARK, "Local failure");
        };

    AllocationResult result = allocations.run(request);
    assertThat(result.status()).isEqualTo("HELD");
    assertThat(result.explanation()).contains("Held 2 of 5");
    assertThat(result.reservations())
        .singleElement()
        .satisfies(r -> assertThat(r.quantity()).isEqualTo(2));
    assertThat(result.alternatives())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.item().id()).isEqualTo(item);
              assertThat(a.item().availableQty()).isEqualTo(8);
              assertThat(a.quantity()).isEqualTo(3);
            });
    assertThat(result.rejections()).noneMatch(r -> r.itemId().equals(item));
    assertThat(stock(item)).isEqualTo(8);

    mvc.perform(
            post("/api/reservations")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(json.writeValueAsString(new HoldItemRequest(request, item, 3))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.quantity").value(3))
        .andExpect(jsonPath("$.status").value("HELD"));
    assertThat(reservations.list(request, null))
        .hasSize(2)
        .allSatisfy(r -> assertThat(r.status().name()).isEqualTo("HELD"));
    assertThat(
            reservations.list(request, null).stream().mapToInt(ReservationResponse::quantity).sum())
        .isEqualTo(5);
    assertThat(stock(item)).isEqualTo(5);

    mvc.perform(
            post("/api/reservations")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json")
                .content(json.writeValueAsString(new HoldItemRequest(request, item, 1))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("QTY_EXCEEDS_REQUEST"));
    assertThat(stock(item)).isEqualTo(5);
    assertThat(reservations.list(request, null)).hasSize(2);
  }

  @Test
  void fullyAllocatedRequestOffersNoAlternativesDespiteRemainingStock() {
    long item = item("DISPLAY_RACK", 10, false, false);
    long other = item("FOLDING_TABLE", 3, false, false);
    propose(item, 5);

    AllocationResult result = allocations.run(request(5));

    assertThat(result.status()).isEqualTo("HELD");
    assertThat(result.alternatives()).isEmpty();
    assertThat(stock(item)).isEqualTo(5);
    assertThat(stock(other)).isEqualTo(3);
  }

  @Test
  void serverRejectsOtherCategoryOversizedVehicleExpiredStockAndExcessQuantity() {
    for (String kind : List.of("category", "vehicle", "deadline", "stock", "quantity")) {
      long item =
          item(
              kind.equals("category") ? "FOLDING_TABLE" : "DISPLAY_RACK",
              1,
              kind.equals("deadline"),
              kind.equals("vehicle"));
      if (kind.equals("stock")) jdbc.update("update item set available_qty=0 where id=?", item);
      long request = request(1);
      propose(item, kind.equals("quantity") ? 2 : 1);
      AllocationResult result = allocations.run(request);
      assertThat(result.status()).isEqualTo("NO_MATCH");
      assertThat(result.reservations()).isEmpty();
      assertThat(result.toolCalls())
          .singleElement()
          .extracting(AllocationResult.ToolTrace::outcome)
          .isEqualTo("REJECTED");
      assertThat(reservations.list(request, null)).isEmpty();
      assertThat(result.alternatives())
          .allSatisfy(
              a ->
                  assertThat(result.rejections()).noneMatch(r -> r.itemId().equals(a.item().id())));
    }
  }

  @Test
  void cumulativeHoldsCannotExceedRequestAndRejectedToolDoesNotRollbackFirstHold() {
    long first = item("DISPLAY_RACK", 2, false, false);
    long second = item("DISPLAY_RACK", 2, false, false);
    ai.action =
        input ->
            first(input)
                ? new ChatResponse(
                    null,
                    List.of(
                        new ToolCall(
                            "one", "holdQuantity", "{\"itemId\":" + first + ",\"quantity\":2}"),
                        new ToolCall(
                            "two", "holdQuantity", "{\"itemId\":" + second + ",\"quantity\":1}")),
                    "tool_calls",
                    null)
                : done();
    AllocationResult result = allocations.run(request(2));
    assertThat(result.status()).isEqualTo("HELD");
    assertThat(result.reservations()).hasSize(1);
    assertThat(result.rejections()).anyMatch(r -> r.code().equals("QTY_EXCEEDS_REQUEST"));
    assertThat(stock(first)).isZero();
    assertThat(stock(second)).isEqualTo(2);
  }

  @Test
  void databaseFailureDuringToolExecutionRollsBackEvenAnEarlierHold() {
    for (boolean afterHold : List.of(false, true)) {
      long item = item("DISPLAY_RACK", 2, false, false);
      long request = request(2);
      var failure = new org.springframework.dao.DataAccessResourceFailureException("local failure");
      org.mockito.Mockito.doThrow(failure).when(inventory).candidates();
      ai.action =
          input -> first(input) && afterHold ? hold(item, 1) : call("searchCandidates", "{}");
      assertThatThrownBy(() -> allocations.run(request)).isSameAs(failure);
      assertThat(stock(item)).isEqualTo(2);
      assertThat(reservations.list(request, null)).isEmpty();
      org.mockito.Mockito.reset(inventory);
    }
  }

  @Test
  void modelMayInspectOtherCategoriesButCannotHoldThem() {
    long item = item("FOLDING_TABLE", 2, false, false);
    ai.action =
        input -> {
          if (first(input)) return call("searchCandidates", "{\"category\":\"FOLDING_TABLE\"}");
          if (input.messages().size() == 4) {
            assertThat(json.writeValueAsString(input.messages()))
                .contains("FOLDING_TABLE", "itemId");
            return hold(item, 1);
          }
          return done();
        };
    var result = allocations.run(request(1));
    assertThat(result.status()).isEqualTo("NO_MATCH");
    assertThat(result.toolCalls()).anyMatch(t -> "CATEGORY_MISMATCH".equals(t.code()));
    assertThat(stock(item)).isEqualTo(2);
  }

  @Test
  void malformedUnknownAndForbiddenToolsFailBeforeAnyMutation() {
    long item = item("DISPLAY_RACK", 5, false, false);
    for (String name : List.of("confirmReservation", "cancelReservation", "unknown")) {
      ai.action = ignored -> call(name, "{}");
      assertUnavailable(request(1));
    }
    for (String args :
        List.of(
            "{}",
            "[]",
            "{",
            "{\"itemId\":1,\"quantity\":-1}",
            "{\"itemId\":1,\"quantity\":1.5}",
            "{\"itemId\":1,\"quantity\":2147483648}",
            "{\"itemId\":1,\"quantity\":1,\"requestId\":99}")) {
      ai.action = ignored -> call("holdQuantity", args);
      assertUnavailable(request(1));
    }
    assertThat(stock(item)).isEqualTo(5);
  }

  @Test
  void forbiddenToolAfterHoldKeepsOnlyHeldStateWithTemplateExplanation() {
    long item = item("DISPLAY_RACK", 1, false, false);
    long request = request(1);
    ai.action =
        ignored ->
            new ChatResponse(
                null,
                List.of(
                    new ToolCall(
                        "hold", "holdQuantity", "{\"itemId\":" + item + ",\"quantity\":1}"),
                    new ToolCall("confirm", "confirmReservation", "{\"reservationId\":1}")),
                "tool_calls",
                null);
    AllocationResult result = allocations.run(request);
    assertThat(result.status()).isEqualTo("HELD");
    assertThat(result.explanation()).contains("Held 1 of 1");
    assertThat(result.toolCalls()).hasSize(1);
    assertThat(reservations.list(request, null))
        .singleElement()
        .extracting(r -> r.status().name())
        .isEqualTo("HELD");
    assertThat(stock(item)).isZero();
  }

  @Test
  void vehicleToolReturnsSharedLimitingDimension() {
    long item = item("DISPLAY_RACK", 1, false, true);
    ai.action =
        input -> first(input) ? call("checkVehicleFit", "{\"itemId\":" + item + "}") : done();
    AllocationResult result = allocations.run(request(1));
    assertThat(result.rejections())
        .anySatisfy(
            r -> {
              assertThat(r.itemId()).isEqualTo(item);
              assertThat(r.code()).isEqualTo("VEHICLE_TOO_SMALL");
              assertThat(r.limitingDimension()).isEqualTo("height");
            });
  }

  @Test
  void repeatedRejectedCallsStopAndSearchOnlyReturnsRequestedCategory() {
    long item = item("FOLDING_TABLE", 1, false, false);
    ai.action = input -> hold(item, 1);
    AllocationResult result = allocations.run(request(1));
    assertThat(result.status()).isEqualTo("NO_MATCH");
    assertThat(result.toolCalls()).hasSize(3);
    assertThat(result.rejections()).noneMatch(r -> r.itemId().equals(item));
    assertThat(result.alternatives()).anyMatch(a -> a.item().id().equals(item));
    ai.action = input -> first(input) ? call("searchCandidates", "{}") : done();
    assertThat(allocations.run(request(1)).toolCalls())
        .singleElement()
        .extracting(AllocationResult.ToolTrace::name)
        .isEqualTo("searchCandidates");
  }

  @Test
  void turnLimitAndBlockingGatewayDeadlineTerminateSafely() {
    AtomicInteger calls = new AtomicInteger();
    ai.action =
        input -> {
          calls.incrementAndGet();
          return call("searchCandidates", "{}");
        };
    assertUnavailable(request(1));
    assertThat(calls).hasValue(3);
    CountDownLatch release = new CountDownLatch(1);
    ai.action =
        ignored -> {
          while (true) {
            try {
              if (release.await(10, TimeUnit.SECONDS)) return done();
            } catch (InterruptedException interrupted) {
              /* Model an uncooperative blocking transport. */
            }
          }
        };
    long started = System.nanoTime();
    try {
      assertUnavailable(request(1));
    } finally {
      release.countDown();
    }
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
  }

  @Test
  void simultaneousRequestsForLastItemProduceOneHoldAndActionableAlternative() throws Exception {
    long item = item("DISPLAY_RACK", 1, false, false);
    long alternative = item("FOLDING_TABLE", 3, false, false);
    long first = request(1), second = request(1);
    CountDownLatch modelsReady = new CountDownLatch(2);
    ai.action =
        input -> {
          if (!first(input)) return done();
          modelsReady.countDown();
          try {
            if (!modelsReady.await(1, TimeUnit.SECONDS))
              throw new AssertionError("Models not concurrent");
          } catch (InterruptedException error) {
            throw new AssertionError(error);
          }
          return hold(item, 1);
        };
    try (ExecutorService pool =
        com.afterbooth.DomainAuthTestSupport.executor(Executors.newFixedThreadPool(2))) {
      Future<AllocationResult> a = pool.submit(() -> allocations.run(first));
      Future<AllocationResult> b = pool.submit(() -> allocations.run(second));
      List<AllocationResult> results =
          List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
      assertThat(results)
          .extracting(AllocationResult::status)
          .containsExactlyInAnyOrder("HELD", "NO_MATCH");
      AllocationResult loser =
          results.stream().filter(r -> r.status().equals("NO_MATCH")).findFirst().orElseThrow();
      assertThat(loser.rejections())
          .anyMatch(r -> r.itemId().equals(item) && r.code().equals("QTY_UNAVAILABLE"));
      assertThat(loser.alternatives()).anyMatch(v -> v.item().id().equals(alternative));
      assertThat(
              reservations
                  .hold(new HoldItemRequest(loser.requestId(), alternative, 1))
                  .status()
                  .name())
          .isEqualTo("HELD");
    }
    assertThat(stock(item)).isZero();
  }

  @Test
  void concurrentRunsForSameRequestSerializeAndSecondReturnsAlreadyHeld() throws Exception {
    long item = item("DISPLAY_RACK", 2, false, false);
    long request = request(2);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    ai.action =
        input -> {
          if (!first(input)) return done();
          entered.countDown();
          try {
            if (!release.await(1, TimeUnit.SECONDS)) throw new AssertionError("Release timed out");
          } catch (InterruptedException error) {
            throw new AssertionError(error);
          }
          return hold(item, 1);
        };
    try (ExecutorService pool =
        com.afterbooth.DomainAuthTestSupport.executor(Executors.newFixedThreadPool(2))) {
      Future<AllocationResult> first = pool.submit(() -> allocations.run(request));
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      Future<ErrorCode> second =
          pool.submit(
              () -> {
                try {
                  allocations.run(request);
                  return null;
                } catch (BusinessException error) {
                  return error.errorCode();
                }
              });
      release.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo("HELD");
      assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorCode.REQUEST_ALREADY_HELD);
    }
    assertThat(stock(item)).isEqualTo(1);
  }

  @Test
  void actualInventoryAndAllocationHttpRoutesMatchContract() throws Exception {
    long item = item("DISPLAY_RACK", 1, false, false);
    long request = request(1);
    propose(item, 1);
    mvc.perform(
            post("/api/pickup-requests/{id}/allocation", request)
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("HELD"))
        .andExpect(jsonPath("$.toolCalls[0].name").value("holdQuantity"));
    mvc.perform(get("/api/items/{id}", item))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.availableQty").value(0))
        .andExpect(jsonPath("$.pickupDeadline").value(org.hamcrest.Matchers.endsWith("+09:00")));
    mvc.perform(get("/api/items"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == " + item + ")]").isNotEmpty());
    mvc.perform(get("/api/items").param("availableOnly", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == " + item + ")]").isEmpty());
    mvc.perform(get("/api/reservations").param("requestId", Long.toString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].requestId").value(request));
    mvc.perform(get("/api/pickup-requests/{id}", request))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(request));
  }

  void assertUnavailable(long request) {
    assertThatThrownBy(() -> allocations.run(request))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.AI_UNAVAILABLE));
  }

  @TestConfiguration
  static class Config {
    @Bean
    @Primary
    Clock allocationClock() {
      return Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
    }

    @Bean
    @Primary
    StubAi stubAi() {
      return new StubAi();
    }
  }

  static class StubAi implements AiGateway {
    volatile Function<ChatRequest, ChatResponse> action;

    public ChatResponse chat(ChatRequest request) {
      return action.apply(request);
    }

    public List<SamSegment> segment(SamRequest request) {
      throw new AssertionError("Not used");
    }

    public Transcription transcribe(byte[] wav) {
      throw new AssertionError("Not used");
    }
  }
}
