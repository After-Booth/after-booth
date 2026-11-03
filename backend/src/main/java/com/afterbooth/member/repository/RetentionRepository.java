package com.afterbooth.member.repository;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RetentionRepository {
  private final JdbcTemplate jdbc;

  public RetentionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public long overdueCount() {
    return jdbc.queryForObject(
        "SELECT (SELECT count(*) FROM reservation WHERE retention_until<=clock_timestamp()-interval '1 hour') + (SELECT count(*) FROM member WHERE state='WITHDRAWN' AND purge_after<=clock_timestamp()-interval '1 hour')",
        Long.class);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public CleanupResult cleanup() {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(6900690001)", Boolean.class)))
      return new CleanupResult(0, List.of());
    List<Long> candidates =
        jdbc.queryForList(
            "SELECT id FROM reservation WHERE retention_until<=clock_timestamp() AND status IN ('HANDED_OVER','RELEASED','EXPIRED') ORDER BY id LIMIT 100",
            Long.class);
    List<Long> purging =
        jdbc.queryForList(
            "SELECT id FROM member WHERE state='WITHDRAWN' AND purge_after<=clock_timestamp() ORDER BY id",
            Long.class);
    String reservationIds = ids(candidates);
    String memberIds = ids(purging);
    List<Long> organizations =
        jdbc.queryForList(
            "SELECT id FROM organization WHERE primary_member_id IN ("
                + memberIds
                + ") OR id IN (SELECT organization_id FROM reservation WHERE id IN ("
                + reservationIds
                + ")) OR id IN (SELECT i.organization_id FROM item i JOIN reservation r ON r.item_id=i.id WHERE r.id IN ("
                + reservationIds
                + ")) OR id IN (SELECT p.organization_id FROM pickup_request p JOIN reservation r ON r.request_id=p.id WHERE r.id IN ("
                + reservationIds
                + ")) ORDER BY id",
            Long.class);
    String organizationIds = ids(organizations);
    jdbc.queryForList(
        "SELECT id FROM member WHERE id IN ("
            + memberIds
            + ") OR id IN (SELECT primary_member_id FROM organization WHERE id IN ("
            + organizationIds
            + ")) ORDER BY id FOR UPDATE",
        Long.class);
    jdbc.queryForList(
        "SELECT id FROM organization WHERE id IN (" + organizationIds + ") ORDER BY id FOR UPDATE",
        Long.class);
    jdbc.queryForList(
        "SELECT id FROM pickup_request WHERE organization_id IN ("
            + organizationIds
            + ") OR id IN (SELECT request_id FROM reservation WHERE id IN ("
            + reservationIds
            + ")) ORDER BY id FOR UPDATE",
        Long.class);
    jdbc.queryForList(
        "SELECT id FROM item WHERE organization_id IN ("
            + organizationIds
            + ") OR id IN (SELECT item_id FROM reservation WHERE id IN ("
            + reservationIds
            + ")) ORDER BY id FOR UPDATE",
        Long.class);
    List<Long> expired =
        jdbc.queryForList(
            "SELECT id FROM reservation WHERE id IN ("
                + reservationIds
                + ") AND retention_until<=clock_timestamp() AND status IN ('HANDED_OVER','RELEASED','EXPIRED') ORDER BY id FOR UPDATE",
            Long.class);
    for (long id : expired) {
      jdbc.update(
          "INSERT INTO reuse_archive(event_id,category,reused_qty,reused_volume_cm3) SELECT i.event_id,i.category,r.handed_over_qty,r.handed_over_qty::numeric*i.width_cm*i.depth_cm*i.height_cm FROM reservation r JOIN item i ON i.id=r.item_id WHERE r.id=? AND r.status='HANDED_OVER' ON CONFLICT(event_id,category) DO UPDATE SET reused_qty=reuse_archive.reused_qty+excluded.reused_qty,reused_volume_cm3=reuse_archive.reused_volume_cm3+excluded.reused_volume_cm3",
          id);
      jdbc.update(
          "UPDATE item i SET archived_handed_over_qty=i.archived_handed_over_qty+r.handed_over_qty FROM reservation r WHERE r.id=? AND r.item_id=i.id AND r.status='HANDED_OVER'",
          id);
      jdbc.update("DELETE FROM handover_log WHERE reservation_id=?", id);
      jdbc.update("DELETE FROM reservation_transition WHERE reservation_id=?", id);
      jdbc.update("DELETE FROM reservation WHERE id=?", id);
    }
    var deletedMembers = new java.util.ArrayList<Long>();
    for (long member : purging) if (purge(member)) deletedMembers.add(member);
    return new CleanupResult(expired.size(), List.copyOf(deletedMembers));
  }

  private boolean purge(long member) {
    List<Long> owned =
        jdbc.queryForList(
            "SELECT o.id FROM organization o JOIN member m ON m.id=o.primary_member_id WHERE m.id=? AND m.state='WITHDRAWN' AND m.purge_after<=clock_timestamp() ORDER BY o.id",
            Long.class,
            member);
    for (long organization : owned) {
      if (Boolean.TRUE.equals(
          jdbc.queryForObject(
              "SELECT EXISTS(SELECT 1 FROM reservation r JOIN item i ON i.id=r.item_id JOIN pickup_request p ON p.id=r.request_id WHERE i.organization_id=? OR p.organization_id=? OR r.organization_id=?)",
              Boolean.class,
              organization,
              organization,
              organization))) continue;
      jdbc.update(
          "DELETE FROM pickup_request p WHERE p.organization_id=? AND NOT EXISTS(SELECT 1 FROM reservation r WHERE r.request_id=p.id)",
          organization);
      jdbc.update(
          "DELETE FROM item i WHERE i.organization_id=? AND NOT EXISTS(SELECT 1 FROM reservation r WHERE r.item_id=i.id)",
          organization);
      jdbc.update(
          "DELETE FROM intake_draft_line l USING intake_draft d,event e WHERE l.draft_id=d.id AND d.event_id=e.id AND e.organization_id=? AND NOT EXISTS(SELECT 1 FROM item i WHERE i.intake_draft_id=d.id)",
          organization);
      jdbc.update(
          "DELETE FROM intake_draft d USING event e WHERE d.event_id=e.id AND e.organization_id=? AND NOT EXISTS(SELECT 1 FROM item i WHERE i.intake_draft_id=d.id)",
          organization);
      jdbc.update(
          "DELETE FROM event e WHERE e.organization_id=? AND NOT EXISTS(SELECT 1 FROM item i WHERE i.event_id=e.id) AND NOT EXISTS(SELECT 1 FROM intake_draft d WHERE d.event_id=e.id) AND NOT EXISTS(SELECT 1 FROM reuse_archive a WHERE a.event_id=e.id)",
          organization);
      jdbc.update(
          "DELETE FROM idempotency_record WHERE actor_member_id=? OR ?=ANY(related_organization_ids)",
          member,
          organization);
      jdbc.update("UPDATE organization SET primary_member_id=NULL WHERE id=?", organization);
      jdbc.update(
          "DELETE FROM organization o WHERE o.id=? AND NOT EXISTS(SELECT 1 FROM event e WHERE e.organization_id=o.id) AND NOT EXISTS(SELECT 1 FROM item i WHERE i.organization_id=o.id) AND NOT EXISTS(SELECT 1 FROM pickup_request p WHERE p.organization_id=o.id) AND NOT EXISTS(SELECT 1 FROM reservation r WHERE r.organization_id=o.id)",
          organization);
    }
    jdbc.update("DELETE FROM auth_challenge WHERE member_id=?", member);
    jdbc.update("DELETE FROM csrf_session WHERE member_id=?", member);
    jdbc.update("DELETE FROM refresh_session WHERE member_id=?", member);
    jdbc.update("DELETE FROM idempotency_record WHERE actor_member_id=?", member);
    return jdbc.update(
            "DELETE FROM member m WHERE m.id=? AND m.state='WITHDRAWN' AND m.purge_after<=clock_timestamp() AND NOT EXISTS(SELECT 1 FROM organization o WHERE o.primary_member_id=m.id)",
            member)
        == 1;
  }

  public record CleanupResult(int transactions, List<Long> deletedMembers) {}

  private static String ids(List<Long> ids) {
    return ids.isEmpty()
        ? "NULL"
        : ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
  }
}
