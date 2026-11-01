package com.afterbooth.allocation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.annotation.JsonDeserialize;

public record HoldItemRequest(
    @JsonDeserialize(using = StrictIdDeserializer.class) @NotNull @Positive Long requestId,
    @JsonDeserialize(using = StrictIdDeserializer.class) @NotNull @Positive Long itemId,
    @JsonDeserialize(using = StrictQuantityDeserializer.class) @Positive int quantity) {}
