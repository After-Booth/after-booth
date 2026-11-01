package com.afterbooth.inventory.dto;

import com.afterbooth.inventory.domain.Event;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record EventResponse(
    Long id, Long organizationId, String name, String venue, OffsetDateTime teardownDeadline) {
  public static EventResponse from(Event event) {
    return new EventResponse(
        event.id(),
        event.organizationId(),
        event.name(),
        event.venue(),
        event.teardownDeadline().atOffset(ZoneOffset.ofHours(9)));
  }
}
