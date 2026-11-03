package com.afterbooth;

import static org.assertj.core.api.Assertions.*;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.idempotency.IdempotencyService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    properties = {"spring.docker.compose.enabled=false", "afterbooth.reservation-sweep-ms=3600000"})
@Import(TestcontainersConfiguration.class)
class IdempotencyRecoveryIntegrationTest {
  @Autowired IdempotencyService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired javax.sql.DataSource source;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;
  @Autowired java.time.Clock clock;
  @Autowired tools.jackson.databind.json.JsonMapper json;
  @Autowired com.afterbooth.global.security.DomainAccess access;

  @org.junit.jupiter.api.BeforeEach
  void authenticate() {
    DomainAuthTestSupport.seedOwner(jdbc, clock, 2);
  }

  static final byte[] BODY = new byte[0];
  static final String PATH = "/api/events";

  void orphan(UUID key) {
    jdbc.update(
        "insert into idempotency_record(idempotency_key,fingerprint,state,created_at,actor_member_id,related_organization_ids) values (?,?,'IN_PROGRESS',current_timestamp,?,array[2]::bigint[])",
        key,
        IdempotencyService.fingerprint("POST", PATH, BODY),
        access.actor().memberId());
  }

  Object run(UUID key, Supplier<?> action) {
    return service.execute(key, "POST", PATH, BODY, 200, action);
  }

  void killOwner(UUID key) {
    long value = key.getMostSignificantBits() ^ key.getLeastSignificantBits();
    assertThat(
            jdbc.queryForObject(
                "select pg_terminate_backend(pid) from pg_locks where locktype='advisory' and classid::bigint=? and objid::bigint=? and objsubid=1 and granted",
                Boolean.class,
                value >>> 32,
                value & 0xffffffffL))
        .isTrue();
  }

