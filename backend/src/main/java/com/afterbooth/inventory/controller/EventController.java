package com.afterbooth.inventory.controller;

import com.afterbooth.inventory.dto.EventResponse;
import com.afterbooth.inventory.service.EventService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
class EventController {
  private final EventService events;

  EventController(EventService events) {
    this.events = events;
  }

  @GetMapping
  List<EventResponse> list() {
    return events.list();
  }

  @GetMapping("/{id}")
  EventResponse get(@PathVariable Long id) {
    return events.get(id);
  }
}
