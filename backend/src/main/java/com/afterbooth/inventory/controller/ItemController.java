package com.afterbooth.inventory.controller;

import com.afterbooth.inventory.dto.ItemResponse;
import com.afterbooth.inventory.service.ItemService;
import java.time.Clock;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/items")
class ItemController {
  private final ItemService items;
  private final Clock clock;

  ItemController(ItemService items, Clock clock) {
    this.items = items;
    this.clock = clock;
  }

  @GetMapping
  List<ItemResponse> list(
      @RequestParam(required = false) Long eventId,
      @RequestParam(defaultValue = "false") boolean availableOnly) {
    return items.list(eventId, availableOnly, clock.instant());
  }

  @GetMapping("/{itemId}")
  ItemResponse get(@PathVariable Long itemId) {
    return items.get(itemId);
  }
}
