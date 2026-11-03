package com.afterbooth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.security.AuthRateLimiter;
import com.afterbooth.global.security.AuthSecurityProperties;
import com.afterbooth.global.security.HmacKeyRing;
import com.afterbooth.global.security.RateLimitedException;
import com.afterbooth.member.dto.LoginMemberRequest;
import com.afterbooth.member.service.SessionService;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
class LoginRateLimitIntegrationTest {
  @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17-alpine");
  private static final String OLD = key();
  private static final String CURRENT = key();
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager manager;
  @Autowired SessionService sessions;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DB::getJdbcUrl);
    registry.add("spring.datasource.username", DB::getUsername);
    registry.add("spring.datasource.password", DB::getPassword);
    registry.add("afterbooth.auth.hmac.current-version", () -> "old");
    registry.add("afterbooth.auth.hmac.keys.old", () -> OLD);
    SyntheticAuthSupport.configure(registry);
  }

  @BeforeEach
  void clear() {
    jdbc.update("delete from auth_rate_limit");
  }

  @Test
  void failureWindowRollsAndBlockLastsFifteenMinutesAfterFifthFailure() {
    String email = email();
    var limiter = limiter(false);
    for (int i = 0; i < 4; i++) {
      limiter.checkLogin(email);
      limiter.loginFailed(email);
    }
    jdbc.update(
        "update auth_rate_limit set window_start=window_start-interval '16 minutes',expires_at=expires_at-interval '16 minutes'");
    for (int i = 0; i < 4; i++) {
      limiter.checkLogin(email);
      limiter.loginFailed(email);
    }
    limiter.checkLogin(email);
    limiter.loginFailed(email);
    assertThatThrownBy(() -> limiter.checkLogin(email))
        .isInstanceOfSatisfying(
            RateLimitedException.class, e -> assertThat(e.retryAfter()).isBetween(899L, 900L));
    jdbc.update(
        "update auth_rate_limit set window_start=window_start-interval '16 minutes',expires_at=expires_at-interval '16 minutes'");
    limiter.checkLogin(email);
  }

  @Test
  void previousHmacAndIndependentInstancesShareFailureAndRestrictionEvidence() {
    String email = email();
    var old = limiter(false);
    var rotated = limiter(true);
    for (int i = 0; i < 4; i++) old.loginFailed(email);
    rotated.loginFailed(email);
    assertThatThrownBy(() -> old.checkLogin(email)).isInstanceOf(RateLimitedException.class);
    assertThatThrownBy(() -> rotated.checkLogin(email)).isInstanceOf(RateLimitedException.class);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from auth_rate_limit where scope='login:block'", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void failureCountersSurviveCallerRollback() {
    String email = email();
    var request = new LoginMemberRequest(email, key());
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              try {
                sessions.login(request, null);
              } catch (BusinessException e) {
                assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
              }
              status.setRollbackOnly();
            });
    assertThat(
            jdbc.queryForObject(
                "select sum(count) from auth_rate_limit where scope='login:failure'", Long.class))
        .isEqualTo(1);
  }

  @Test
  void concurrentMissingAccountAttemptsStopAfterFiveAndCreateNoSessions() throws Exception {
    String email = email();
    String password = key();
    long initial = jdbc.queryForObject("select count(*) from refresh_session", Long.class);
    try (var executor = Executors.newFixedThreadPool(7)) {
      var start = new CountDownLatch(1);
      var results = new ArrayList<Future<ErrorCode>>();
      for (int i = 0; i < 7; i++)
        results.add(
            executor.submit(
                () -> {
                  start.await();
                  try {
                    sessions.login(new LoginMemberRequest(email, password), null);
                    return null;
                  } catch (BusinessException e) {
                    return e.errorCode();
                  }
                }));
      start.countDown();
      int rejected = 0, limited = 0;
      for (var result : results) {
        var code = result.get();
        if (code == ErrorCode.UNAUTHENTICATED) rejected++;
        if (code == ErrorCode.RATE_LIMITED) limited++;
      }
      assertThat(rejected).isEqualTo(5);
      assertThat(limited).isEqualTo(2);
    }
    assertThat(jdbc.queryForObject("select count(*) from refresh_session", Long.class))
        .isEqualTo(initial);
    assertThat(
            jdbc.queryForObject(
                "select sum(count) from auth_rate_limit where scope='login:failure'", Long.class))
        .isEqualTo(5);
  }

  private AuthRateLimiter limiter(boolean rotated) {
    var hmac =
        new AuthSecurityProperties.Hmac(
            rotated ? "current" : "old",
            rotated ? Map.of("old", OLD, "current", CURRENT) : Map.of("old", OLD),
            rotated ? Map.of("old", Instant.now().plusSeconds(3600)) : Map.of());
    return new AuthRateLimiter(
        jdbc,
        new HmacKeyRing(new AuthSecurityProperties("https://localhost", hmac), Clock.systemUTC()),
        manager);
  }

  private static String email() {
    return UUID.randomUUID() + "@example.invalid";
  }

  private static String key() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
