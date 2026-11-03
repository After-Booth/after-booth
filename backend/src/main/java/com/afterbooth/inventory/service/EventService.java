package com.afterbooth.inventory.service;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.security.DomainAccess;
import com.afterbooth.inventory.domain.Event;
import com.afterbooth.inventory.dto.CreateEventRequest;
import com.afterbooth.inventory.dto.EventResponse;
import com.afterbooth.inventory.repository.EventRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class EventService {
  private final EventRepository events;
  private final DomainAccess access;
  private final Clock clock;
  private final JsonMapper json;

  public EventService(EventRepository events, DomainAccess access, Clock clock, JsonMapper json) {
    this.events = events;
    this.access = access;
    this.clock = clock;
    this.json = json;
  }

  @Transactional(readOnly = true)
  public List<EventResponse> list() {
    access.actor();
    return events.findAll(org.springframework.data.domain.Sort.by("id")).stream()
        .filter(event -> access.owner(event.organizationId()))
        .map(EventResponse::from)
        .toList();
  }

  @Transactional(readOnly = true)
  public EventResponse get(Long id) {
    access.event(id);
    return EventResponse.from(
        events.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)));
  }

  @Transactional
  public EventResponse create(byte[] body) {
    var request = CreateEventRequest.parse(json, body);
    long organizationId = access.organizationId();
    access.lockOrganizations(List.of(organizationId));
    var event =
        Event.schedule(
            organizationId,
            request.name(),
            request.venue(),
            request.teardownDeadline().toInstant());
    event.requireFutureDeadline(clock.instant());
    return EventResponse.from(events.saveAndFlush(event));
  }
}
