package com.afterbooth.stats.service;

import com.afterbooth.global.security.DomainAccess;
import com.afterbooth.inventory.domain.ItemCategory;
import com.afterbooth.stats.dto.ReuseStats;
import com.afterbooth.stats.repository.ReuseStatsRepository;
import java.math.BigInteger;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReuseStatsService {
  private static final BigInteger CUBIC_CM_PER_LITER = BigInteger.valueOf(1_000);

  private final ReuseStatsRepository repository;
  private final DomainAccess access;

  public ReuseStatsService(ReuseStatsRepository repository, DomainAccess access) {
    this.repository = repository;
    this.access = access;
  }

  @Transactional(readOnly = true)
  public ReuseStats get(Long eventId) {
    access.actor();
    if (eventId != null) access.event(eventId);
    var rows = repository.quantities(eventId);
    var categories = new EnumMap<ItemCategory, BigInteger>(ItemCategory.class);
    var events = new LinkedHashMap<Long, ReuseStats.EventQuantity>();
    BigInteger total = BigInteger.ZERO;
    BigInteger cubicCm = BigInteger.ZERO;
    for (var row : rows) {
      total = total.add(row.quantity());
      cubicCm = cubicCm.add(row.cubicCm());
      categories.merge(row.category(), row.quantity(), BigInteger::add);
      if (access.owner(row.organizationId()))
        events.compute(
            row.eventId(),
            (id, previous) ->
                new ReuseStats.EventQuantity(
                    id,
                    row.eventName(),
                    row.quantity()
                        .add(BigInteger.valueOf(previous == null ? 0 : previous.quantity()))
                        .longValueExact()));
    }
    return new ReuseStats(
        total.longValueExact(),
        cubicCm.divide(CUBIC_CM_PER_LITER).longValueExact(),
        categories.entrySet().stream()
            .map(
                entry ->
                    new ReuseStats.CategoryQuantity(
                        entry.getKey(), entry.getKey().label(), entry.getValue().longValueExact()))
            .toList(),
        events.values().stream()
            .sorted(java.util.Comparator.comparing(ReuseStats.EventQuantity::eventId))
            .toList());
  }
}
