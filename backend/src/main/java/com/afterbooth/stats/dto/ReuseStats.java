package com.afterbooth.stats.dto;

import com.afterbooth.inventory.domain.ItemCategory;
import java.util.List;

public record ReuseStats(
    long totalReused, List<CategoryQuantity> byCategory, List<EventQuantity> byEvent) {
  public record CategoryQuantity(ItemCategory category, String label, long quantity) {}

  public record EventQuantity(Long eventId, String eventName, long quantity) {}
}
