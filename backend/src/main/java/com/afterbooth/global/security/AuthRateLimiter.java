package com.afterbooth.global.security;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthRateLimiter {
  private final JdbcTemplate jdbc;
  private final HmacKeyRing keys;
  private final TransactionTemplate independent;
  private final TransactionTemplate participating;

  public AuthRateLimiter(JdbcTemplate jdbc, HmacKeyRing keys, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.keys = keys;
    independent = new TransactionTemplate(manager);
    independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    participating = new TransactionTemplate(manager);
    participating.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
  }

  public boolean allowRegistration(String email) {
    return consume("signup:email", email, 5, false).allowed();
  }

  public boolean allowVerificationIssuance(String email) {
    return consume("signup:issue:email", email, 5, true).allowed();
  }

  public void checkIp(String scope, String ip) {
    Decision decision = consume(scope + ":ip", ip, 20, false);
    if (!decision.allowed()) throw new RateLimitedException(decision.retryAfter());
  }

  public void checkLogin(String email) {
    loginWindow(email, false, independent);
  }

  public void loginFailed(String email) {
    loginWindow(email, true, independent);
  }

  public void checkLoginWithinTransaction(String email) {
    loginWindow(email, false, participating);
  }

  public void loginFailedWithinTransaction(String email) {
    loginWindow(email, true, participating);
  }

  private void loginWindow(String email, boolean failure, TransactionTemplate transaction) {
    try {
      Long retry =
          transaction.execute(
              status -> {
                Instant before =
                    jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
                List<Key> candidates =
                    keys.versions(before).stream()
                        .map(
                            version ->
                                new Key(version, keys.digest(version, "login:failure", email)))
                        .toList();
                candidates.stream()
                    .map(key -> ByteBuffer.wrap(key.digest()).getLong())
                    .sorted()
                    .distinct()
                    .forEach(lock -> jdbc.queryForList("select pg_advisory_xact_lock(?)", lock));
                Instant now =
                    jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
                List<Key> aliases =
                    candidates.stream()
                        .filter(key -> keys.versions(now).contains(key.version()))
                        .toList();
                long wait = 0;
                long count = 0;
                for (Key key : aliases) {
                  Timestamp blocked =
                      jdbc.queryForObject(
                          "select max(expires_at) from auth_rate_limit where scope='login:block' and subject_hmac=? and key_version=? and expires_at>?",
                          Timestamp.class,
                          key.digest(),
                          key.version(),
                          Timestamp.from(now));
                  if (blocked != null)
                    wait =
                        Math.max(
                            wait,
                            (long)
                                Math.ceil(
                                    (blocked.toInstant().toEpochMilli() - now.toEpochMilli())
                                        / 1000.0));
                  count =
                      Math.max(
                          count,
                          jdbc.queryForObject(
                              "select coalesce(sum(count),0) from auth_rate_limit where scope='login:failure' and subject_hmac=? and key_version=? and window_start>?",
                              Long.class,
                              key.digest(),
                              key.version(),
                              Timestamp.from(now.minusSeconds(900))));
                }
                if (wait > 0 || !failure) return wait;
                for (Key key : aliases) {
                  jdbc.update(
                      "insert into auth_rate_limit(scope,subject_hmac,key_version,window_start,count,expires_at) values('login:failure',?,?,?,1,?) on conflict(scope,subject_hmac,key_version,window_start) do update set count=auth_rate_limit.count+1",
                      key.digest(),
                      key.version(),
                      Timestamp.from(now),
                      Timestamp.from(now.plusSeconds(900)));
                  if (count + 1 >= 5)
                    jdbc.update(
                        "insert into auth_rate_limit(scope,subject_hmac,key_version,window_start,count,expires_at) values('login:block',?,?,?,1,?) on conflict do nothing",
                        key.digest(),
                        key.version(),
                        Timestamp.from(now),
                        Timestamp.from(now.plusSeconds(900)));
                }
                return 0L;
              });
      if (retry != null && retry > 0) throw new RateLimitedException(retry);
    } catch (DataAccessException | TransactionException e) {
      throw new BusinessException(ErrorCode.AUTH_UNAVAILABLE);
    }
  }

  private Decision consume(String scope, String subject, int maximum, boolean interval) {
    try {
      Decision decision =
          independent.execute(
              status -> {
                Instant beforeLock =
                    jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
                List<Key> candidateVersions =
                    keys.versions(beforeLock).stream()
                        .map(version -> new Key(version, keys.digest(version, scope, subject)))
                        .toList();
                // Lock every live key in a stable order, so old/new instances share an exclusion
                // point.
                candidateVersions.stream()
                    .map(key -> ByteBuffer.wrap(key.digest()).getLong())
                    .sorted()
                    .distinct()
                    .forEach(lock -> jdbc.queryForList("select pg_advisory_xact_lock(?)", lock));
                Instant now =
                    jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
                List<Key> versions =
                    candidateVersions.stream()
                        .filter(key -> keys.versions(now).contains(key.version()))
                        .toList();
                Instant start =
                    Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), 3600) * 3600);
                Instant end = start.plusSeconds(3600);
                // Write every live alias so an older instance can observe newer-instance requests.
                for (Key key : versions) {
                  jdbc.update(
                      """
                      insert into auth_rate_limit(scope,subject_hmac,key_version,window_start,count,expires_at)
                      values (?,?,?,?,1,?) on conflict(scope,subject_hmac,key_version,window_start)
                      do update set count=least(auth_rate_limit.count::bigint+1,2147483647)::int
                      """,
                      scope,
                      key.digest(),
                      key.version(),
                      Timestamp.from(start),
                      Timestamp.from(end));
                }
                long count = 0;
                boolean recent = false;
                for (Key key : versions) {
                  count =
                      Math.max(
                          count,
                          jdbc.queryForObject(
                              """
              select coalesce(sum(count),0) from auth_rate_limit
              where scope=? and subject_hmac=? and key_version=? and window_start=?
              """,
                              Long.class,
                              scope,
                              key.digest(),
                              key.version(),
                              Timestamp.from(start)));
                  if (interval) {
                    recent |=
                        Boolean.TRUE.equals(
                            jdbc.queryForObject(
                                """
                select exists(select 1 from auth_rate_limit where scope=? and subject_hmac=?
                and key_version=? and expires_at>?)
                """,
                                Boolean.class,
                                scope + ":interval",
                                key.digest(),
                                key.version(),
                                Timestamp.from(now)));
                  }
                }
                boolean result = count <= maximum && !recent;
                if (interval && result)
                  for (Key key : versions)
                    jdbc.update(
                        """
            insert into auth_rate_limit(scope,subject_hmac,key_version,window_start,count,expires_at)
            values (?,?,?,?,1,?) on conflict do nothing
            """,
                        scope + ":interval",
                        key.digest(),
                        key.version(),
                        Timestamp.from(now),
                        Timestamp.from(now.plusSeconds(60)));
                return new Decision(result, end.getEpochSecond() - now.getEpochSecond());
              });
      return java.util.Objects.requireNonNull(decision);
    } catch (DataAccessException | TransactionException e) {
      throw new BusinessException(ErrorCode.AUTH_UNAVAILABLE);
    }
  }

  public int cleanupExpired() {
    try {
      return jdbc.update("delete from auth_rate_limit where expires_at <= clock_timestamp()");
    } catch (DataAccessException e) {
      throw new BusinessException(ErrorCode.AUTH_UNAVAILABLE);
    }
  }

  private record Decision(boolean allowed, long retryAfter) {}

  private record Key(String version, byte[] digest) {}
}
