package com.afterbooth.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.afterbooth.allocation.domain.Reservation;
import com.afterbooth.allocation.dto.AllocationResult.ToolTrace;
import com.afterbooth.allocation.dto.HoldItemRequest;
import com.afterbooth.allocation.dto.ReservationResponse;
import com.afterbooth.allocation.repository.ReservationRepository;
import com.afterbooth.allocation.service.AllocationFinalizer;
import com.afterbooth.allocation.service.ReservationService;
import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.security.DomainAccess;
import com.afterbooth.inventory.domain.*;
import com.afterbooth.inventory.service.*;
import com.afterbooth.request.domain.PickupRequest;
import com.afterbooth.request.service.PickupRequestService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AllocationFinalizerTest {
  @Test
  void finalStockLossPreservesAcceptedTraceWhenLaterProposalUsesTheSameItem() {
    var now = Instant.parse("2026-11-01T00:00:00Z");
    var access = mock(DomainAccess.class);
    var holds = mock(ReservationService.class);
    var reservations = mock(ReservationRepository.class);
    var requests = mock(PickupRequestService.class);
    var stock = mock(ItemStockService.class);
    var inventory = mock(ItemService.class);
    var vehicles = mock(VehicleFitPolicy.class);
    var request =
        PickupRequest.create(
            2L,
            ItemCategory.STORAGE_BIN,
            3,
            VehicleType.VAN,
            now.plusSeconds(3600),
            null,
            null,
            now);
    ReflectionTestUtils.setField(request, "id", 1L);
    var item =
        Item.register(
            1L,
            1L,
            null,
            ItemCategory.STORAGE_BIN,
            new Dimensions(20, 20, 20),
            Condition.GOOD,
            1,
            now.plusSeconds(3600),
            null,
            null);
    ReflectionTestUtils.setField(item, "id", 7L);
    var reservation =
        Reservation.hold(1L, 7L, 2L, 1, Duration.ofMinutes(15), now.plusSeconds(3600), now);
    ReflectionTestUtils.setField(reservation, "id", 31L);
    var first = new HoldItemRequest(1L, 7L, 1);
    var second = new HoldItemRequest(1L, 7L, 2);
    when(access.request(1L)).thenReturn(2L);
    when(requests.lock(1L)).thenReturn(request);
    when(stock.get(7L)).thenReturn(item);
    when(holds.holdForAgent(first))
        .thenReturn(ReservationResponse.from(reservation, "Synthetic bin", null));
    when(holds.holdForAgent(second)).thenThrow(new BusinessException(ErrorCode.QTY_UNAVAILABLE));
    when(inventory.candidates()).thenReturn(List.of());
    var trace =
        List.of(
            new ToolTrace(
                "holdQuantity",
                Map.of("itemId", 7L, "quantity", 1),
                "OK",
                null,
                "Synthetic first hold"),
            new ToolTrace(
                "holdQuantity",
                Map.of("itemId", 7L, "quantity", 2),
                "OK",
                null,
                "Synthetic second hold"));
    var result =
        new AllocationFinalizer(
                access,
                holds,
                reservations,
                requests,
                stock,
                inventory,
                vehicles,
                Clock.fixed(now, ZoneOffset.UTC))
            .complete(
                1L,
                List.of(first, second),
                Map.of(),
                trace,
                "Prepared explanation",
                List.of(1L, 2L));
    assertThat(result.reservations())
        .singleElement()
        .satisfies(held -> assertThat(held.quantity()).isEqualTo(1));
    assertThat(result.toolCalls()).extracting(ToolTrace::outcome).containsExactly("OK", "REJECTED");
    assertThat(result.toolCalls().get(1).code()).isEqualTo("QTY_UNAVAILABLE");
    assertThat(result.toolCalls().get(0).arguments().get("quantity")).isEqualTo(1);
    assertThat(result.toolCalls().get(1).arguments().get("quantity")).isEqualTo(2);
  }
}
