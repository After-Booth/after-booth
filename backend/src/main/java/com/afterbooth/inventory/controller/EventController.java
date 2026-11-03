package com.afterbooth.inventory.controller;

import com.afterbooth.global.idempotency.IdempotencyService;
import com.afterbooth.inventory.dto.EventResponse;
import com.afterbooth.inventory.service.EventService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
class EventController {
  private final EventService events;
  private final IdempotencyService idempotency;

  EventController(EventService events, IdempotencyService idempotency) {
    this.events = events;
    this.idempotency = idempotency;
  }

  @GetMapping
  List<EventResponse> list() {
    return events.list();
  }

  @GetMapping("/{id}")
  EventResponse get(@PathVariable Long id) {
    return events.get(id);
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> create(
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody byte[] body,
      HttpServletRequest http) {
    return idempotency.execute(
        key, http.getMethod(), http.getRequestURI(), body, 201, () -> events.create(body));
  }
}
