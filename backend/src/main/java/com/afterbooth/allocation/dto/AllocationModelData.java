package com.afterbooth.allocation.dto;

import com.afterbooth.allocation.domain.ReservationStatus;
import com.afterbooth.inventory.domain.*;
import com.afterbooth.inventory.dto.ItemResponse;
import com.afterbooth.request.domain.PickupRequest;
import java.time.Instant;

public final class AllocationModelData {
  private AllocationModelData() {}

  public record Request(ItemCategory category, int quantity, VehicleType vehicleType) {
    public static Request from(PickupRequest request) {
      return new Request(request.category(), request.quantity(), request.vehicleType());
    }
  }

  public record Candidate(
      Long itemId,
      ItemCategory category,
      String label,
      ItemResponse.Size sizeCm,
      Condition condition,
      int availableQty,
      boolean pickupEligible) {
    public static Candidate from(Item item, Instant now) {
      return new Candidate(
          item.id(),
          item.category(),
          item.label(),
          new ItemResponse.Size(
              item.dimensions().widthCm(),
              item.dimensions().depthCm(),
              item.dimensions().heightCm()),
          item.condition(),
          item.availableQty(),
          !item.isPickupClosed(now));
    }
  }

  public record Hold(Long itemId, int quantity, ReservationStatus status) {
    public static Hold from(ReservationResponse held) {
      return new Hold(held.itemId(), held.quantity(), held.status());
    }
  }
}
