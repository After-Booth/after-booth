package com.afterbooth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.security.HmacKeyRing;
import com.afterbooth.member.dto.RegisterMemberRequest;
import com.afterbooth.member.dto.VerifyEmailRequest;
import com.afterbooth.member.mail.MailGateway;
import com.afterbooth.member.mail.MailMessage;
import com.afterbooth.member.service.SignupCleanup;
import com.afterbooth.member.service.SignupService;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SignupIntegrationTest {
  @Autowired SignupService signup;
  @Autowired SignupCleanup cleanup;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired HmacKeyRing keys;
  @MockitoBean MailGateway mail;

  @MockitoBean(name = "signupMailExecutor")
  Executor executor;

  private final ConcurrentLinkedQueue<MailMessage> sent = new ConcurrentLinkedQueue<>();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    byte[] previous = new byte[32];
    new SecureRandom().nextBytes(previous);
    registry.add("afterbooth.auth.hmac.current-version", () -> "test-current");
    registry.add(
        "afterbooth.auth.hmac.keys.test-current", () -> Base64.getEncoder().encodeToString(key));
    registry.add(
        "afterbooth.auth.hmac.keys.test-previous",
        () -> Base64.getEncoder().encodeToString(previous));
    registry.add(
        "afterbooth.auth.hmac.previous-valid-until.test-previous",
        () -> Instant.now().plusSeconds(86400).toString());
  }

  @BeforeEach
  void capture() {
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

  @Test
  void normalizeDuplicatesDedicatedOrganizationAndFinalOwnerPassword() {
    String email = email(), original = password(), finalPassword = " " + password() + " e\u0301 ";
    signup.register(new RegisterMemberRequest("  " + email.toUpperCase() + "  ", original));
    signup.register(new RegisterMemberRequest(email, password()));
    assertThat(count("SELECT count(*) FROM member WHERE email_normalized=?", email)).isEqualTo(1);
    Long member =
        jdbc.queryForObject("SELECT id FROM member WHERE email_normalized=?", Long.class, email);
    assertThat(count("SELECT count(*) FROM organization WHERE primary_member_id=?", member))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM organization WHERE primary_member_id IS NULL"))
        .isGreaterThanOrEqualTo(3);
    String before = hash(email);
    assertThat(encoder.matches(original, before)).isTrue();
    var issued = signup.requestVerification(email);
    MailMessage message = sent.remove();
    assertThat(message.challengeId().equals(issued.challengeId())).isTrue();
    signup.verify(
        new VerifyEmailRequest(email, issued.challengeId(), message.code(), finalPassword));
    assertThat(jdbc.queryForObject("SELECT state FROM member WHERE id=?", String.class, member))
        .isEqualTo("ACTIVE");
    String after = hash(email);
    assertThat(after.startsWith("$argon2id$v=19$m=19456,t=2,p=1$")).isTrue();
    assertThat(encoder.matches(finalPassword, after)).isTrue();
    assertThat(encoder.matches(original, after)).isFalse();
    assertChallengeFailure(
        () ->
            signup.verify(
                new VerifyEmailRequest(email, issued.challengeId(), message.code(), password())));
    assertThat(hash(email).equals(after)).isTrue();
    assertThat(signup.requestVerification(email).challengeId().length() == 43).isTrue();
    assertThat(sent).isEmpty();
  }

  @Test
  void attemptsEmailBindingDummyAndExpiration() {
    String email = email();
    signup.register(new RegisterMemberRequest(email, password()));
    String id = signup.requestVerification(email).challengeId();
    MailMessage message = sent.remove();
    assertChallengeFailure(
        () -> signup.verify(new VerifyEmailRequest(email(), id, message.code(), password())));
    for (int i = 0; i < 4; i++)
      assertChallengeFailure(
          () ->
              signup.verify(new VerifyEmailRequest(email, id, wrong(message.code()), password())));
    assertThat(
            jdbc.queryForObject(
                "SELECT attempts FROM auth_challenge WHERE id=?", Integer.class, id))
        .isEqualTo(5);
    assertChallengeFailure(
        () -> signup.verify(new VerifyEmailRequest(email, id, message.code(), password())));
    String unknown = email();
    String dummy = signup.requestVerification(unknown).challengeId();
    assertThat(sent).isEmpty();
    assertChallengeFailure(
        () -> signup.verify(new VerifyEmailRequest(unknown, dummy, message.code(), password())));
    String second = email();
    signup.register(new RegisterMemberRequest(second, password()));
    String expired = signup.requestVerification(second).challengeId();
    MailMessage expiredMessage = sent.remove();
    jdbc.update(
        "UPDATE auth_challenge SET created_at=now()-interval '11 minutes',expires_at=now()-interval '1 minute' WHERE id=?",
        expired);
    assertChallengeFailure(
        () ->
            signup.verify(
                new VerifyEmailRequest(second, expired, expiredMessage.code(), password())));
  }

  @Test
  void intervalAndReissueInvalidateOnlyAllowedPreviousChallenge() {
    String email = email();
    signup.register(new RegisterMemberRequest(email, password()));
    String first = signup.requestVerification(email).challengeId();
    MailMessage initial = sent.remove();
    String dummy = signup.requestVerification(email).challengeId();
    assertThat(sent).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT consumed_at IS NULL FROM auth_challenge WHERE id=?", Boolean.class, first))
        .isTrue();
    expireInterval(email);
    String replacement = signup.requestVerification(email).challengeId();
    MailMessage latest = sent.remove();
    assertChallengeFailure(
        () -> signup.verify(new VerifyEmailRequest(email, first, initial.code(), password())));
    assertChallengeFailure(
        () -> signup.verify(new VerifyEmailRequest(email, dummy, initial.code(), password())));
    signup.verify(new VerifyEmailRequest(email, replacement, latest.code(), password()));
  }

  @Test
  void concurrentRegistrationCreatesExactlyOneAndLimitsPersist() throws Exception {
    String email = email();
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(8)) {
      var futures = new ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 12; i++)
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  signup.register(new RegisterMemberRequest(email, password()));
                  return null;
                }));
      start.countDown();
      for (var result : futures) result.get(60, TimeUnit.SECONDS);
    }
    assertThat(count("SELECT count(*) FROM member WHERE email_normalized=?", email)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(count) FROM auth_rate_limit WHERE scope='signup:email' AND subject_hmac=?",
                Long.class,
                keys.currentDigest("signup:email", email)))
        .isEqualTo(12);
  }

  @Test
  void withdrawalBlockIncludesPriorKeyAndDeadlineDespiteCleanupDelay() {
    String blocked = email();
    jdbc.update(
        "INSERT INTO withdrawn_email_block(email_hmac,key_version,blocked_until) VALUES (?,?,clock_timestamp()+interval '30 days')",
        keys.digest("test-previous", "withdrawn-email", blocked),
        "test-previous");
    signup.register(new RegisterMemberRequest(blocked, password()));
    assertThat(count("SELECT count(*) FROM member WHERE email_normalized=?", blocked)).isZero();
    jdbc.update(
        "UPDATE withdrawn_email_block SET blocked_until=clock_timestamp() WHERE email_hmac=?",
        keys.digest("test-previous", "withdrawn-email", blocked));
    signup.register(new RegisterMemberRequest(blocked, password()));
    assertThat(count("SELECT count(*) FROM member WHERE email_normalized=?", blocked)).isEqualTo(1);
  }

  @Test
  void cleanupRemovesPendingAndUnusedOrganizationPreservesReferencedAndActive() {
    String unused = email(), referenced = email(), active = email();
    for (String email : List.of(unused, referenced, active))
      signup.register(new RegisterMemberRequest(email, password()));
    String id = signup.requestVerification(active).challengeId();
    MailMessage message = sent.remove();
    signup.verify(new VerifyEmailRequest(active, id, message.code(), password()));
    Long unusedOrg =
        jdbc.queryForObject(
            "SELECT o.id FROM organization o JOIN member m ON m.id=o.primary_member_id WHERE m.email_normalized=?",
            Long.class,
            unused);
    Long referencedOrg =
        jdbc.queryForObject(
            "SELECT o.id FROM organization o JOIN member m ON m.id=o.primary_member_id WHERE m.email_normalized=?",
            Long.class,
            referenced);
    jdbc.update(
        "INSERT INTO event(organization_id,name,venue,teardown_deadline) VALUES (?,'Synthetic event','Synthetic venue',clock_timestamp()+interval '1 day')",
        referencedOrg);
    jdbc.update(
        "UPDATE member SET created_at=clock_timestamp()-interval '25 hours' WHERE email_normalized IN (?,?,?)",
        unused,
        referenced,
        active);
    cleanup.clean();
    assertThat(
            count(
                "SELECT count(*) FROM member WHERE email_normalized IN (?,?)", unused, referenced))
        .isZero();
    assertThat(count("SELECT count(*) FROM organization WHERE id=?", unusedOrg)).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM organization WHERE id=? AND primary_member_id IS NULL",
                referencedOrg))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM member WHERE email_normalized=? AND state='ACTIVE'", active))
        .isEqualTo(1);
  }

  @Test
  void pendingExpiryPreservesYoungerAccountAndResponsesDoNotRevealExistence() {
    String expired = email(), fresh = email(), unknown = email();
    var accepted = signup.register(new RegisterMemberRequest(expired, password()));
    assertThat(signup.register(new RegisterMemberRequest(expired, password()))).isEqualTo(accepted);
    assertThat(signup.register(new RegisterMemberRequest(fresh, password()))).isEqualTo(accepted);
    var real = signup.requestVerification(fresh);
    sent.clear();
    var dummy = signup.requestVerification(unknown);
    assertThat(dummy.message()).isEqualTo(real.message());
    assertThat(dummy.challengeId()).hasSize(real.challengeId().length());
    assertThat(sent).isEmpty();
    jdbc.update(
        "UPDATE member SET created_at=statement_timestamp()-interval '86401 seconds' WHERE email_normalized=?",
        expired);
    jdbc.update(
        "UPDATE member SET created_at=statement_timestamp()-interval '86300 seconds' WHERE email_normalized=?",
        fresh);
    cleanup.clean();
    assertThat(count("SELECT count(*) FROM member WHERE email_normalized=?", expired)).isZero();
    assertThat(count("SELECT count(*) FROM member WHERE email_normalized=?", fresh)).isEqualTo(1);
    signup.register(new RegisterMemberRequest(expired, password()));
    assertThat(count("SELECT count(*) FROM member WHERE email_normalized=?", expired)).isEqualTo(1);
  }

  @Test
  void memberAndSharedSchemaRejectInvalidRowsAndKeepLegacyData() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO member(state,created_at,withdrawn_at) VALUES ('WITHDRAWN',now(),now())"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO member(state,created_at,email_normalized,password_hash) VALUES ('ACTIVE',now(),?,?)",
                    email(),
                    encoder.encode(password())))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO auth_challenge(id,email_key,key_version,purpose,attempts,created_at,expires_at) VALUES (?,?,'synthetic','SIGNUP_VERIFY',6,now(),now()+interval '10 minutes')",
                    UUID.randomUUID().toString(),
                    new byte[32]))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE item SET archived_handed_over_qty=total_qty+1 WHERE id=(SELECT min(id) FROM item)"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(count("SELECT count(*) FROM organization WHERE primary_member_id IS NULL"))
        .isGreaterThanOrEqualTo(3);
    assertThat(count("SELECT count(*) FROM item WHERE archived_handed_over_qty=0"))
        .isGreaterThanOrEqualTo(3);
    assertThat(
            count(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('member','withdrawn_email_block','refresh_session','refresh_token','auth_challenge','csrf_session','auth_rate_limit','reuse_archive')"))
        .isEqualTo(8);
  }

  @Test
  void actualLockWaitRechecksExpirationAndNeverActivates() throws Exception {
    String email = email();
    signup.register(new RegisterMemberRequest(email, password()));
    String id = signup.requestVerification(email).challengeId();
    MailMessage message = sent.remove();
    try (var connection = jdbc.getDataSource().getConnection();
        var pool = Executors.newSingleThreadExecutor()) {
      connection.setAutoCommit(false);
      Instant expires =
          jdbc.queryForObject(
              "SELECT clock_timestamp()+interval '2 seconds'",
              (rs, row) -> rs.getTimestamp(1).toInstant());
      try (var update =
          connection.prepareStatement(
              "UPDATE auth_challenge SET created_at=?,expires_at=? WHERE id=?")) {
        update.setTimestamp(1, Timestamp.from(expires.minusSeconds(600)));
        update.setTimestamp(2, Timestamp.from(expires));
        update.setString(3, id);
        update.executeUpdate();
      }
      var result =
          pool.submit(
              () -> {
                try {
                  signup.verify(new VerifyEmailRequest(email, id, message.code(), password()));
                  return false;
                } catch (BusinessException error) {
                  return error.errorCode() == ErrorCode.INVALID_AUTH_CHALLENGE;
                }
              });
      boolean waiting = false;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline) {
        waiting =
            count(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE 'SELECT * FROM auth_challenge%'")
                > 0;
        if (waiting) break;
        Thread.sleep(20);
      }
      while (jdbc.queryForObject(
              "SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant())
          .isBefore(expires)) Thread.sleep(20);
      connection.commit();
      assertThat(waiting).isTrue();
      assertThat(result.get(20, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM member WHERE email_normalized=?", String.class, email))
        .isEqualTo("PENDING_VERIFICATION");
  }

  @Test
  void databaseFailureRollsBackMemberAndPreservesIndependentRateCounter() {
    String email = email();
    jdbc.execute(
        "CREATE FUNCTION synthetic_signup_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.primary_member_id IS NOT NULL THEN RAISE EXCEPTION 'synthetic private database detail'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER synthetic_signup_failure BEFORE INSERT ON organization FOR EACH ROW EXECUTE FUNCTION synthetic_signup_failure()");
    try {
      assertThatThrownBy(() -> signup.register(new RegisterMemberRequest(email, password())))
          .isInstanceOfSatisfying(
              BusinessException.class,
              error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.AUTH_UNAVAILABLE));
      assertThat(count("SELECT count(*) FROM member WHERE email_normalized=?", email)).isZero();
      assertThat(
              count(
                  "SELECT count(*) FROM auth_rate_limit WHERE scope='signup:email' AND subject_hmac=?",
                  keys.currentDigest("signup:email", email)))
          .isEqualTo(1);
    } finally {
      jdbc.execute("DROP TRIGGER synthetic_signup_failure ON organization");
      jdbc.execute("DROP FUNCTION synthetic_signup_failure()");
    }
  }

  @Test
  void cleanupDeletesExpiredAuthenticationRows() {
    String email = email();
    String id = signup.requestVerification(email).challengeId();
    jdbc.update(
        "UPDATE auth_challenge SET created_at=now()-interval '11 minutes',expires_at=now()-interval '1 minute' WHERE id=?",
        id);
    byte[] synthetic = new byte[32];
    new SecureRandom().nextBytes(synthetic);
    jdbc.update(
        "INSERT INTO csrf_session(id_hash,token_hash,created_at,expires_at) VALUES (?,?,now()-interval '2 hours',now()-interval '1 hour')",
        synthetic,
        synthetic);
    jdbc.update(
        "INSERT INTO auth_rate_limit(scope,subject_hmac,key_version,window_start,count,expires_at) VALUES ('synthetic-expired',?,'test-current',now()-interval '2 hours',1,now()-interval '1 hour')",
        synthetic);
    cleanup.clean();
    assertThat(count("SELECT count(*) FROM auth_challenge WHERE id=?", id)).isZero();
    assertThat(count("SELECT count(*) FROM csrf_session WHERE id_hash=?", synthetic)).isZero();
    assertThat(count("SELECT count(*) FROM auth_rate_limit WHERE scope='synthetic-expired'"))
        .isZero();
  }

  @Test
  void issuanceHourlyLimitReturnsDummyWithoutReplacingLastRealChallenge() {
    String email = email();
    signup.register(new RegisterMemberRequest(email, password()));
    String last = null;
    MailMessage message = null;
    for (int i = 0; i < 5; i++) {
      expireInterval(email);
      last = signup.requestVerification(email).challengeId();
      message = sent.remove();
    }
    expireInterval(email);
    var limited = signup.requestVerification(email);
    assertThat(limited.challengeId().length() == 43).isTrue();
    assertThat(sent).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT member_id IS NULL AND code_hmac IS NULL FROM auth_challenge WHERE id=?",
                Boolean.class,
                limited.challengeId()))
        .isTrue();
    signup.verify(new VerifyEmailRequest(email, last, message.code(), password()));
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM member WHERE email_normalized=?", String.class, email))
        .isEqualTo("ACTIVE");
    String unknown = email();
    for (int i = 0; i < 6; i++) {
      expireInterval(unknown);
      signup.requestVerification(unknown);
    }
    assertThat(sent).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(count) FROM auth_rate_limit WHERE scope='signup:issue:email' AND subject_hmac=?",
                Long.class,
                keys.currentDigest("signup:issue:email", unknown)))
        .isEqualTo(6);
  }

  @Test
  void blockedAndRealTargetsShareFiveRegistrationQuotaAndGenericResponse() {
    String email = email();
    jdbc.update(
        "INSERT INTO withdrawn_email_block(email_hmac,key_version,blocked_until) VALUES (?,?,now()+interval '30 days')",
        keys.currentDigest("withdrawn-email", email),
        keys.currentVersion());
    String response = null;
    for (int i = 0; i < 5; i++)
      response = signup.register(new RegisterMemberRequest(email, password())).message();
    jdbc.update(
        "DELETE FROM withdrawn_email_block WHERE email_hmac=?",
        keys.currentDigest("withdrawn-email", email));
    assertThat(signup.register(new RegisterMemberRequest(email, password())).message())
        .isEqualTo(response);
    assertThat(count("SELECT count(*) FROM member WHERE email_normalized=?", email)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT sum(count) FROM auth_rate_limit WHERE scope='signup:email' AND subject_hmac=?",
                Long.class,
                keys.currentDigest("signup:email", email)))
        .isEqualTo(6);
  }

  @Test
  void simultaneousConfirmationsConsumeOnceAndOnlyWinnerSetsPassword() throws Exception {
    String email = email();
    signup.register(new RegisterMemberRequest(email, password()));
    String id = signup.requestVerification(email).challengeId();
    MailMessage message = sent.remove();
    List<String> candidates = List.of(password(), password());
    CountDownLatch start = new CountDownLatch(1);
    List<Boolean> outcomes = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
      for (String candidate : candidates)
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    signup.verify(new VerifyEmailRequest(email, id, message.code(), candidate));
                    return true;
                  } catch (BusinessException error) {
                    if (error.errorCode() != ErrorCode.INVALID_AUTH_CHALLENGE) throw error;
                    return false;
                  }
                }));
      start.countDown();
      for (var result : results) outcomes.add(result.get(30, TimeUnit.SECONDS));
    }
    assertThat(outcomes.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
    String hash = hash(email);
    for (int i = 0; i < 2; i++)
      assertThat(encoder.matches(candidates.get(i), hash)).isEqualTo(outcomes.get(i));
    assertThat(
            jdbc.queryForObject(
                "SELECT consumed_at IS NOT NULL FROM auth_challenge WHERE id=?", Boolean.class, id))
        .isTrue();
  }

  @Test
  void failedChallengeConsumptionRollsBackActivationHashAndAttemptTogether() {
    String email = email(), original = password(), replacement = password();
    signup.register(new RegisterMemberRequest(email, original));
    String id = signup.requestVerification(email).challengeId();
    MailMessage message = sent.remove();
    String before = hash(email);
    jdbc.execute(
        "CREATE FUNCTION synthetic_consume_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.consumed_at IS NOT NULL THEN RAISE EXCEPTION 'synthetic private consume detail'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER synthetic_consume_failure BEFORE UPDATE ON auth_challenge FOR EACH ROW EXECUTE FUNCTION synthetic_consume_failure()");
    try {
      assertThatThrownBy(
              () -> signup.verify(new VerifyEmailRequest(email, id, message.code(), replacement)))
          .isInstanceOfSatisfying(
              BusinessException.class,
              error -> {
                assertThat(error.errorCode()).isEqualTo(ErrorCode.AUTH_UNAVAILABLE);
                assertThat(error.getCause()).isNull();
              });
      assertThat(hash(email).equals(before)).isTrue();
      assertThat(
              jdbc.queryForObject(
                  "SELECT state FROM member WHERE email_normalized=?", String.class, email))
          .isEqualTo("PENDING_VERIFICATION");
      assertThat(
              jdbc.queryForObject(
                  "SELECT consumed_at IS NULL AND attempts=0 FROM auth_challenge WHERE id=?",
                  Boolean.class,
                  id))
          .isTrue();
    } finally {
      jdbc.execute("DROP TRIGGER synthetic_consume_failure ON auth_challenge");
      jdbc.execute("DROP FUNCTION synthetic_consume_failure()");
    }
    signup.verify(new VerifyEmailRequest(email, id, message.code(), replacement));
    assertThat(encoder.matches(replacement, hash(email))).isTrue();
  }

  @Test
  void sharedSchemaEnforcesSessionTokenAndRetentionConstraints() {
    String email = email();
    signup.register(new RegisterMemberRequest(email, password()));
    Long member =
        jdbc.queryForObject("SELECT id FROM member WHERE email_normalized=?", Long.class, email);
    UUID family = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO refresh_session(id,member_id,auth_version,created_at,expires_at) VALUES (?,?,0,now(),now()+interval '168 hours')",
        family,
        member);
    byte[] one = new byte[32], two = new byte[32];
    new SecureRandom().nextBytes(one);
    new SecureRandom().nextBytes(two);
    jdbc.update(
        "INSERT INTO refresh_token(token_hash,session_id,created_at) VALUES (?,?,now())",
        one,
        family);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO refresh_token(token_hash,session_id,created_at) VALUES (?,?,now())",
                    two,
                    family))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO refresh_session(id,member_id,auth_version,created_at,expires_at) VALUES (?,?,0,now(),now()+interval '169 hours')",
                    UUID.randomUUID(),
                    member))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO csrf_session(id_hash,token_hash,created_at,expires_at) VALUES (?,?,now(),now()+interval '2 hours')",
                    two,
                    two))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO organization(name,primary_member_id) VALUES ('Synthetic',?)",
                    member))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    Long event = jdbc.queryForObject("SELECT min(id) FROM event", Long.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO reuse_archive(event_id,category,reused_qty,reused_volume_cm3) VALUES (?,'UNKNOWN',0,0)",
                    event))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO reuse_archive(event_id,category,reused_qty,reused_volume_cm3) VALUES (?,'DISPLAY_RACK',-1,0)",
                    event))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO member(state,created_at,withdrawn_at,purge_after) VALUES ('WITHDRAWN',now(),now(),now()+interval '719 hours')"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(count("SELECT count(*) FROM reservation WHERE retention_until IS NOT NULL"))
        .isZero();
  }

  @Test
  void signupCannotConsumeDifferentPurposeAndFailureAttemptPersists() {
    String email = email();
    signup.register(new RegisterMemberRequest(email, password()));
    String id = signup.requestVerification(email).challengeId();
    MailMessage message = sent.remove();
    jdbc.update("UPDATE auth_challenge SET purpose='PASSWORD_RESET' WHERE id=?", id);
    assertChallengeFailure(
        () -> signup.verify(new VerifyEmailRequest(email, id, message.code(), password())));
    assertThat(
            jdbc.queryForObject(
                "SELECT attempts=1 AND consumed_at IS NULL FROM auth_challenge WHERE id=?",
                Boolean.class,
                id))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM member WHERE email_normalized=?", String.class, email))
        .isEqualTo("PENDING_VERIFICATION");
  }

  private void expireInterval(String email) {
    for (String version : keys.versions()) {
      jdbc.update(
          "DELETE FROM auth_rate_limit WHERE scope='signup:issue:email:interval' AND key_version=? AND subject_hmac=?",
          version,
          keys.digest(version, "signup:issue:email", email));
    }
  }

  private String hash(String email) {
    return jdbc.queryForObject(
        "SELECT password_hash FROM member WHERE email_normalized=?", String.class, email);
  }

  private long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  private static String email() {
    return UUID.randomUUID() + "@example.test";
  }

  private static String password() {
    return UUID.randomUUID().toString();
  }

  private static String wrong(String code) {
    return code.equals("000000") ? "000001" : "000000";
  }

  private static void assertChallengeFailure(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.INVALID_AUTH_CHALLENGE));
  }
}