  static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
  }

  void conflict(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
  }

  @Test
  void independentInstancesCoordinateThroughPostgres() throws Exception {
    var otherLocks =
        new com.afterbooth.global.idempotency.IdempotencyLockPool(
            source, 2, 0, java.time.Duration.ofSeconds(1));
    try {
      var other = new IdempotencyService(jdbc, manager, clock, json, otherLocks, access);
      UUID key = UUID.randomUUID();
      orphan(key);
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      try (var workers =
          com.afterbooth.DomainAuthTestSupport.executor(Executors.newSingleThreadExecutor())) {
        var first =
            workers.submit(
                () ->
                    service.executePrepared(
                        key,
                        "POST",
                        PATH,
                        BODY,
                        200,
                        () -> {
                          entered.countDown();
                          await(release);
                          return () -> Map.of("first", true);
                        }));
        try {
          await(entered);
          conflict(
              () ->
                  other.execute(
                      key,
                      "POST",
                      PATH,
                      BODY,
                      200,
                      () -> {
                        throw new AssertionError();
                      }));
        } finally {
          release.countDown();
        }
        first.get(10, TimeUnit.SECONDS);
        assertThat(
                other
                    .execute(
                        key,
                        "POST",
                        PATH,
                        BODY,
                        200,
                        () -> {
                          throw new AssertionError();
                        })
                    .getBody())
            .contains("first");
      }
    } finally {
      otherLocks.close();
    }
  }

  @Test
  void orphanPreparedRunsOnceAndReplays() {
    UUID key = UUID.randomUUID();
    orphan(key);
    AtomicInteger preparations = new AtomicInteger();
    AtomicInteger actions = new AtomicInteger();
    Supplier<Supplier<?>> prepare =
        () -> {
          preparations.incrementAndGet();
          return () -> Map.of("runs", actions.incrementAndGet());
        };
    service.executePrepared(key, "POST", PATH, BODY, 200, prepare);
    service.executePrepared(key, "POST", PATH, BODY, 200, prepare);
    assertThat(preparations).hasValue(1);
    assertThat(actions).hasValue(1);
  }

  @Test
  void orphanDifferentFingerprintConflictsWithoutPreparation() {
    UUID key = UUID.randomUUID();
    orphan(key);
    conflict(
        () ->
            service.executePrepared(
                key,
                "POST",
                PATH,
                new byte[] {1},
                200,
                () -> {
                  throw new AssertionError();
                }));
  }

  @Test
  void recoveredBusinessErrorIsStoredAndReplayed() {
    UUID key = UUID.randomUUID();
    orphan(key);
    var first =
        service.execute(
            key,
            "POST",
            PATH,
            BODY,
            200,
            () -> {
              throw new BusinessException(ErrorCode.INVALID_INPUT);
            });
    var replay =
        service.execute(
            key,
            "POST",
            PATH,
            BODY,
            200,
            () -> {
              throw new AssertionError();
            });
    assertThat(first.getStatusCode().value()).isEqualTo(400);
    assertThat(replay.getStatusCode()).isEqualTo(first.getStatusCode());
    assertThat(
            jdbc.queryForObject(
                "select state from idempotency_record where idempotency_key=?", String.class, key))
        .isEqualTo("DONE");
  }

  @Test
  void simultaneousRecoveryAllowsOnlyOnePreparation() throws Exception {
    UUID key = UUID.randomUUID();
    orphan(key);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    try (var workers =
        com.afterbooth.DomainAuthTestSupport.executor(Executors.newSingleThreadExecutor())) {
      var first =
          workers.submit(
              () ->
                  service.executePrepared(
                      key,
                      "POST",
                      PATH,
                      BODY,
                      200,
                      () -> {
                        entered.countDown();
                        await(release);
                        return () -> Map.of("ok", true);
                      }));
      try {
        await(entered);
        conflict(
            () ->
                run(
                    key,
                    () -> {
                      throw new AssertionError();
                    }));
      } finally {
        release.countDown();
      }
      assertThat(first.get(10, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
    }
  }

  @Test
  void deadPreparationCannotOverwriteRecoveredSuccessOrCacheErrorOrDeleteClaim() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      int failure = mode;
      UUID key = UUID.randomUUID();
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      AtomicInteger actions = new AtomicInteger();
      try (var workers =
          com.afterbooth.DomainAuthTestSupport.executor(Executors.newSingleThreadExecutor())) {
        var first =
            workers.submit(
                () ->
                    service.executePrepared(
                        key,
                        "POST",
                        PATH,
                        BODY,
                        200,
                        () -> {
                          entered.countDown();
                          await(release);
                          if (failure == 1) throw new BusinessException(ErrorCode.INVALID_INPUT);
                          if (failure == 2)
                            throw new IllegalStateException("Synthetic preparation failure");
                          return () -> Map.of("stale", actions.incrementAndGet());
                        }));
        try {
          await(entered);
          killOwner(key);
          run(key, () -> Map.of("recovered", true));
        } finally {
          release.countDown();
        }
        assertThatThrownBy(() -> first.get(10, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class);
        assertThat(actions).hasValue(0);
        var replay =
            service.execute(
                key,
                "POST",
                PATH,
                BODY,
                200,
                () -> {
                  throw new AssertionError();
                });
        assertThat(replay.getBody()).contains("recovered").doesNotContain("stale");
      }
    }
  }

  @Test
  void finalRowLockProtectsCommitWhenSessionDies() throws Exception {
    UUID key = UUID.randomUUID();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    try (var workers =
        com.afterbooth.DomainAuthTestSupport.executor(Executors.newSingleThreadExecutor())) {
      var first =
          workers.submit(
              () ->
                  run(
                      key,
                      () -> {
                        entered.countDown();
                        await(release);
                        return Map.of("committed", true);
                      }));
      try {
        await(entered);
        killOwner(key);
        conflict(
            () ->
                run(
                    key,
                    () -> {
                      throw new AssertionError();
                    }));
      } finally {
        release.countDown();
      }
      first.get(10, TimeUnit.SECONDS);
      var replay =
          service.execute(
              key,
              "POST",
              PATH,
              BODY,
              200,
              () -> {
                throw new AssertionError();
              });
      assertThat(replay.getBody()).contains("committed");
    }
  }
}
