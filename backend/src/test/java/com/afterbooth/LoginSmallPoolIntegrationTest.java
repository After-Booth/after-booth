package com.afterbooth;

import static org.assertj.core.api.Assertions.assertThat;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.member.dto.LoginMemberRequest;
import com.afterbooth.member.service.SessionService;
import com.zaxxer.hikari.HikariDataSource;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(
    properties = {
      "spring.docker.compose.enabled=false",
      "spring.datasource.hikari.maximum-pool-size=3",
      "spring.datasource.hikari.connection-timeout=1000"
    })
class LoginSmallPoolIntegrationTest {
  @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17-alpine");
  @Autowired SessionService sessions;
  @Autowired JdbcTemplate jdbc;
  @Autowired HikariDataSource datasource;
  @Autowired PasswordEncoder passwords;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DB::getJdbcUrl);
    registry.add("spring.datasource.username", DB::getUsername);
    registry.add("spring.datasource.password", DB::getPassword);
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    registry.add("afterbooth.auth.hmac.current-version", () -> "pool");
    registry.add("afterbooth.auth.hmac.keys.pool", () -> Base64.getEncoder().encodeToString(bytes));
    SyntheticAuthSupport.configure(registry);
  }

  @Test
  void queuedAttemptsUseOneConnectionEachAndCorrectPasswordCannotBypassNewRestriction()
      throws Exception {
    String email = UUID.randomUUID() + "@example.invalid",
        password = UUID.randomUUID().toString(),
        wrong = UUID.randomUUID().toString();
    long id =
        jdbc.queryForObject(
            "insert into member(email_normalized,password_hash,state,created_at,verified_at) values(?,?,'ACTIVE',clock_timestamp(),clock_timestamp()) returning id",
            Long.class,
            email,
            passwords.encode(password));
    var results = new ArrayList<Future<ErrorCode>>();
    try (var blocker =
            DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        var executor = Executors.newFixedThreadPool(8)) {
      blocker.setAutoCommit(false);
      try (var statement =
          blocker.prepareStatement("select id from member where id=? for update")) {
        statement.setLong(1, id);
        statement.executeQuery().close();
      }
      for (int i = 0; i < 8; i++) results.add(executor.submit(() -> result(email, wrong)));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (datasource.getHikariPoolMXBean().getActiveConnections() < 3
          && System.nanoTime() < deadline) Thread.sleep(5);
      boolean saturated = datasource.getHikariPoolMXBean().getActiveConnections() == 3;
      blocker.commit();
      assertThat(saturated).isTrue();
      int failed = 0, limited = 0;
      for (var future : results) {
        var code = future.get(10, TimeUnit.SECONDS);
        if (code == ErrorCode.UNAUTHENTICATED) failed++;
        if (code == ErrorCode.RATE_LIMITED) limited++;
      }
      assertThat(failed).isEqualTo(5);
      assertThat(limited).isEqualTo(3);
    }
    assertThat(result(email, password)).isEqualTo(ErrorCode.RATE_LIMITED);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from refresh_session where member_id=?", Long.class, id))
        .isZero();
  }

  private ErrorCode result(String email, String password) {
    try {
      sessions.login(new LoginMemberRequest(email, password), null);
      return null;
    } catch (BusinessException e) {
      return e.errorCode();
    }
  }
}
