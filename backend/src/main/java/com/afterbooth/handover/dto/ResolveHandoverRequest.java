package com.afterbooth.handover.dto;

import jakarta.validation.constraints.Min;
import tools.jackson.databind.annotation.JsonDeserialize;

public record ResolveHandoverRequest(
    @JsonDeserialize(using = StrictQuantityDeserializer.class) @Min(1) int acceptedQty) {}
