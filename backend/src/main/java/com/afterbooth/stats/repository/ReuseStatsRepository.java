package com.afterbooth.stats.repository;

import com.afterbooth.inventory.domain.ItemCategory;
import java.math.BigInteger;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReuseStatsRepository {
  private final JdbcTemplate jdbc;

  public ReuseStatsRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Quantity> quantities(Long eventId) {
    return jdbc.query(
        "SELECT q.category,e.id,e.name,e.organization_id,sum(q.quantity),sum(q.cubic_cm) FROM (SELECT i.category,i.event_id,r.handed_over_qty::numeric quantity,r.handed_over_qty::numeric*i.width_cm*i.depth_cm*i.height_cm cubic_cm FROM reservation r JOIN item i ON i.id=r.item_id WHERE r.status='HANDED_OVER' UNION ALL SELECT category,event_id,reused_qty::numeric,reused_volume_cm3 FROM reuse_archive) q JOIN event e ON e.id=q.event_id WHERE (?::bigint IS NULL OR e.id=?) GROUP BY q.category,e.id,e.name,e.organization_id ORDER BY q.category,e.id",
        (row, n) ->
            new Quantity(
                ItemCategory.valueOf(row.getString(1)),
                row.getLong(2),
                row.getString(3),
                row.getLong(4),
                row.getBigDecimal(5).toBigIntegerExact(),
                row.getBigDecimal(6).toBigIntegerExact()),
        eventId,
        eventId);
  }

  public record Quantity(
      ItemCategory category,
      Long eventId,
      String eventName,
      long organizationId,
      BigInteger quantity,
      BigInteger cubicCm) {}
}
