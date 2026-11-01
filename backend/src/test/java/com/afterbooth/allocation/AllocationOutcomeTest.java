package com.afterbooth.allocation;

import static org.assertj.core.api.Assertions.*;

import com.afterbooth.allocation.domain.*;
import com.afterbooth.allocation.dto.*;
import com.afterbooth.inventory.domain.*;
import com.afterbooth.request.domain.PickupRequest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AllocationOutcomeTest {
  final Instant now = Instant.parse("2026-11-01T00:00:00Z");

  @Test
  void fullPartialAndNoMatchUseRemainingCapacityAndKeepSameItemTopUp() {
    var request =
        PickupRequest.create(
            2L, ItemCategory.DISPLAY_RACK, 5, VehicleType.SEDAN, null, null, null, now);
    var item =
        Item.register(
            1L,
            1L,
            null,
            ItemCategory.DISPLAY_RACK,
            new Dimensions(20, 20, 20),
            Condition.GOOD,
            8,
            now.plusSeconds(3600),
            null,
            null);
    ReflectionTestUtils.setField(item, "id", 7L);
    for (int held : List.of(0, 2, 5)) {
      List<ReservationResponse> reservations =
          held == 0
              ? List.of()
              : List.of(
                  new ReservationResponse(
                      1L,
                      1L,
                      2L,
                      7L,
                      "진열대",
                      held,
                      null,
                      ReservationStatus.HELD,
                      null,
                      null,
                      null,
                      List.of()));
      var rejected = Map.of(7L, new AllocationResult.Rejection(7L, "QTY_UNAVAILABLE", "재고", null));
      var result =
          AllocationOutcome.assemble(
              request,
              reservations,
              rejected,
              List.of(),
              List.of(item),
              ignored -> new VehicleFitResult(true, null, null),
              now,
              null);
      assertThat(result.status()).isEqualTo(held == 0 ? "NO_MATCH" : "HELD");
      if (held == 5) assertThat(result.alternatives()).isEmpty();
      else {
        assertThat(result.alternatives())
            .singleElement()
            .satisfies(
                a -> {
                  assertThat(a.item().id()).isEqualTo(7L);
                  assertThat(a.quantity()).isEqualTo(5 - held);
                });
        assertThat(result.rejections()).isEmpty();
      }
      assertThat(rejected).hasSize(1);
    }
  }

  @Test
  void existingRejectionIsNotDuplicatedByOutcomeValidation() {
    var request =
        PickupRequest.create(
            2L, ItemCategory.DISPLAY_RACK, 1, VehicleType.SEDAN, null, null, null, now);
    var item =
        Item.register(
            1L,
            1L,
            null,
            ItemCategory.DISPLAY_RACK,
            new Dimensions(20, 20, 20),
            Condition.GOOD,
            1,
            now.minusSeconds(1),
            null,
            null);
    ReflectionTestUtils.setField(item, "id", 7L);
    var rejection = new AllocationResult.Rejection(7L, "PICKUP_CLOSED", "진열대: 마감", null);
    var result =
        AllocationOutcome.assemble(
            request,
            List.of(),
            Map.of(7L, rejection),
            List.of(),
            List.of(item),
            ignored -> new VehicleFitResult(true, null, null),
            now,
            null);
    assertThat(result.rejections()).containsExactly(rejection);
    assertThat(result.alternatives()).isEmpty();
    assertThat(result.status()).isEqualTo("NO_MATCH");
  }
}
