package com.afterbooth.stats.service;

import com.afterbooth.inventory.domain.ItemCategory;
import com.afterbooth.stats.dto.ReuseStats;
import com.afterbooth.stats.repository.ReuseStatsRepository;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReuseStatsService {
  private static final long CUBIC_CM_PER_LITER = 1_000;

  private final ReuseStatsRepository repository;

  public ReuseStatsService(ReuseStatsRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public ReuseStats get(Long eventId) {
    var rows = repository.quantities(eventId);
    var categories = new EnumMap<ItemCategory, Long>(ItemCategory.class);
    var events = new LinkedHashMap<Long, ReuseStats.EventQuantity>();
    long total = 0;
    long cubicCm = 0;
    for (var row : rows) {
      total += row.quantity();
      cubicCm += row.cubicCm();
      categories.merge(row.category(), row.quantity(), Long::sum);
      events.compute(
          row.eventId(),
          (id, previous) ->
              new ReuseStats.EventQuantity(
                  id,
                  row.eventName(),
                  row.quantity() + (previous == null ? 0 : previous.quantity())));
    }
    return new ReuseStats(
        total,
        cubicCm / CUBIC_CM_PER_LITER,
        categories.entrySet().stream()
            .map(
                entry ->
                    new ReuseStats.CategoryQuantity(
                        entry.getKey(), entry.getKey().label(), entry.getValue()))
            .toList(),
        events.values().stream()
            .sorted(java.util.Comparator.comparing(ReuseStats.EventQuantity::eventId))
            .toList());
  }
}
