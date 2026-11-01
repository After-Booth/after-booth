package com.afterbooth.allocation.controller;

import com.afterbooth.allocation.service.AllocationService;
import com.afterbooth.global.idempotency.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pickup-requests/{requestId}/allocation")
class AllocationController {
  private final AllocationService allocations;
  private final IdempotencyService idempotency;

  AllocationController(AllocationService allocations, IdempotencyService idempotency) {
    this.allocations = allocations;
    this.idempotency = idempotency;
  }

  @PostMapping
  ResponseEntity<String> run(
      @PathVariable Long requestId,
      @RequestHeader("Idempotency-Key") UUID key,
      @RequestBody(required = false) byte[] body,
      HttpServletRequest http) {
    return idempotency.execute(
        key,
        http.getMethod(),
        http.getRequestURI(),
        body == null ? new byte[0] : body,
        200,
        () -> allocations.run(requestId));
  }
}
