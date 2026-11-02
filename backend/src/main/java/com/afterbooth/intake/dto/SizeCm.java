package com.afterbooth.intake.dto;

import com.afterbooth.inventory.domain.Dimensions;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.annotation.JsonDeserialize;

public record SizeCm(
    @JsonDeserialize(using = StrictIntegerDeserializer.class) @Positive int width,
    @JsonDeserialize(using = StrictIntegerDeserializer.class) @Positive int depth,
    @JsonDeserialize(using = StrictIntegerDeserializer.class) @Positive int height) {
  public Dimensions dimensions() {
    return new Dimensions(width, depth, height);
  }

  public static SizeCm from(Dimensions size) {
    return new SizeCm(size.widthCm(), size.depthCm(), size.heightCm());
  }
}
