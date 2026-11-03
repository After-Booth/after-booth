package com.afterbooth.member.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepository {
  private final JdbcTemplate jdbc;

  public SessionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public TokenFamily find(byte[] hash) {
    return jdbc
        .query(
            "SELECT s.*,t.used_at FROM refresh_token t JOIN refresh_session s ON s.id=t.session_id WHERE t.token_hash=?",
            (rs, row) ->
                new TokenFamily(
                    rs.getObject("id", UUID.class),
                    rs.getLong("member_id"),
                    rs.getLong("auth_version"),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getTimestamp("revoked_at") != null,
                    rs.getTimestamp("used_at") != null),
            hash)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public TokenFamily lock(byte[] hash) {
    var family = find(hash);
    if (family == null) return null;
    jdbc.queryForList(
        "SELECT id FROM refresh_session WHERE id=? FOR UPDATE", UUID.class, family.id());
    // Read the token again after waiting: locking only the family in a joined query can
    // otherwise retain a pre-lock snapshot of the concurrently consumed token row.
    return find(hash);
  }

  public void create(UUID id, long memberId, long version, Instant now) {
    jdbc.update(
        "INSERT INTO refresh_session(id,member_id,auth_version,created_at,expires_at) VALUES(?,?,?,?,?)",
        id,
        memberId,
        version,
        Timestamp.from(now),
        Timestamp.from(now.plusSeconds(604800)));
  }

  public void token(byte[] hash, UUID id, Instant now) {
    jdbc.update(
        "INSERT INTO refresh_token(token_hash,session_id,created_at) VALUES(?,?,?)",
        hash,
        id,
        Timestamp.from(now));
  }

  public void used(byte[] hash, Instant now) {
    jdbc.update("UPDATE refresh_token SET used_at=? WHERE token_hash=?", Timestamp.from(now), hash);
  }

  public void revoke(UUID id, Instant now) {
    jdbc.update(
        "UPDATE refresh_session SET revoked_at=coalesce(revoked_at,?) WHERE id=?",
        Timestamp.from(now),
        id);
  }

  public void revokeAll(long memberId, Instant now) {
    jdbc.update(
        "UPDATE refresh_session SET revoked_at=coalesce(revoked_at,?) WHERE member_id=?",
        Timestamp.from(now),
        memberId);
    jdbc.update("UPDATE member SET auth_version=auth_version+1 WHERE id=?", memberId);
  }

  public int cleanup() {
    var ids =
        jdbc.queryForList(
            "SELECT DISTINCT member_id FROM refresh_session WHERE expires_at<=clock_timestamp() ORDER BY member_id",
            Long.class);
    int deleted = 0;
    for (long id : ids) {
      jdbc.queryForList("SELECT id FROM member WHERE id=? FOR UPDATE", Long.class, id);
      deleted +=
          jdbc.update(
              "DELETE FROM refresh_session WHERE member_id=? AND expires_at<=clock_timestamp()",
              id);
    }
    return deleted;
  }

  public record TokenFamily(
      UUID id, long memberId, long authVersion, Instant expiresAt, boolean revoked, boolean used) {}
}
