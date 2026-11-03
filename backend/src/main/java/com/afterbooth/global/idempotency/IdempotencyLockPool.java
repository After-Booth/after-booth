package com.afterbooth.global.idempotency;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyLockPool {
  private final HikariDataSource pool;
  private final ThreadLocal<Session> current = new ThreadLocal<>();

  @org.springframework.beans.factory.annotation.Autowired
  public IdempotencyLockPool(
      DataSource source,
      @Value("${afterbooth.idempotency.lock-pool.maximum-pool-size:16}") int maximum,
      // Prefilled to the maximum: a burst on a growing pool can strand a waiter until the timeout
      @Value(
              "${afterbooth.idempotency.lock-pool.minimum-idle:${afterbooth.idempotency.lock-pool.maximum-pool-size:16}}")
          int minimum,
      @Value("${afterbooth.idempotency.lock-pool.connection-timeout:5s}")
          Duration connectionTimeout) {
    this(source, maximum, minimum, connectionTimeout.toMillis());
  }

  IdempotencyLockPool(DataSource source, int maximum, int minimum, long timeout) {
    if (maximum < 1 || minimum < 0 || minimum > maximum || timeout < 250)
      throw new IllegalArgumentException("Invalid afterbooth.idempotency.lock-pool settings");
    try {
      HikariDataSource original = source.unwrap(HikariDataSource.class);
      HikariConfig config = new HikariConfig();
      config.setPoolName("idempotency-locks");
      config.setJdbcUrl(original.getJdbcUrl());
      config.setUsername(original.getUsername());
      config.setPassword(original.getPassword());
      config.setDriverClassName(original.getDriverClassName());
      config.setDataSourceProperties(original.getDataSourceProperties());
      if (original.getDataSource() != null) config.setDataSource(original.getDataSource());
      if (original.getDataSourceClassName() != null)
        config.setDataSourceClassName(original.getDataSourceClassName());
      config.setCatalog(original.getCatalog());
      config.setSchema(original.getSchema());
      config.setConnectionInitSql(original.getConnectionInitSql());
      config.setConnectionTestQuery(original.getConnectionTestQuery());
      config.setMaximumPoolSize(maximum);
      config.setMinimumIdle(minimum);
      config.setConnectionTimeout(timeout);
      config.setAutoCommit(true);
      pool = new HikariDataSource();
      config.copyStateTo(pool);
    } catch (SQLException error) {
      throw new IllegalStateException("Cannot configure idempotency lock pool", error);
    }
  }

  Session acquire(UUID key) {
    long lockKey = key.getMostSignificantBits() ^ key.getLeastSignificantBits();
    Connection connection = null;
    try {
      connection = pool.getConnection();
      Session session = new Session(connection, lockKey);
      if (!session.query("select pg_try_advisory_lock(?)", lockKey)) {
        connection.close();
        throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
      }
      current.set(session);
      return session;
    } catch (SQLException error) {
      if (connection != null) discard(connection);
      throw new IllegalStateException("Cannot acquire idempotency lock", error);
    }
  }

  public <T> T allocation(long requestId, java.util.function.Supplier<T> action) {
    Session existing = current.get();
    if (existing != null) {
      existing.requireAllocation(requestId);
      return action.get();
    }
    Session session;
    try {
      session = acquire(new UUID(Long.MIN_VALUE, requestId));
    } catch (BusinessException failure) {
      if (failure.errorCode() == ErrorCode.IDEMPOTENCY_CONFLICT)
        throw new BusinessException(ErrorCode.REQUEST_ALREADY_HELD);
      throw failure;
    }
    try (session) {
      return action.get();
    }
  }

  private void discard(Connection connection) {
    pool.evictConnection(connection);
    try {
      connection.close();
    } catch (SQLException ignored) {
    }
  }

  @PreDestroy
  public void close() {
    pool.close();
  }

  final class Session implements AutoCloseable {
    private final Connection connection;
    private final long lockKey;
    private final java.util.List<Long> additional = new java.util.ArrayList<>();

    private Session(Connection connection, long lockKey) {
      this.connection = connection;
      this.lockKey = lockKey;
    }

    void requireOwnership() {
      try {
        if (!query(
            "select exists(select 1 from pg_locks where locktype='advisory' and pid=pg_backend_pid() and classid::bigint=? and objid::bigint=? and objsubid=1 and granted)",
            lockKey >>> 32,
            lockKey & 0xffffffffL)) throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
      } catch (SQLException error) {
        throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
      }
    }

    void requireAllocation(long requestId) {
      long value = Long.MIN_VALUE ^ requestId;
      try {
        if (!query("select pg_try_advisory_lock(?)", value))
          throw new BusinessException(ErrorCode.REQUEST_ALREADY_HELD);
        additional.add(value);
      } catch (SQLException error) {
        throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
      }
    }

    private boolean query(String sql, long... values) throws SQLException {
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        for (int i = 0; i < values.length; i++) statement.setLong(i + 1, values[i]);
        try (ResultSet result = statement.executeQuery()) {
          return result.next() && result.getBoolean(1);
        }
      }
    }

    @Override
    public void close() {
      current.remove();
      try {
        for (Long value : additional)
          if (!query("select pg_advisory_unlock(?)", value)) {
            discard(connection);
            return;
          }
        if (!query("select pg_advisory_unlock(?)", lockKey)) {
          discard(connection);
          return;
        }
        connection.close();
      } catch (SQLException error) {
        discard(connection);
      }
    }
  }
}
