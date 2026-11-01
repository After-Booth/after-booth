package com.afterbooth.inventory.service;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.inventory.dto.EventResponse;
import com.afterbooth.inventory.repository.EventRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {
  private final EventRepository events;

  public EventService(EventRepository events) {
    this.events = events;
  }

  @Transactional(readOnly = true)
  public List<EventResponse> list() {
    return events.findAll(org.springframework.data.domain.Sort.by("id")).stream()
        .map(EventResponse::from)
        .toList();
  }

  @Transactional(readOnly = true)
  public EventResponse get(Long id) {
    return EventResponse.from(
        events.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)));
  }
}
