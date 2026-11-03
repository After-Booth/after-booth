package com.afterbooth.inventory.dto;

import com.afterbooth.inventory.domain.Condition;
import com.afterbooth.inventory.domain.Item;
import com.afterbooth.inventory.domain.ItemCategory;
import com.afterbooth.inventory.domain.VehicleType;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public record ItemResponse(
    Long id,
    Long eventId,
    Long organizationId,
    ItemCategory category,
    String label,
    Size sizeCm,
    Condition condition,
    int totalQty,
    int availableQty,
    OffsetDateTime pickupDeadline,
    VehicleType vehicleRequirement,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String conditionsTranscript) {
  public record Size(int width, int depth, int height) {}

  public static ItemResponse from(Item i) {
    return from(i, true);
  }

  public static ItemResponse from(Item i, boolean includeTranscript) {
    return new ItemResponse(
        i.id(),
        i.eventId(),
        i.organizationId(),
        i.category(),
        i.label(),
        new Size(i.dimensions().widthCm(), i.dimensions().depthCm(), i.dimensions().heightCm()),
        i.condition(),
        i.totalQty(),
        i.availableQty(),
        i.pickupDeadline().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),
        i.vehicleRequirement(),
        includeTranscript ? i.conditionsTranscript() : null);
  }
}
