package com.afterbooth.handover.dto;

import jakarta.validation.constraints.Min;

public record ResolveHandoverRequest(@Min(1) int acceptedQty) {}
