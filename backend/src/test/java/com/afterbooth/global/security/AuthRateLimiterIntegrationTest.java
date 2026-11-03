package com.afterbooth.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
class AuthRateLimiterIntegrationTest {
  @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17-alpine");
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager manager;
  @Autowired Clock clock;
  private String oldKey;
  private String newKey;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DB::getJdbcUrl);
    registry.add("spring.datasource.username", DB::getUsername);
    registry.add("spring.datasource.password", DB::getPassword);
  }

  @BeforeEach
  void prepare() {
    jdbc.update("delete from auth_rate_limit");
    oldKey = randomKey();
    newKey = randomKey();
  }

  @Test
  void twoIndependentInstancesHaveAtomicSharedCountersAndPersistRejectedMutations()
      throws Exception {
    var first = limiter("old", false);
    var second = limiter("old", false);
    try (var executor = Executors.newFixedThreadPool(8)) {
      var start = new CountDownLatch(1);
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 12; i++) {
        var instance = i % 2 == 0 ? first : second;
        results.add(
            executor.submit(
                () -> {
                  start.await();
                  return instance.allowRegistration("parallel@example.invalid");
                }));
      }
      start.countDown();
      int allowed = 0;
      for (var result : results) if (result.get()) allowed++;
      assertThat(allowed).isEqualTo(5);
    }
    assertThat(jdbc.queryForObject("select sum(count) from auth_rate_limit", Long.class))
        .isEqualTo(12L);
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              assertThat(first.allowRegistration("rollback@example.invalid")).isTrue();
              status.setRollbackOnly();
            });
    assertThat(jdbc.queryForObject("select sum(count) from auth_rate_limit", Long.class))
        .isEqualTo(13L);
  }

  @Test
  void rotationRetainsHourlyAndIssuanceIntervalLimits() {
    var old = limiter("old", false);
    for (int i = 0; i < 5; i++)
      assertThat(old.allowRegistration("rotation@example.invalid")).isTrue();
    var rotated = limiter("new", true);
    assertThat(rotated.allowRegistration("rotation@example.invalid")).isFalse();
    assertThat(old.allowVerificationIssuance("interval@example.invalid")).isTrue();
    assertThat(rotated.allowVerificationIssuance("interval@example.invalid")).isFalse();
    assertThat(rotated.allowRegistration("interval@example.invalid")).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from auth_rate_limit where key_version='new'", Integer.class))
        .isEqualTo(3);
  }

  @Test
  void alternatingCurrentVersionsCannotSplitTheQuota() {
    var old = limiter("old", false);
    var rotated = limiter("new", true);
    for (int i = 0; i < 5; i++) {
      assertThat((i % 2 == 0 ? old : rotated).allowRegistration("mixed@example.invalid")).isTrue();
    }
    assertThat(old.allowRegistration("mixed@example.invalid")).isFalse();
    assertThat(rotated.allowRegistration("mixed@example.invalid")).isFalse();
    assertThat(rotated.allowVerificationIssuance("mixed-interval@example.invalid")).isTrue();
    assertThat(old.allowVerificationIssuance("mixed-interval@example.invalid")).isFalse();
    assertThat(
            jdbc.queryForObject(
                "select count from auth_rate_limit where scope='signup:email' and key_version='old'",
                Integer.class))
        .isEqualTo(7);
  }

  @Test
  void issuanceRechecksDatabaseExpiryAfterRealAdvisoryLockWait() throws Exception {
    var limiter = limiter("old", false);
    assertThat(limiter.allowVerificationIssuance("wait@example.invalid")).isTrue();
    jdbc.update(
        "update auth_rate_limit set window_start=clock_timestamp()-interval '59 seconds', expires_at=clock_timestamp()+interval '1 second' where scope='signup:issue:email:interval'");
    var hmac =
        new HmacKeyRing(
            new AuthSecurityProperties(
                null, new AuthSecurityProperties.Hmac("old", Map.of("old", oldKey), Map.of())),
            clock);
    long lock =
        java.nio.ByteBuffer.wrap(hmac.digest("old", "signup:issue:email", "wait@example.invalid"))
            .getLong();
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<?> holder =
          executor.submit(
              () ->
                  new TransactionTemplate(manager)
                      .executeWithoutResult(
                          status -> {
                            jdbc.queryForList("select pg_advisory_xact_lock(?)", lock);
                            held.countDown();
                            try {
                              release.await();
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException("Lock test interrupted");
                            }
                          }));
      held.await();
      Future<Boolean> waiting =
          executor.submit(() -> limiter.allowVerificationIssuance("wait@example.invalid"));
      try {
        Thread.sleep(1250);
        assertThat(waiting.isDone()).isFalse();
      } finally {
        release.countDown();
      }
      holder.get();
      assertThat(waiting.get()).isTrue();
    }
  }

  @Test
  void ipRejectsAfterTwentyAndCleanupRemovesOnlyExpiredRows() {
    var limiter = limiter("old", false);
    for (int i = 0; i < 20; i++) limiter.checkIp("signup", "192.0.2.1");
    assertThatThrownBy(() -> limiter.checkIp("signup", "192.0.2.1"))
        .isInstanceOf(RateLimitedException.class);
    assertThat(jdbc.queryForObject("select sum(count) from auth_rate_limit", Long.class))
        .isEqualTo(21L);
    jdbc.update(
        "update auth_rate_limit set window_start=?,expires_at=?",
        Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")),
        Timestamp.from(Instant.parse("2000-01-01T01:00:00Z")));
    limiter.checkIp("signup", "192.0.2.2");
    assertThat(limiter.cleanupExpired()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from auth_rate_limit", Integer.class))
        .isEqualTo(1);
  }

  private AuthRateLimiter limiter(String current, boolean previous) {
    var hmac =
        new AuthSecurityProperties.Hmac(
            current,
            Map.of("old", oldKey, "new", newKey),
            previous ? Map.of("old", clock.instant().plusSeconds(3600)) : Map.of());
    return new AuthRateLimiter(
        jdbc, new HmacKeyRing(new AuthSecurityProperties(null, hmac), clock), manager);
  }

  private String randomKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
