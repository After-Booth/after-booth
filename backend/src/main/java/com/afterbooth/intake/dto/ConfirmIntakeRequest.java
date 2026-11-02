package com.afterbooth.intake.dto;

import com.afterbooth.inventory.domain.Condition;
import com.afterbooth.inventory.domain.ItemCategory;
import com.afterbooth.inventory.domain.VehicleType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

public record ConfirmIntakeRequest(
    @NotEmpty List<@NotNull @Valid ConfirmLine> lines,
    OffsetDateTime pickupDeadline,
    VehicleType vehicleRequirement,
    String conditionsTranscript) {
  public record ConfirmLine(
      @JsonDeserialize(using = StrictIdDeserializer.class) @NotNull Long lineId,
      @NotNull ItemCategory category,
      @JsonDeserialize(using = StrictIntegerDeserializer.class) @Positive int quantity,
      @NotNull @Valid SizeCm sizeCm,
      @NotNull Condition condition) {}
}
