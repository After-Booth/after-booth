package com.afterbooth.allocation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.afterbooth.ai.*;
import com.afterbooth.allocation.domain.*;
import com.afterbooth.allocation.dto.*;
import com.afterbooth.allocation.repository.ReservationRepository;
import com.afterbooth.allocation.service.*;
import com.afterbooth.inventory.domain.*;
import com.afterbooth.inventory.service.*;
import com.afterbooth.request.domain.PickupRequest;
import com.afterbooth.request.service.PickupRequestService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class AllocationServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
  final PickupRequestService requests = mock(PickupRequestService.class);
  final ReservationService holds = mock(ReservationService.class);
  final ReservationRepository reservations = mock(ReservationRepository.class);
  final ItemService inventory = mock(ItemService.class);
  final ItemStockService stock = mock(ItemStockService.class);
  final VehicleFitPolicy vehicles = mock(VehicleFitPolicy.class);
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path fixtures;
  final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

  AllocationService service(AiGateway ai, Instant now) {
    var locks = mock(com.afterbooth.global.idempotency.IdempotencyLockPool.class);
    when(locks.allocation(anyLong(), any()))
        .thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(1)).get());
    var access = mock(com.afterbooth.global.security.DomainAccess.class);
    var finalizer =
        new AllocationFinalizer(
            access,
            holds,
            reservations,
            requests,
            stock,
            inventory,
            vehicles,
            Clock.fixed(now, ZoneOffset.UTC));
    return new AllocationService(
        ai,
        requests,
        holds,
        reservations,
        inventory,
        stock,
        vehicles,
        Clock.fixed(now, ZoneOffset.UTC),
        json,
        5,
        Duration.ofSeconds(5),
        access,
        locks,
        finalizer);
  }

  PickupRequest request(long id, Instant now) {
    var request =
        PickupRequest.create(
            2L,
            ItemCategory.DISPLAY_RACK,
            2,
            VehicleType.SEDAN,
            now.plusSeconds(3600),
            "purpose",
            "private request transcript",
            now);
    ReflectionTestUtils.setField(request, "id", id);
    when(requests.findForAllocation(id)).thenReturn(request);
    when(requests.lock(id)).thenReturn(request);
    return request;
  }

  Item item(Instant now) {
    var item =
        Item.register(
            1L,
            1L,
            null,
            ItemCategory.DISPLAY_RACK,
            new Dimensions(20, 20, 20),
            Condition.GOOD,
            4,
            now.plusSeconds(3600),
            null,
            "private inventory transcript");
    ReflectionTestUtils.setField(item, "id", 7L);
    when(inventory.candidates()).thenReturn(List.of(item));
    when(stock.get(7L)).thenReturn(item);
    when(vehicles.checkFit(any(), any())).thenReturn(new VehicleFitResult(true, null, null));
    return item;
  }

  ReservationResponse held(long id, long request, Instant now) {
    return new ReservationResponse(
        id,
        request,
        2L,
        7L,
        "진열대",
        2,
        null,
        ReservationStatus.HELD,
        now.plusSeconds(600).atOffset(ZoneOffset.UTC),
        now.plusSeconds(3600).atOffset(ZoneOffset.UTC),
        now.atOffset(ZoneOffset.UTC),
        List.of());
  }

  static ChatResponse tool(String id, String name, String args) {
    return new ChatResponse(null, List.of(new ToolCall(id, name, args)), "tool_calls", null);
  }

  List<ChatRequest> scenario(
      long requestId,
      long reservationId,
      Instant now,
      AllocationReplaySupport support,
      boolean replay) {
    request(requestId, now);
    item(now);
    when(holds.holdForAgent(any())).thenReturn(held(reservationId, requestId, now));
    List<ChatRequest> captured = new ArrayList<>();
    AiGateway ai = mock(AiGateway.class);
    when(ai.chat(any()))
        .thenAnswer(
            invocation -> {
              captured.add(invocation.getArgument(0));
              if (replay) return support.replay(invocation.getArgument(0));
              ChatResponse response =
                  switch (captured.size()) {
                    case 1 -> tool("search", "searchCandidates", "{}");
                    case 2 -> tool("hold", "holdQuantity", "{\"itemId\":7,\"quantity\":2}");
                    default -> new ChatResponse("임시 확보했어요.", List.of(), "stop", null);
                  };
              return support.record(invocation.getArgument(0), response);
            });
    assertThat(service(ai, now).run(requestId).reservations())
        .singleElement()
        .satisfies(r -> assertThat(r.id()).isEqualTo(reservationId));
    return captured;
  }

  @Test
  void modelConversationAndRealReplayKeysIgnoreRequestReservationIdsAndWallClock()
      throws Exception {
    try (var support = new AllocationReplaySupport(fixtures)) {
      var first = scenario(11L, 31L, NOW, support, false);
      support.startReplay();
      var second = scenario(12L, 32L, NOW.plusSeconds(86400), support, true);
      assertThat(second).containsExactlyElementsOf(first);
      assertThat(json.writeValueAsString(first))
          .doesNotContain(
              "requestId",
              "reservationId",
              "createdAt",
              "pickupDeadline",
              "holdExpiresAt",
              "transcript",
              "organizationId",
              "eventId");
      support.assertComplete(3);
    }
  }

  @Test
  void networkLossAfterALiveTurnRestartsOnTheRecordedConversation() throws Exception {
    try (var support = new AllocationReplaySupport(fixtures)) {
      var recorded = scenario(11L, 31L, NOW, support, false);
      support.startReplay();
      request(12L, NOW);
      item(NOW);
      when(holds.holdForAgent(any())).thenReturn(held(32L, 12L, NOW));
      List<ChatRequest> sent = new ArrayList<>();
      AiGateway ai = mock(AiGateway.class);
      when(ai.chat(any()))
          .thenAnswer(
              invocation -> {
                ChatRequest request = invocation.getArgument(0);
                sent.add(request);
                // A live first answer carries its own tool-call ID, so the next key has no
                // recording
                return sent.size() == 1
                    ? tool("live-search", "searchCandidates", "{}")
                    : support.replay(request);
              });

      assertThat(service(ai, NOW).run(12L).reservations())
          .singleElement()
          .satisfies(r -> assertThat(r.id()).isEqualTo(32L));
      assertThat(sent).hasSize(5);
      assertThat(sent.subList(2, 5)).containsExactlyElementsOf(recorded);
      support.assertComplete(3);
    }
  }

  @Test
  void databaseFailureBeforeAndAfterHoldPropagatesInsteadOfReturningSuccess() {
    for (boolean afterHold : List.of(false, true)) {
      request(11L, NOW);
      item(NOW);
      var failure = new DataAccessResourceFailureException("local database unavailable");
      when(holds.holdForAgent(any())).thenReturn(held(31L, 11L, NOW));
      when(inventory.candidates()).thenThrow(failure);
      AiGateway ai = mock(AiGateway.class);
      when(ai.chat(any()))
          .thenReturn(
              afterHold
                  ? tool("hold", "holdQuantity", "{\"itemId\":7,\"quantity\":2}")
                  : tool("search", "searchCandidates", "{}"),
              tool("search", "searchCandidates", "{}"));
      assertThatThrownBy(() -> service(ai, NOW).run(11L)).isSameAs(failure);
      reset(inventory);
    }
  }

  @Test
  void searchWithoutCategoryDescribesRequestedInventory() {
    assertSearchSummary("{}", "요청한 종류의 재고를 조회했어요.");
  }

  @Test
  void searchWithRequestedCategoryDescribesRequestedInventory() {
    assertSearchSummary("{\"category\":\"DISPLAY_RACK\"}", "요청한 종류의 재고를 조회했어요.");
  }

  @Test
  void searchWithAnotherCategoryDescribesOtherInventory() {
    assertSearchSummary("{\"category\":\"FOLDING_TABLE\"}", "다른 종류의 재고도 살펴봤어요.");
  }

  private void assertSearchSummary(String arguments, String summary) {
    request(11L, NOW);
    item(NOW);
    AiGateway ai = mock(AiGateway.class);
    when(ai.chat(any()))
        .thenReturn(
            tool("search", "searchCandidates", arguments),
            new ChatResponse("재고를 확인했어요.", List.of(), "stop", null));

    assertThat(service(ai, NOW).run(11L).toolCalls())
        .singleElement()
        .satisfies(trace -> assertThat(trace.summary()).isEqualTo(summary));
  }

  @Test
  void categorySearchIsOptionalValidatedAndCannotSmuggleExtraFields() {
    assertThat(AllocationArguments.parse(json, "searchCandidates", "{}").category()).isNull();
    assertThat(
            AllocationArguments.parse(json, "searchCandidates", "{\"category\":\"FOLDING_TABLE\"}")
                .category())
        .isEqualTo(ItemCategory.FOLDING_TABLE);
    for (String raw :
        List.of(
            "{\"category\":\"bad\"}",
            "{\"category\":null}",
            "{\"category\":1}",
            "{\"category\":\"DISPLAY_RACK\",\"requestId\":1}",
            "{")) {
      assertThatThrownBy(() -> AllocationArguments.parse(json, "searchCandidates", raw))
          .isInstanceOf(AgentProtocolViolation.class);
    }
  }
}
