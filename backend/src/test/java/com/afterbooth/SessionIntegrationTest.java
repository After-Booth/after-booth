package com.afterbooth;

import static org.assertj.core.api.Assertions.assertThat;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.security.AuthenticatedMember;
import com.afterbooth.global.security.JwtService;
import com.afterbooth.member.dto.LoginMemberRequest;
import com.afterbooth.member.service.SessionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@Import(TestcontainersConfiguration.class)
class SessionIntegrationTest {
  @Autowired SessionService sessions;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder passwords;
  @Autowired JwtService jwt;
  @Autowired com.afterbooth.global.security.CsrfSessionService csrf;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    SyntheticAuthSupport.configure(registry);
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    registry.add("afterbooth.auth.hmac.current-version", () -> "sessions");
    registry.add(
        "afterbooth.auth.hmac.keys.sessions", () -> Base64.getEncoder().encodeToString(key));
  }

  private Account account(String state) {
    String email = UUID.randomUUID() + "@example.invalid",
        password = " " + UUID.randomUUID() + " e\u0301 ";
    long id =
        jdbc.queryForObject(
            "insert into member(email_normalized,password_hash,state,auth_version,created_at,verified_at) values(?,?,?,0,statement_timestamp(),case when ?='ACTIVE' then statement_timestamp() else null end) returning id",
            Long.class,
            email,
            passwords.encode(password),
            state,
            state);
    return new Account(id, email, password);
  }

  private SessionService.Issued login(Account a) {
    return sessions.login(new LoginMemberRequest(a.email(), a.password()), null);
  }

  private void rejected(Runnable action, ErrorCode expected) {
    ErrorCode actual = null;
    try {
      action.run();
    } catch (BusinessException e) {
      actual = e.errorCode();
    }
    assertThat(actual == expected).isTrue();
  }

  @Test
  void normalizedLoginPreservesRawPasswordAndUniformFailures() {
    var active = account("ACTIVE");
    var issued =
        sessions.login(
            new LoginMemberRequest("  " + active.email().toUpperCase() + "  ", active.password()),
            null);
    assertThat(issued.response().expiresIn()).isEqualTo(600);
    assertThat(issued.maxAge() > 604790 && issued.maxAge() <= 604800).isTrue();
    rejected(
        () ->
            sessions.login(new LoginMemberRequest(active.email(), active.password().trim()), null),
        ErrorCode.UNAUTHENTICATED);
    rejected(
        () ->
            sessions.login(
                new LoginMemberRequest(UUID.randomUUID() + "@example.invalid", active.password()),
                null),
        ErrorCode.UNAUTHENTICATED);
    var pending = account("PENDING_VERIFICATION");
    rejected(() -> login(pending), ErrorCode.EMAIL_NOT_VERIFIED);
    rejected(
        () ->
            sessions.login(
                new LoginMemberRequest(pending.email(), UUID.randomUUID().toString()), null),
        ErrorCode.UNAUTHENTICATED);
    jdbc.update(
        "update member set state='WITHDRAWN',email_normalized=null,password_hash=null,withdrawn_at=statement_timestamp(),purge_after=statement_timestamp()+interval '720 hours' where id=?",
        active.id());
    rejected(() -> login(active), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void rotationStoresHashesRetainsEvidenceAndReuseCommitsFamilyRevocation() throws Exception {
    var a = account("ACTIVE");
    var first = login(a);
    var second = sessions.refresh(first.refresh(), first.csrf().sessionId());
    assertThat(second != null && !second.refresh().equals(first.refresh())).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from refresh_token t join refresh_session s on s.id=t.session_id where s.member_id=?",
                Integer.class,
                a.id()))
        .isEqualTo(2);
    byte[] hash =
        MessageDigest.getInstance("SHA-256")
            .digest(first.refresh().getBytes(StandardCharsets.UTF_8));
    assertThat(
            jdbc.queryForObject(
                "select used_at is not null from refresh_token where token_hash=?",
                Boolean.class,
                hash))
        .isTrue();
    assertThat(sessions.refresh(first.refresh(), null) == null).isTrue();
    assertThat(sessions.refresh(second.refresh(), null) == null).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select revoked_at is not null from refresh_session where member_id=?",
                Boolean.class,
                a.id()))
        .isTrue();
  }

  @Test
  void concurrentRefreshHasOneWinnerAndReuseRevokesWinner() throws Exception {
    var a = account("ACTIVE");
    var first = login(a);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var one =
          executor.submit(
              () -> {
                start.await();
                return sessions.refresh(first.refresh(), null);
              });
      var two =
          executor.submit(
              () -> {
                start.await();
                return sessions.refresh(first.refresh(), null);
              });
      start.countDown();
      var x = one.get();
      var y = two.get();
      assertThat((x == null) != (y == null)).isTrue();
      assertThat(sessions.refresh((x == null ? y : x).refresh(), null) == null).isTrue();
    }
  }

  @Test
  void currentLogoutAndLogoutAllHaveDifferentScopeAndPreserveAnonymousCsrf() {
    var a = account("ACTIVE");
    var one = login(a);
    var two = login(a);
    sessions.logout(one.refresh());
    sessions.logout(one.refresh());
    sessions.logout(null);
    assertThat(sessions.refresh(one.refresh(), null) == null).isTrue();
    var current = sessions.refresh(two.refresh(), two.csrf().sessionId());
    assertThat(current != null).isTrue();
    UUID sid =
        jdbc.queryForObject(
            "select id from refresh_session where member_id=? and revoked_at is null",
            UUID.class,
            a.id());
    long anonymous =
        jdbc.queryForObject(
            "select count(*) from csrf_session where member_id is null", Long.class);
    sessions.logoutAll(new AuthenticatedMember(a.id(), sid, 0));
    assertThat(sessions.refresh(current.refresh(), null) == null).isTrue();
    assertThat(
            jdbc.queryForObject("select auth_version from member where id=?", Long.class, a.id()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from csrf_session where member_id=?", Long.class, a.id()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from csrf_session where member_id is null", Long.class))
        .isEqualTo(anonymous);
    rejected(
        () -> sessions.logoutAll(new AuthenticatedMember(a.id(), sid, 0)),
        ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void expiredFamilyCannotAuthenticateAndCleanupCascadesTokensOnlyAfterExpiry() {
    var a = account("ACTIVE");
    var first = login(a);
    sessions.refresh(first.refresh(), null);
    assertThat(sessions.cleanupExpired()).isZero();
    jdbc.update(
        "update refresh_session set created_at=statement_timestamp()-interval '8 days',expires_at=statement_timestamp()-interval '1 day' where member_id=?",
        a.id());
    assertThat(sessions.refresh(first.refresh(), null) == null).isTrue();
    assertThat(sessions.cleanupExpired()).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from member where id=?", Integer.class, a.id()))
        .isEqualTo(1);
  }

  @Test
  void passwordChangedWhileLoginWaitsCannotIssueFromStaleCredentials() throws Exception {
    var a = account("ACTIVE");
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    String replacement = passwords.encode(UUID.randomUUID().toString());
    try (var executor = Executors.newFixedThreadPool(2)) {
      var holder =
          executor.submit(
              () ->
                  new org.springframework.transaction.support.TransactionTemplate(manager)
                      .executeWithoutResult(
                          status -> {
                            jdbc.queryForList(
                                "select id from member where id=? for update", Long.class, a.id());
                            held.countDown();
                            await(release);
                            jdbc.update(
                                "update member set password_hash=?,auth_version=auth_version+1 where id=?",
                                replacement,
                                a.id());
                          }));
      held.await();
      var waiting =
          executor.submit(
              () -> {
                try {
                  login(a);
                  return false;
                } catch (BusinessException e) {
                  return e.errorCode() == ErrorCode.UNAUTHENTICATED;
                }
              });
      try {
        Thread.sleep(100);
        assertThat(waiting.isDone()).isFalse();
      } finally {
        release.countDown();
      }
      holder.get();
      assertThat(waiting.get()).isTrue();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from refresh_session where member_id=?", Long.class, a.id()))
          .isZero();
    }
  }

  @Test
  void refreshWaitsForMemberMutationAndRejectsNewVersion() throws Exception {
    var a = account("ACTIVE");
    var first = login(a);
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var holder =
          executor.submit(
              () ->
                  new org.springframework.transaction.support.TransactionTemplate(manager)
                      .executeWithoutResult(
                          status -> {
                            jdbc.queryForList(
                                "select id from member where id=? for update", Long.class, a.id());
                            held.countDown();
                            await(release);
                            jdbc.update(
                                "update member set auth_version=auth_version+1 where id=?", a.id());
                          }));
      held.await();
      var waiting = executor.submit(() -> sessions.refresh(first.refresh(), null));
      try {
        Thread.sleep(100);
        assertThat(waiting.isDone()).isFalse();
      } finally {
        release.countDown();
      }
      holder.get();
      assertThat(waiting.get() == null).isTrue();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from refresh_token t join refresh_session s on s.id=t.session_id where s.member_id=?",
                  Long.class,
                  a.id()))
          .isEqualTo(1);
    }
  }

  @Test
  void concurrentCurrentLogoutCannotLeaveRefreshedFamilyActive() throws Exception {
    var a = account("ACTIVE");
    var first = login(a);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var refresh =
          executor.submit(
              () -> {
                start.await();
                return sessions.refresh(first.refresh(), null);
              });
      var logout =
          executor.submit(
              () -> {
                start.await();
                sessions.logout(first.refresh());
                return true;
              });
      start.countDown();
      var rotated = refresh.get();
      assertThat(logout.get()).isTrue();
      assertThat(
              jdbc.queryForObject(
                  "select revoked_at is not null from refresh_session where member_id=?",
                  Boolean.class,
                  a.id()))
          .isTrue();
      if (rotated != null) assertThat(sessions.refresh(rotated.refresh(), null) == null).isTrue();
    }
  }

  @Test
  void memberLinkedCsrfExpiryCleanupPreservesUnrelatedAnonymousAndMemberData() {
    var a = account("ACTIVE");
    login(a);
    var anonymous = csrf.issue();
    jdbc.update(
        "update csrf_session set created_at=statement_timestamp()-interval '2 hours',expires_at=statement_timestamp()-interval '1 hour' where member_id=?",
        a.id());
    long expired =
        jdbc.queryForObject(
            "select count(*) from csrf_session where expires_at<=statement_timestamp()",
            Long.class);
    assertThat(csrf.cleanupExpired()).isEqualTo(Math.toIntExact(expired));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from csrf_session where member_id=?", Long.class, a.id()))
        .isZero();
    byte[] anonymousHash;
    try {
      anonymousHash =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(anonymous.sessionId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("Required digest unavailable");
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from csrf_session where id_hash=?", Long.class, anonymousHash))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from member where id=?", Long.class, a.id()))
        .isEqualTo(1);
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Synthetic lock interrupted");
    }
  }

  private record Account(long id, String email, String password) {
    @Override
    public String toString() {
      return "Account[redacted]";
    }
  }
}
