package com.afterbooth.stats.controller;

import com.afterbooth.stats.dto.ReuseStats;
import com.afterbooth.stats.service.ReuseStatsService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/stats/reuse")
class ReuseStatsController {
  private final ReuseStatsService service;

  ReuseStatsController(ReuseStatsService service) {
    this.service = service;
  }

  @GetMapping
  ReuseStats get(@RequestParam(required = false) Long eventId) {
    return service.get(eventId);
  }
}
