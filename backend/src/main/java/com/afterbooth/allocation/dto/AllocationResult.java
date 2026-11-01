package com.afterbooth.allocation.dto;

import com.afterbooth.inventory.dto.ItemResponse;
import java.util.List;
import java.util.Map;

public record AllocationResult(
    Long requestId,
    String status,
    String explanation,
    List<ReservationResponse> reservations,
    List<Rejection> rejections,
    List<Alternative> alternatives,
    List<ToolTrace> toolCalls) {
  public record Rejection(Long itemId, String code, String message, String limitingDimension) {}

  public record Alternative(ItemResponse item, int quantity, String reason) {}

  public record ToolTrace(
      String name, Map<String, Object> arguments, String outcome, String code, String summary) {}
}
