package com.afterbooth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.security.AuthenticatedMember;
import com.afterbooth.global.security.JwtService;
import com.afterbooth.member.dto.ChangePasswordRequest;
import com.afterbooth.member.dto.LoginMemberRequest;
import com.afterbooth.member.dto.ResetPasswordRequest;
import com.afterbooth.member.mail.MailGateway;
import com.afterbooth.member.mail.MailMessage;
import com.afterbooth.member.service.PasswordService;
import com.afterbooth.member.service.SessionService;
import com.afterbooth.member.service.SignupService;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@Import(TestcontainersConfiguration.class)
class PasswordIntegrationTest {
  @Autowired PasswordService passwords;
  @Autowired SignupService signup;
  @Autowired SessionService sessions;
  @Autowired PasswordEncoder encoder;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtService jwt;
  @Autowired PlatformTransactionManager manager;
  @MockitoBean MailGateway mail;

  @MockitoBean(name = "signupMailExecutor")
  Executor executor;

  private final ConcurrentLinkedQueue<MailMessage> sent = new ConcurrentLinkedQueue<>();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    SyntheticAuthSupport.configure(registry);
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    registry.add("afterbooth.auth.hmac.current-version", () -> "password-test");
    registry.add(
        "afterbooth.auth.hmac.keys.password-test", () -> Base64.getEncoder().encodeToString(key));
  }

  @BeforeEach
  void captureSyntheticMail() {
    doAnswer(
            call -> {
              ((Runnable) call.getArgument(0)).run();
              return null;
            })
        .when(executor)
        .execute(any());
    doAnswer(
            call -> {
              sent.add(call.getArgument(0));
              return null;
            })
        .when(mail)
        .send(any());
  }

  private Account account() {
    String email = UUID.randomUUID() + "@example.invalid",
        password = " " + UUID.randomUUID() + " e\u0301 ";
    long id =
        jdbc.queryForObject(
            "insert into member(email_normalized,password_hash,state,created_at,verified_at) values(?,?,'ACTIVE',clock_timestamp(),clock_timestamp()) returning id",
            Long.class,
            email,
            encoder.encode(password));
    long organization =
        jdbc.queryForObject(
            "insert into organization(name,primary_member_id) values('Synthetic password account',?) returning id",
            Long.class,
            id);
    return new Account(id, email, password, organization);
  }

  private SessionService.Issued login(Account a) {
    return sessions.login(new LoginMemberRequest(a.email(), a.password()), null);
  }

  private AuthenticatedMember principal(SessionService.Issued issued) {
    return jwt.verify(issued.response().accessToken(), Instant.now());
  }

  private MailMessage reset(Account a) {
    var issued = signup.requestPasswordReset(a.email());
    return sent.stream()
        .filter(message -> message.challengeId().equals(issued.challengeId()))
        .findFirst()
        .orElseThrow();
  }

  private ResetPasswordRequest request(MailMessage message, String next) {
    return new ResetPasswordRequest(
        message.recipient(), message.challengeId(), message.code(), next);
  }

  private ErrorCode outcome(Runnable action) {
    try {
      action.run();
      return null;
    } catch (BusinessException e) {
      return e.errorCode();
    }
  }

  private void fails(Runnable action, ErrorCode code) {
    assertThat(outcome(action)).isEqualTo(code);
  }

  private long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  private boolean matches(Account a, String password) {
    return encoder.matches(
        password,
        jdbc.queryForObject("select password_hash from member where id=?", String.class, a.id()));
  }

  @Test
  void changeRevokesAllSessionsResetChallengesAndCsrfWithoutChangingIdentity() {
    var a = account();
    var one = login(a);
    var two = login(a);
    var actor = principal(one);
    var code = reset(a);
    String next = " " + UUID.randomUUID() + " e\u0301 ";
    passwords.change(actor, new ChangePasswordRequest(a.password(), next));
    assertThat(matches(a, next)).isTrue();
    assertThat(matches(a, next.trim())).isFalse();
    assertThat(count("select auth_version from member where id=?", a.id())).isEqualTo(1);
    assertThat(
            count(
                "select count(*) from refresh_session where member_id=? and revoked_at is null",
                a.id()))
        .isZero();
    assertThat(count("select count(*) from csrf_session where member_id=?", a.id())).isZero();
    assertThat(
            count(
                "select count(*) from organization where id=? and primary_member_id=?",
                a.organization(),
                a.id()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select email_normalized=? from member where id=?",
                Boolean.class,
                a.email(),
                a.id()))
        .isTrue();
    fails(() -> principal(one), ErrorCode.UNAUTHENTICATED);
    fails(() -> principal(two), ErrorCode.UNAUTHENTICATED);
    assertThat(sessions.refresh(one.refresh(), null) == null).isTrue();
    fails(() -> passwords.reset(request(code, a.password())), ErrorCode.INVALID_AUTH_CHALLENGE);
    fails(
        () -> passwords.change(actor, new ChangePasswordRequest(a.password(), next)),
        ErrorCode.UNAUTHENTICATED);
    assertThat(sessions.login(new LoginMemberRequest(a.email(), next), null) != null).isTrue();
  }

  @Test
  void resetConsumesCodeRevokesSessionsAndAllowsNormalizedEmailLogin() {
    var a = account();
    var session = login(a);
    var message = reset(a);
    String next = UUID.randomUUID().toString();
    passwords.reset(
        new ResetPasswordRequest(
            " " + a.email().toUpperCase() + " ", message.challengeId(), message.code(), next));
    assertThat(matches(a, next)).isTrue();
    assertThat(count("select auth_version from member where id=?", a.id())).isEqualTo(1);
    assertThat(count("select count(*) from csrf_session where member_id=?", a.id())).isZero();
    fails(() -> principal(session), ErrorCode.UNAUTHENTICATED);
    fails(() -> passwords.reset(request(message, a.password())), ErrorCode.INVALID_AUTH_CHALLENGE);
    assertThat(sessions.login(new LoginMemberRequest(a.email(), next), null) != null).isTrue();
    assertThat(
            count(
                "select count(*) from organization where id=? and primary_member_id=?",
                a.organization(),
                a.id()))
        .isEqualTo(1);
    assertThat(count("select count(*) from idempotency_record where actor_member_id=?", a.id()))
        .isZero();
  }

  @Test
  void failedCurrentChecksSurviveCallerRollbackAndBlockAfterFive() {
    var a = account();
    var actor = principal(login(a));
    var caller = new TransactionTemplate(manager);
    for (int i = 0; i < 5; i++)
      caller.executeWithoutResult(
          status -> {
            fails(
                () ->
                    passwords.change(
                        actor,
                        new ChangePasswordRequest(UUID.randomUUID().toString(), a.password())),
                ErrorCode.INVALID_CURRENT_PASSWORD);
            status.setRollbackOnly();
          });
    fails(
        () ->
            passwords.change(
                actor, new ChangePasswordRequest(a.password(), UUID.randomUUID().toString())),
        ErrorCode.RATE_LIMITED);
    assertThat(matches(a, a.password())).isTrue();
    assertThat(count("select auth_version from member where id=?", a.id())).isZero();
    jdbc.update(
        "update auth_rate_limit set window_start=window_start-interval '16 minutes',expires_at=expires_at-interval '16 minutes' where scope='password-change:block'");
    passwords.change(actor, new ChangePasswordRequest(a.password(), a.password()));
  }

  @Test
  void passwordBoundsPreserveSpacesAndUnicodeWithoutNormalization() {
    var a = account();
    var actor = principal(login(a));
    for (String invalid : new String[] {"", "x".repeat(11), "x".repeat(129)}) {
      fails(
          () -> passwords.change(actor, new ChangePasswordRequest(invalid, a.password())),
          ErrorCode.INVALID_INPUT);
      fails(
          () -> passwords.change(actor, new ChangePasswordRequest(a.password(), invalid)),
          ErrorCode.INVALID_INPUT);
    }
    fails(
        () ->
            passwords.change(
                actor,
                new ChangePasswordRequest(a.password().replace("e\u0301", "\u00e9"), a.password())),
        ErrorCode.INVALID_CURRENT_PASSWORD);
    String next = "\ud83d\ude00".repeat(128);
    passwords.change(actor, new ChangePasswordRequest(a.password(), next));
    assertThat(matches(a, next)).isTrue();
  }

  @Test
  void resetRejectsWrongCodeAndExhaustsExactlyFiveAttempts() {
    var a = account();
    var message = reset(a);
    String wrong = message.code().equals("000000") ? "000001" : "000000";
    for (int i = 0; i < 5; i++)
      fails(
          () ->
              passwords.reset(
                  new ResetPasswordRequest(a.email(), message.challengeId(), wrong, a.password())),
          ErrorCode.INVALID_AUTH_CHALLENGE);
    fails(
        () -> passwords.reset(request(message, UUID.randomUUID().toString())),
        ErrorCode.INVALID_AUTH_CHALLENGE);
    assertThat(count("select attempts from auth_challenge where id=?", message.challengeId()))
        .isEqualTo(5);
    assertThat(matches(a, a.password())).isTrue();
  }

  @Test
  void resetRejectsExpiredCodesAndInactiveMembers() {
    var a = account();
    var expired = reset(a);
    jdbc.update(
        "update auth_challenge set created_at=statement_timestamp()-interval '11 minutes',expires_at=statement_timestamp()-interval '1 minute' where id=?",
        expired.challengeId());
    fails(() -> passwords.reset(request(expired, a.password())), ErrorCode.INVALID_AUTH_CHALLENGE);
    var b = account();
    var pending = reset(b);
    jdbc.update("update member set state='PENDING_VERIFICATION' where id=?", b.id());
    fails(() -> passwords.reset(request(pending, b.password())), ErrorCode.INVALID_AUTH_CHALLENGE);
    var c = account();
    var withdrawn = reset(c);
    withdraw(c);
    fails(
        () -> passwords.reset(request(withdrawn, c.password())), ErrorCode.INVALID_AUTH_CHALLENGE);
  }

  @Test
  void resetBindsEmailChallengeAndPurposeAndRejectsSignupCode() {
    var a = account();
    var b = account();
    var code = reset(a);
    var other = reset(b);
    fails(
        () ->
            passwords.reset(
                new ResetPasswordRequest(b.email(), code.challengeId(), code.code(), b.password())),
        ErrorCode.INVALID_AUTH_CHALLENGE);
    fails(
        () ->
            passwords.reset(
                new ResetPasswordRequest(
                    a.email(), other.challengeId(), code.code(), a.password())),
        ErrorCode.INVALID_AUTH_CHALLENGE);
    jdbc.update("update member set state='PENDING_VERIFICATION' where id=?", a.id());
    var signupCode = signup.requestVerification(a.email());
    var message =
        sent.stream()
            .filter(m -> m.challengeId().equals(signupCode.challengeId()))
            .findFirst()
            .orElseThrow();
    jdbc.update("update member set state='ACTIVE' where id=?", a.id());
    fails(() -> passwords.reset(request(message, a.password())), ErrorCode.INVALID_AUTH_CHALLENGE);
    assertThat(count("select attempts from auth_challenge where id=?", message.challengeId()))
        .isZero();
  }

  @Test
  void resetIssuanceIsGenericForMissingInactiveQuotaAndDeliveryFailure() {
    var active = account();
    var missing = UUID.randomUUID() + "@example.invalid";
    var actual = signup.requestPasswordReset(active.email());
    var absent = signup.requestPasswordReset(missing);
    assertThat(actual.message()).isEqualTo(absent.message());
    assertThat(absent.challengeId().matches("[A-Za-z0-9_-]{43}")).isTrue();
    assertThat(
            count(
                "select count(*) from auth_challenge where id=? and member_id is null and code_hmac is null",
                absent.challengeId()))
        .isEqualTo(1);
    var limited = signup.requestPasswordReset(active.email());
    assertThat(limited.message()).isEqualTo(actual.message());
    assertThat(
            count(
                "select count(*) from auth_challenge where id=? and member_id is null",
                limited.challengeId()))
        .isEqualTo(1);
    var pending = account();
    jdbc.update("update member set state='PENDING_VERIFICATION' where id=?", pending.id());
    var dummy = signup.requestPasswordReset(pending.email());
    assertThat(dummy.message()).isEqualTo(actual.message());
    assertThat(
            count(
                "select count(*) from auth_challenge where id=? and member_id is null",
                dummy.challengeId()))
        .isEqualTo(1);
    doThrow(new IllegalStateException("Synthetic delivery failure")).when(mail).send(any());
    assertThat(signup.requestPasswordReset(account().email()).message())
        .isEqualTo(actual.message());
  }

  @Test
  void reissueInvalidatesOldCodeAndConfirmationHasNoIssuanceInterval() {
    var a = account();
    var old = reset(a);
    jdbc.update(
        "update auth_rate_limit set window_start=window_start-interval '61 seconds',expires_at=expires_at-interval '61 seconds' where scope='password-reset:issue:email:interval'");
    var fresh = reset(a);
    fails(() -> passwords.reset(request(old, a.password())), ErrorCode.INVALID_AUTH_CHALLENGE);
    passwords.reset(request(fresh, UUID.randomUUID().toString()));
  }

  @Test
  void sixthHourlyResetIssuanceIsDummyEvenAfterIntervalElapses() {
    var a = account();
    for (int i = 0; i < 5; i++) {
      assertThat(reset(a).purpose()).isEqualTo(MailMessage.Purpose.PASSWORD_RESET);
      jdbc.update(
          "update auth_rate_limit set window_start=window_start-interval '61 seconds',expires_at=expires_at-interval '61 seconds' where scope='password-reset:issue:email:interval'");
    }
    var sixth = signup.requestPasswordReset(a.email());
    assertThat(
            count(
                "select count(*) from auth_challenge where id=? and member_id is null",
                sixth.challengeId()))
        .isEqualTo(1);
    assertThat(sent.size()).isEqualTo(5);
  }

  @Test
  void concurrentChangesHaveOneWinnerAndNeverRestoreOldCredentials() throws Exception {
    var a = account();
    var actor = principal(login(a));
    var start = new CountDownLatch(1);
    String x = UUID.randomUUID().toString(), y = UUID.randomUUID().toString();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var one =
          pool.submit(
              () -> {
                start.await();
                return outcome(
                    () -> passwords.change(actor, new ChangePasswordRequest(a.password(), x)));
              });
      var two =
          pool.submit(
              () -> {
                start.await();
                return outcome(
                    () -> passwords.change(actor, new ChangePasswordRequest(a.password(), y)));
              });
      start.countDown();
      var r1 = one.get(15, TimeUnit.SECONDS);
      var r2 = two.get(15, TimeUnit.SECONDS);
      assertThat((r1 == null) != (r2 == null)).isTrue();
      assertThat(r1 == ErrorCode.UNAUTHENTICATED || r2 == ErrorCode.UNAUTHENTICATED).isTrue();
      assertThat(matches(a, r1 == null ? x : y)).isTrue();
      assertThat(count("select auth_version from member where id=?", a.id())).isEqualTo(1);
    }
  }

  @Test
  void changeAndResetRaceHaveOneWinnerAndInvalidateOlderCode() throws Exception {
    var a = account();
    var actor = principal(login(a));
    var code = reset(a);
    var start = new CountDownLatch(1);
    String changed = UUID.randomUUID().toString(), reset = UUID.randomUUID().toString();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var one =
          pool.submit(
              () -> {
                start.await();
                return outcome(
                    () ->
                        passwords.change(actor, new ChangePasswordRequest(a.password(), changed)));
              });
      var two =
          pool.submit(
              () -> {
                start.await();
                return outcome(() -> passwords.reset(request(code, reset)));
              });
      start.countDown();
      var x = one.get(15, TimeUnit.SECONDS);
      var y = two.get(15, TimeUnit.SECONDS);
      assertThat((x == null) != (y == null)).isTrue();
      assertThat(matches(a, x == null ? changed : reset)).isTrue();
      assertThat(count("select auth_version from member where id=?", a.id())).isEqualTo(1);
    }
  }

  @Test
  void staleLoginWaitingForMemberCannotIssueAfterCredentialVersionProtocolCommits()
      throws Exception {
    var a = account();
    var actor = principal(login(a));
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var holderPid = new AtomicInteger();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var blocker =
          pool.submit(
              () ->
                  new TransactionTemplate(manager)
                      .executeWithoutResult(
                          status -> {
                            holderPid.set(
                                jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                            jdbc.queryForList(
                                "select id from member where id=? for update", Long.class, a.id());
                            held.countDown();
                            try {
                              release.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                              throw new IllegalStateException(e);
                            }
                            // Commit the same mutation protocol while the login is queued behind
                            // this member row.
                            jdbc.update(
                                "update member set password_hash=?,auth_version=auth_version+1 where id=?",
                                encoder.encode(UUID.randomUUID().toString()),
                                a.id());
                            jdbc.update(
                                "update refresh_session set revoked_at=clock_timestamp() where member_id=?",
                                a.id());
                          }));
      assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
      var stale = pool.submit(() -> outcome(() -> login(a)));
      try {
        awaitMemberLockWait(holderPid.get());
      } finally {
        release.countDown();
      }
      blocker.get(15, TimeUnit.SECONDS);
      assertThat(stale.get(15, TimeUnit.SECONDS)).isEqualTo(ErrorCode.UNAUTHENTICATED);
      fails(
          () -> passwords.change(actor, new ChangePasswordRequest(a.password(), a.password())),
          ErrorCode.UNAUTHENTICATED);
    }
  }

  @Test
  void realPasswordChangeRacingLoginAndRefreshLeavesNoValidOldSession() throws Exception {
    var a = account();
    var original = login(a);
    var actor = principal(original);
    String next = UUID.randomUUID().toString();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(3)) {
      var change =
          pool.submit(
              () -> {
                start.await();
                passwords.change(actor, new ChangePasswordRequest(a.password(), next));
                return true;
              });
      var login =
          pool.submit(
              () -> {
                start.await();
                try {
                  return login(a);
                } catch (BusinessException e) {
                  assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
                  return null;
                }
              });
      var refresh =
          pool.submit(
              () -> {
                start.await();
                return sessions.refresh(original.refresh(), null);
              });
      start.countDown();
      assertThat(change.get(15, TimeUnit.SECONDS)).isTrue();
      var loggedIn = login.get(15, TimeUnit.SECONDS);
      var refreshed = refresh.get(15, TimeUnit.SECONDS);
      if (loggedIn != null) fails(() -> principal(loggedIn), ErrorCode.UNAUTHENTICATED);
      if (refreshed != null) fails(() -> principal(refreshed), ErrorCode.UNAUTHENTICATED);
      fails(() -> principal(original), ErrorCode.UNAUTHENTICATED);
      assertThat(
              count(
                  "select count(*) from refresh_session where member_id=? and revoked_at is null",
                  a.id()))
          .isZero();
      assertThat(sessions.login(new LoginMemberRequest(a.email(), next), null) != null).isTrue();
      assertThat(count("select auth_version from member where id=?", a.id())).isEqualTo(1);
    }
  }

  @Test
  void realResetRacingSessionIssuanceLeavesOnlyNewCredentialUsable() throws Exception {
    var a = account();
    var original = login(a);
    var message = reset(a);
    String next = UUID.randomUUID().toString();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var reset =
          pool.submit(
              () -> {
                start.await();
                passwords.reset(request(message, next));
                return true;
              });
      var login =
          pool.submit(
              () -> {
                start.await();
                try {
                  return login(a);
                } catch (BusinessException e) {
                  assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
                  return null;
                }
              });
      start.countDown();
      assertThat(reset.get(15, TimeUnit.SECONDS)).isTrue();
      var issued = login.get(15, TimeUnit.SECONDS);
      if (issued != null) fails(() -> principal(issued), ErrorCode.UNAUTHENTICATED);
      fails(() -> principal(original), ErrorCode.UNAUTHENTICATED);
      assertThat(
              count(
                  "select count(*) from refresh_session where member_id=? and revoked_at is null",
                  a.id()))
          .isZero();
      assertThat(sessions.login(new LoginMemberRequest(a.email(), next), null) != null).isTrue();
    }
  }

  @Test
  void withdrawalProtocolWinningMemberLockRejectsWaitingReset() throws Exception {
    var a = account();
    var message = reset(a);
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var holderPid = new AtomicInteger();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var withdrawal =
          pool.submit(
              () ->
                  new TransactionTemplate(manager)
                      .executeWithoutResult(
                          status -> {
                            holderPid.set(
                                jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                            jdbc.queryForList(
                                "select id from member where id=? for update", Long.class, a.id());
                            held.countDown();
                            try {
                              release.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                              throw new IllegalStateException(e);
                            }
                            withdraw(a);
                          }));
      assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
      var waiting =
          pool.submit(() -> outcome(() -> passwords.reset(request(message, a.password()))));
      try {
        awaitMemberLockWait(holderPid.get());
      } finally {
        release.countDown();
      }
      withdrawal.get(15, TimeUnit.SECONDS);
      assertThat(waiting.get(15, TimeUnit.SECONDS)).isEqualTo(ErrorCode.INVALID_AUTH_CHALLENGE);
      assertThat(
              count(
                  "select count(*) from member where id=? and password_hash is null and state='WITHDRAWN'",
                  a.id()))
          .isEqualTo(1);
    }
  }

  @Test
  void mutationFailureRollsBackHashVersionSessionsChallengesAndCsrfTogether() {
    var a = account();
    var issued = login(a);
    var actor = principal(issued);
    var code = reset(a);
    jdbc.execute(
        "create function synthetic_password_failure() returns trigger language plpgsql as $$ begin raise exception 'synthetic failure'; end $$");
    jdbc.execute(
        "create trigger synthetic_password_failure before delete on csrf_session for each row execute function synthetic_password_failure()");
    try {
      fails(
          () ->
              passwords.change(
                  actor, new ChangePasswordRequest(a.password(), UUID.randomUUID().toString())),
          ErrorCode.AUTH_UNAVAILABLE);
      assertThat(matches(a, a.password())).isTrue();
      assertThat(count("select auth_version from member where id=?", a.id())).isZero();
      assertThat(
              count(
                  "select count(*) from auth_challenge where id=? and consumed_at is null",
                  code.challengeId()))
          .isEqualTo(1);
      assertThat(
              count(
                  "select count(*) from refresh_session where member_id=? and revoked_at is null",
                  a.id()))
          .isEqualTo(1);
      assertThat(principal(issued).memberId()).isEqualTo(a.id());
    } finally {
      jdbc.execute("drop trigger synthetic_password_failure on csrf_session");
      jdbc.execute("drop function synthetic_password_failure()");
    }
  }

  private void withdraw(Account a) {
    jdbc.update(
        "update member set state='WITHDRAWN',auth_version=auth_version+1,email_normalized=null,password_hash=null,withdrawn_at=clock_timestamp(),purge_after=clock_timestamp()+interval '30 days' where id=?",
        a.id());
  }

  private void awaitMemberLockWait(int holderPid) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (Boolean.TRUE.equals(
          jdbc.queryForObject(
              "select exists(select 1 from pg_stat_activity where wait_event_type='Lock' and ?=any(pg_blocking_pids(pid)) and lower(query) like '%from member%')",
              Boolean.class, holderPid))) return;
      Thread.sleep(20);
    }
    throw new AssertionError("Member query did not enter the expected PostgreSQL lock wait");
  }

  private record Account(long id, String email, String password, long organization) {
    @Override
    public String toString() {
      return "Account[redacted]";
    }
  }
}
