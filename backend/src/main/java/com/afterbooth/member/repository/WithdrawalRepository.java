package com.afterbooth.member.repository;

import com.afterbooth.intake.domain.DraftCategory;
import com.afterbooth.inventory.domain.ItemCategory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WithdrawalRepository {
  private final JdbcTemplate jdbc;

  public WithdrawalRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public String email(long id) {
    return jdbc
        .queryForList(
            "select email_normalized from member where id=? and email_normalized is not null",
            String.class,
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public List<Long> relatedMembers(long id) {
    return jdbc.queryForList(
        """
        select distinct o.primary_member_id from organization o where o.primary_member_id is not null
        and (o.primary_member_id=? or o.id in (
          select r.organization_id from reservation r join item i on i.id=r.item_id
          join organization g on g.id=i.organization_id join organization v on v.id=r.organization_id
          where g.primary_member_id=? or v.primary_member_id=?
          union select i.organization_id from reservation r join item i on i.id=r.item_id
          join organization g on g.id=i.organization_id join organization v on v.id=r.organization_id
          where g.primary_member_id=? or v.primary_member_id=?)) order by o.primary_member_id
        """,
        Long.class,
        id,
        id,
        id,
        id,
        id);
  }

  public boolean blocked(long id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        select exists(select 1 from reservation r join item i on i.id=r.item_id
        join organization g on g.id=i.organization_id join organization v on v.id=r.organization_id
        where (g.primary_member_id=? or v.primary_member_id=?) and r.status in ('HELD','CONFIRMED'))
        """,
            Boolean.class,
            id,
            id));
  }

  public void erase(long id, String version, byte[] digest, Instant now) {
    Timestamp deadline = Timestamp.from(now.plusSeconds(30L * 24 * 60 * 60));
    List<Long> organizations =
        jdbc.queryForList(
            "select id from organization where primary_member_id=? order by id for update",
            Long.class,
            id);
    jdbc.update("delete from idempotency_record where actor_member_id=?", id);
    for (long org : organizations) {
      jdbc.update(
          "update reservation set retention_until=least(retention_until,?) where organization_id=? or item_id in (select id from item where organization_id=?)",
          deadline,
          org,
          org);
      jdbc.update(
          "update handover_log set boxes='[]'::jsonb where reservation_id in (select r.id from reservation r join item i on i.id=r.item_id where r.organization_id=? or i.organization_id=?)",
          org,
          org);
      jdbc.update(
          "delete from idempotency_record where actor_member_id=? or ?=any(related_organization_ids)",
          id,
          org);
      jdbc.update(
          "update pickup_request set purpose=null,transcript=null where organization_id=?", org);
      jdbc.update("update item set conditions_transcript=null where organization_id=?", org);
      for (ItemCategory category : ItemCategory.values())
        jdbc.update(
            "update item set label=? where organization_id=? and category=?",
            category.label(),
            org,
            category.name());
      jdbc.update(
          "update intake_draft set failure_message=null where event_id in (select id from event where organization_id=?)",
          org);
      jdbc.update(
          "update intake_draft_line set boxes='[]'::jsonb where draft_id in (select d.id from intake_draft d join event e on e.id=d.event_id where e.organization_id=?)",
          org);
      for (DraftCategory category : DraftCategory.values())
        jdbc.update(
            "update intake_draft_line set label=? where category=? and draft_id in (select d.id from intake_draft d join event e on e.id=d.event_id where e.organization_id=?)",
            category.label(),
            category.name(),
            org);
      jdbc.update(
          "update event set name='Withdrawn event',venue='Removed' where organization_id=?", org);
      jdbc.update("update organization set name='Withdrawn organization' where id=?", org);
    }
    jdbc.update("delete from auth_challenge where member_id=?", id);
    jdbc.update(
        "insert into withdrawn_email_block(email_hmac,key_version,blocked_until) values (?,?,?) on conflict(key_version,email_hmac) do update set blocked_until=excluded.blocked_until",
        digest,
        version,
        deadline);
    jdbc.update(
        "update member set state='WITHDRAWN',email_normalized=null,password_hash=null,withdrawn_at=?,purge_after=? where id=?",
        Timestamp.from(now),
        deadline,
        id);
  }
}
