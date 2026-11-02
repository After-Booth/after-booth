package com.afterbooth.global.idempotency;

import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {"spring.docker.compose.enabled=false", "afterbooth.reservation-sweep-ms=3600000"})
@Import(IdempotencyLockPoolIntegrationTest.Database.class)
class IdempotencyLockPoolIntegrationTest {
  @Autowired DataSource source;
  @Autowired JdbcTemplate jdbc;

  @Test
  void invalidSettingsFailBeforeConnecting() {
    for (int[] values : new int[][] {{0, 0, 1000}, {1, -1, 1000}, {1, 2, 1000}, {1, 0, 249}}) {
      assertThatIllegalArgumentException()
          .isThrownBy(
              () ->
                  new IdempotencyLockPool(
                      source, values[0], values[1], Duration.ofMillis(values[2])))
          .withMessageContaining("afterbooth.idempotency.lock-pool");
    }
  }

  @Test
  void exhaustedSeparatePoolTimesOutAndReleasedSessionCanBeReused() {
    try (var holder = new PoolHolder()) {
      var first = holder.locks.acquire(UUID.randomUUID());
      try {
        assertThatThrownBy(() -> holder.locks.acquire(UUID.randomUUID()))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1);
      } finally {
        first.close();
      }
      try (var second = holder.locks.acquire(UUID.randomUUID())) {
        second.requireOwnership();
      }
      assertThat(holder.pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }
  }

  @Test
  void uncertainUnlockEvictsPhysicalSessionAndDoesNotLeakLock() {
    try (var holder = new PoolHolder()) {
      UUID key = UUID.randomUUID();
      var first = holder.locks.acquire(key);
      long value = key.getMostSignificantBits() ^ key.getLeastSignificantBits();
      assertThat(
              jdbc.queryForObject(
                  "select pg_terminate_backend(pid) from pg_locks where locktype='advisory' and classid::bigint=? and objid::bigint=? and objsubid=1 and granted",
                  Boolean.class,
                  value >>> 32,
                  value & 0xffffffffL))
          .isTrue();
      assertThatThrownBy(first::requireOwnership)
          .isInstanceOf(com.afterbooth.global.error.BusinessException.class);
      first.close();
      try (var second = holder.locks.acquire(key)) {
        second.requireOwnership();
      }
      assertThat(holder.pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }
  }

  class PoolHolder implements AutoCloseable {
    final IdempotencyLockPool locks = new IdempotencyLockPool(source, 1, 0, Duration.ofMillis(250));
    final HikariDataSource pool = (HikariDataSource) ReflectionTestUtils.getField(locks, "pool");

    public void close() {
      locks.close();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Database {
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
      return new PostgreSQLContainer("postgres:17-alpine");
    }
  }
}
