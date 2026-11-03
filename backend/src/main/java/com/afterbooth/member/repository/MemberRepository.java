package com.afterbooth.member.repository;

import com.afterbooth.member.domain.Member;
import com.afterbooth.member.domain.MemberState;
import com.afterbooth.member.domain.SignupChallenge;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MemberRepository {
  private final JdbcTemplate jdbc;

  public MemberRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Instant databaseNow() {
    return jdbc.queryForObject(
        "SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
  }

  public void lockEmail(String email) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(("member-email\0" + email).getBytes(StandardCharsets.UTF_8));
      jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", ByteBuffer.wrap(digest).getLong());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Required digest unavailable");
    }
  }

  public Member findForUpdate(String email) {
    List<Member> rows =
        jdbc.query(
            "SELECT * FROM member WHERE email_normalized = ? FOR UPDATE",
            MemberRepository::member,
            email);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Member findByIdForUpdate(long id) {
    return jdbc
        .query("SELECT * FROM member WHERE id=? FOR UPDATE", MemberRepository::member, id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public boolean blocked(String version, byte[] digest, Instant now) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM withdrawn_email_block WHERE key_version=? AND email_hmac=? AND blocked_until>?)",
            Boolean.class,
            version,
            digest,
            Timestamp.from(now)));
  }

  public void register(String email, String hash, Instant now) {
    Long id =
        jdbc.queryForObject(
            "INSERT INTO member(email_normalized,password_hash,state,created_at) VALUES (?,?,'PENDING_VERIFICATION',?) RETURNING id",
            Long.class,
            email,
            hash,
            Timestamp.from(now));
    jdbc.update(
        "INSERT INTO organization(name,primary_member_id) VALUES ('Personal organization',?)", id);
  }

  public void invalidateChallenges(String version, byte[] emailKey, Instant now) {
    invalidateChallenges(version, emailKey, "SIGNUP_VERIFY", now);
  }

  public void invalidateChallenges(String version, byte[] emailKey, String purpose, Instant now) {
    jdbc.update(
        "UPDATE auth_challenge SET consumed_at=? WHERE key_version=? AND email_key=? AND purpose=? AND consumed_at IS NULL",
        Timestamp.from(now),
        version,
        emailKey,
        purpose);
  }

  public void insertChallenge(
      String id, Long memberId, byte[] emailKey, String version, byte[] codeHmac, Instant now) {
    insertChallenge(id, memberId, emailKey, version, "SIGNUP_VERIFY", codeHmac, now);
  }

  public void insertChallenge(
      String id,
      Long memberId,
      byte[] emailKey,
      String version,
      String purpose,
      byte[] codeHmac,
      Instant now) {
    jdbc.update(
        "INSERT INTO auth_challenge(id,member_id,email_key,key_version,purpose,code_hmac,attempts,created_at,expires_at) VALUES (?,?,?,?,?,?,0,?,?)",
        id,
        memberId,
        emailKey,
        version,
        purpose,
        codeHmac,
        Timestamp.from(now),
        Timestamp.from(now.plusSeconds(600)));
  }

  public void replacePassword(long memberId, String hash, Instant now) {
    jdbc.update("UPDATE member SET password_hash=? WHERE id=?", hash, memberId);
    jdbc.update(
        "UPDATE auth_challenge SET consumed_at=? WHERE member_id=? AND purpose='PASSWORD_RESET' AND consumed_at IS NULL",
        Timestamp.from(now),
        memberId);
  }

  public SignupChallenge challengeForUpdate(String id) {
    List<SignupChallenge> rows =
        jdbc.query(
            "SELECT * FROM auth_challenge WHERE id=? FOR UPDATE",
            (rs, row) ->
                new SignupChallenge(
                    rs.getString("id"),
                    rs.getObject("member_id", Long.class),
                    rs.getBytes("email_key"),
                    rs.getString("key_version"),
                    rs.getString("purpose"),
                    rs.getBytes("code_hmac"),
                    rs.getInt("attempts"),
                    rs.getTimestamp("expires_at").toInstant(),
                    instant(rs, "consumed_at")),
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void attempt(String id) {
    jdbc.update("UPDATE auth_challenge SET attempts=LEAST(attempts+1,5) WHERE id=?", id);
  }

  public boolean activate(Long memberId, String passwordHash, String challengeId, Instant now) {
    int changed =
        jdbc.update(
            "UPDATE member SET password_hash=?,state='ACTIVE',verified_at=? WHERE id=? AND state='PENDING_VERIFICATION'",
            passwordHash,
            Timestamp.from(now),
            memberId);
    if (changed == 1)
      jdbc.update(
          "UPDATE auth_challenge SET consumed_at=? WHERE id=?", Timestamp.from(now), challengeId);
    return changed == 1;
  }

  public void cleanup() {
    Instant now = databaseNow();
    Timestamp timestamp = Timestamp.from(now);
    List<Long> pending =
        jdbc.queryForList(
            "SELECT id FROM member WHERE state='PENDING_VERIFICATION' AND created_at<=? ORDER BY id FOR UPDATE SKIP LOCKED",
            Long.class,
            Timestamp.from(now.minusSeconds(86400)));
    for (Long id : pending) {
      List<Long> organizations =
          jdbc.queryForList(
              "SELECT id FROM organization WHERE primary_member_id=? FOR UPDATE", Long.class, id);
      jdbc.update("DELETE FROM auth_challenge WHERE member_id=?", id);
      jdbc.update("DELETE FROM csrf_session WHERE member_id=?", id);
      jdbc.update(
          "DELETE FROM member WHERE id=? AND state='PENDING_VERIFICATION' AND NOT EXISTS (SELECT 1 FROM refresh_session WHERE member_id=?) AND NOT EXISTS (SELECT 1 FROM idempotency_record WHERE actor_member_id=?)",
          id,
          id,
          id);
      for (Long organization : organizations)
        jdbc.update(
            "DELETE FROM organization o WHERE o.id=? AND o.primary_member_id IS NULL AND NOT EXISTS (SELECT 1 FROM event e WHERE e.organization_id=o.id) AND NOT EXISTS (SELECT 1 FROM item i WHERE i.organization_id=o.id) AND NOT EXISTS (SELECT 1 FROM pickup_request p WHERE p.organization_id=o.id) AND NOT EXISTS (SELECT 1 FROM reservation r WHERE r.organization_id=o.id)",
            organization);
    }
    jdbc.update("DELETE FROM auth_challenge WHERE expires_at<=?", timestamp);
    jdbc.update("DELETE FROM csrf_session WHERE expires_at<=?", timestamp);
    jdbc.update("DELETE FROM auth_rate_limit WHERE expires_at<=?", timestamp);
    jdbc.update("DELETE FROM withdrawn_email_block WHERE blocked_until<=?", timestamp);
  }

  private static Member member(ResultSet rs, int row) throws SQLException {
    return new Member(
        rs.getLong("id"),
        rs.getString("email_normalized"),
        rs.getString("password_hash"),
        MemberState.valueOf(rs.getString("state")),
        rs.getLong("auth_version"),
        rs.getTimestamp("created_at").toInstant(),
        instant(rs, "verified_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
