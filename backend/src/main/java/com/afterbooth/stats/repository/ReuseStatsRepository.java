package com.afterbooth.stats.repository;

import com.afterbooth.inventory.domain.ItemCategory;
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
        "select i.category,e.id,e.name,e.organization_id,sum(r.handed_over_qty),sum(r.handed_over_qty::bigint*i.width_cm*i.depth_cm*i.height_cm)::bigint from reservation r join item i on i.id=r.item_id join event e on e.id=i.event_id where r.status='HANDED_OVER' and (?::bigint is null or e.id=?) group by i.category,e.id,e.name,e.organization_id order by i.category,e.id",
        (row, n) ->
            new Quantity(
                ItemCategory.valueOf(row.getString(1)),
                row.getLong(2),
                row.getString(3),
                row.getLong(4),
                row.getLong(5),
                row.getLong(6)),
        eventId,
        eventId);
  }

  public record Quantity(
      ItemCategory category,
      Long eventId,
      String eventName,
      long organizationId,
      long quantity,
      long cubicCm) {}
}
