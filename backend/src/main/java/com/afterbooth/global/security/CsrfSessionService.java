package com.afterbooth.global.security;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.Base64;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CsrfSessionService {
  public static final String COOKIE = "__Host-afterbooth-csrf-session";
  private final JdbcTemplate jdbc;
  private final SecureRandom random = new SecureRandom();

  public CsrfSessionService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Issued issue() {
    return issue(null, null);
  }

  @org.springframework.transaction.annotation.Transactional
  public Issued reissue(String previousId) {
    try {
      Long memberId = null;
      if (previousId != null && previousId.matches("[A-Za-z0-9_-]{43}")) {
        var ids =
            jdbc.queryForList(
                "select member_id from csrf_session where id_hash=? and expires_at>clock_timestamp() and member_id is not null",
                Long.class,
                hash(previousId));
        if (!ids.isEmpty()) {
          long id = ids.getFirst();
          jdbc.queryForList("select id from member where id=? for update", Long.class, id);
          if (Boolean.TRUE.equals(
              jdbc.queryForObject(
                  "select exists(select 1 from csrf_session c join member m on m.id=c.member_id where c.id_hash=? and c.expires_at>clock_timestamp() and m.state='ACTIVE')",
                  Boolean.class,
                  hash(previousId)))) memberId = id;
        }
      }
      return issue(memberId, previousId);
    } catch (DataAccessException e) {
      throw new BusinessException(ErrorCode.AUTH_UNAVAILABLE);
    }
  }

  public Issued issue(Long memberId, String previousId) {
    String id = randomToken();
    String token = randomToken();
    try {
      var now = jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
      jdbc.update(
          """
          insert into csrf_session(id_hash,token_hash,member_id,created_at,expires_at)
          values(?,?,?,?,?)
          """,
          hash(id),
          hash(token),
          memberId,
          Timestamp.from(now),
          Timestamp.from(now.plusSeconds(3600)));
      if (previousId != null && previousId.matches("[A-Za-z0-9_-]{43}"))
        jdbc.update("delete from csrf_session where id_hash=?", hash(previousId));
      return new Issued(id, token);
    } catch (DataAccessException e) {
      throw new BusinessException(ErrorCode.AUTH_UNAVAILABLE);
    }
  }

  String storedTokenHash(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{43}")) return null;
    try {
      return jdbc
          .query(
              "select token_hash from csrf_session where id_hash=? and expires_at>clock_timestamp()",
              (result, row) ->
                  Base64.getUrlEncoder().withoutPadding().encodeToString(result.getBytes(1)),
              hash(id))
          .stream()
          .findFirst()
          .orElse(null);
    } catch (DataAccessException e) {
      throw new BusinessException(ErrorCode.AUTH_UNAVAILABLE);
    }
  }

  public void invalidateMember(long memberId) {
    jdbc.update("delete from csrf_session where member_id=?", memberId);
  }

  public int cleanupExpired() {
    try {
      return jdbc.update("delete from csrf_session where expires_at<=clock_timestamp()");
    } catch (DataAccessException e) {
      throw new BusinessException(ErrorCode.AUTH_UNAVAILABLE);
    }
  }

  static byte[] hash(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Required digest unavailable");
    }
  }

  String randomToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public record Issued(String sessionId, String csrfToken) {
    @Override
    public String toString() {
      return "Issued[redacted]";
    }
  }
}
