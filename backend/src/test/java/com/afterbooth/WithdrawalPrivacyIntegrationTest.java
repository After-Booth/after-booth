package com.afterbooth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.afterbooth.global.domain.BoundingBox;
import com.afterbooth.global.security.AuthenticatedMember;
import com.afterbooth.global.security.CsrfSessionService;
import com.afterbooth.global.security.JwtService;
import com.afterbooth.intake.domain.DraftCategory;
import com.afterbooth.intake.pipeline.EstimatedLine;
import com.afterbooth.intake.pipeline.IntakePipeline;
import com.afterbooth.intake.service.IntakeService;
import com.afterbooth.inventory.domain.Condition;
import com.afterbooth.inventory.domain.ItemCategory;
import com.afterbooth.member.dto.LoginMemberRequest;
import com.afterbooth.member.dto.WithdrawMemberRequest;
import com.afterbooth.member.service.SessionService;
import com.afterbooth.member.service.WithdrawalService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"spring.docker.compose.enabled=false", "afterbooth.ai.mode=replay"})
@Import(TestcontainersConfiguration.class)
class WithdrawalPrivacyIntegrationTest {
  @Autowired WithdrawalService withdrawals;
  @Autowired SessionService sessions;
  @Autowired JwtService jwt;
  @Autowired PasswordEncoder encoder;
  @Autowired JdbcTemplate jdbc;
  @Autowired Clock clock;
  @Autowired CsrfSessionService csrf;
  @Autowired IntakeService intake;

  @MockitoBean(name = "intakeExecutor")
  Executor executor;

  @MockitoBean IntakePipeline pipeline;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    SyntheticAuthSupport.configure(registry);
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    registry.add("afterbooth.auth.hmac.current-version", () -> "withdrawal-privacy");
    registry.add(
        "afterbooth.auth.hmac.keys.withdrawal-privacy",
        () -> Base64.getEncoder().encodeToString(key));
  }

  private Account account() {
    String email = UUID.randomUUID() + "@example.invalid";
    String password = UUID.randomUUID().toString();
    String marker = UUID.randomUUID().toString();
    long member =
        jdbc.queryForObject(
            "insert into member(email_normalized,password_hash,state,created_at,verified_at) values(?,?,'ACTIVE',statement_timestamp(),statement_timestamp()) returning id",
            Long.class,
            email,
            encoder.encode(password));
    long org =
        jdbc.queryForObject(
            "insert into organization(name,primary_member_id) values(?,?) returning id",
            Long.class,
            marker,
            member);
    long event =
        jdbc.queryForObject(
            "insert into event(organization_id,name,venue,teardown_deadline) values(?,?,?,clock_timestamp()+interval '1 day') returning id",
            Long.class,
            org,
            marker,
            marker);
    var login = sessions.login(new LoginMemberRequest(email, password), null);
    return new Account(
        member,
        org,
        event,
        password,
        marker,
        jwt.verify(login.response().accessToken(), clock.instant()));
  }

  private long draft(Account account) {
    return jdbc.queryForObject(
        "insert into intake_draft(event_id,status,stage,failure_message,created_at) values(?,'FAILED','ESTIMATE',?,clock_timestamp()) returning id",
        Long.class,
        account.event,
        account.marker);
  }

  private long request(Account account) {
    return jdbc.queryForObject(
        "insert into pickup_request(organization_id,category,quantity,vehicle_type,purpose,transcript,created_at) values(?,'STORAGE_BIN',1,'VAN',?,?,clock_timestamp()) returning id",
        Long.class,
        account.org,
        account.marker,
        account.marker);
  }

  private long item(Account account, long draft) {
    return jdbc.queryForObject(
        "insert into item(event_id,organization_id,intake_draft_id,category,label,width_cm,depth_cm,height_cm,condition,total_qty,available_qty,pickup_deadline,conditions_transcript) values(?,?,?,'STORAGE_BIN',?,10,10,6,'GOOD',1,0,clock_timestamp()+interval '1 day',?) returning id",
        Long.class,
        account.event,
        account.org,
        draft,
        account.marker,
        account.marker);
  }

  private void challenge(Account account, String purpose) {
    byte[] digest = new byte[32];
    new SecureRandom().nextBytes(digest);
    jdbc.update(
        "insert into auth_challenge(id,member_id,email_key,key_version,purpose,code_hmac,created_at,expires_at) values(?,?,?,'withdrawal-privacy',?,?,statement_timestamp(),statement_timestamp()+interval '10 minutes')",
        UUID.randomUUID().toString(),
        account.member,
        digest,
        purpose,
        digest);
  }

  private UUID snapshot(long actor, long org, String marker) {
    UUID key = UUID.randomUUID();
    jdbc.update(
        "insert into idempotency_record(idempotency_key,fingerprint,state,response_status,response_body,created_at,actor_member_id,related_organization_ids) values(?,?,'DONE',200,jsonb_build_object('value',?::text),clock_timestamp(),?,ARRAY[?]::bigint[])",
        key,
        marker,
        marker,
        actor,
        org);
    return key;
  }

  @Test
  void completeImmediateScopeIsErasedAndUnrelatedOriginalsAndAnonymousCsrfSurvive() {
    Account owner = account(), other = account();
    long ownDraft = draft(owner), otherDraft = draft(other);
    long ownItem = item(owner, ownDraft), otherItem = item(other, otherDraft);
    long ownRequest = request(owner), otherRequest = request(other);
    jdbc.update(
        "insert into intake_draft_line(draft_id,category,label,count,boxes,width_cm,depth_cm,height_cm,condition,confidence,needs_review) values(?,'STORAGE_BIN',?,1,'[{\"x\":0,\"y\":0,\"width\":0.2,\"height\":0.2}]',10,10,6,'GOOD',1,false)",
        ownDraft,
        owner.marker);
    jdbc.update(
        "insert into intake_draft_line(draft_id,category,label,count,boxes,condition,confidence,needs_review) values(?,'UNKNOWN',?,1,'[]','GOOD',1,true)",
        ownDraft,
        owner.marker);
    long reservation =
        jdbc.queryForObject(
            "insert into reservation(request_id,item_id,organization_id,quantity,handed_over_qty,status,hold_expires_at,pickup_deadline,created_at) values(?,?,?,1,1,'HANDED_OVER',statement_timestamp(),statement_timestamp()+interval '1 day',statement_timestamp()) returning id",
            Long.class,
            otherRequest,
            ownItem,
            other.org);
    jdbc.update(
        "insert into handover_log(reservation_id,expected_qty,counted_qty,accepted_qty,status,boxes,created_at) values(?,1,1,1,'COMPLETED','[{\"x\":0,\"y\":0,\"width\":0.2,\"height\":0.2}]',clock_timestamp())",
        reservation);
    challenge(owner, "SIGNUP_VERIFY");
    challenge(owner, "PASSWORD_RESET");
    challenge(other, "PASSWORD_RESET");
    csrf.issue(owner.member, null);
    csrf.issue(other.member, null);
    csrf.issue();
    int otherCsrfBefore =
        jdbc.queryForObject(
            "select count(*) from csrf_session where member_id=?", Integer.class, other.member);
    int anonymousBefore =
        jdbc.queryForObject(
            "select count(*) from csrf_session where member_id is null", Integer.class);
    UUID ownCopy = snapshot(owner.member, owner.org, owner.marker);
    UUID counterpartyCopy = snapshot(other.member, owner.org, owner.marker);
    UUID unaffectedCopy = snapshot(other.member, other.org, other.marker);
    withdrawals.withdraw(owner.principal, new WithdrawMemberRequest(owner.password));
    assertThat(
            jdbc.queryForObject(
                "select state='WITHDRAWN' and email_normalized is null and password_hash is null and auth_version=1 and withdrawn_at is not null and purge_after=withdrawn_at+interval '720 hours' from member where id=?",
                Boolean.class,
                owner.member))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from auth_challenge where member_id=?",
                Integer.class,
                owner.member))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from refresh_session where member_id=? and revoked_at is null",
                Integer.class,
                owner.member))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from csrf_session where member_id=?", Integer.class, owner.member))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from csrf_session where member_id is null", Integer.class))
        .isEqualTo(anonymousBefore);
    assertThat(
            jdbc.queryForObject(
                "select purpose is null and transcript is null from pickup_request where id=?",
                Boolean.class,
                ownRequest))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select conditions_transcript is null and label=? from item where id=?",
                Boolean.class,
                ItemCategory.STORAGE_BIN.label(),
                ownItem))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select failure_message is null from intake_draft where id=?",
                Boolean.class,
                ownDraft))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select bool_and(boxes='[]'::jsonb and ((category='STORAGE_BIN' and label=?) or (category='UNKNOWN' and label=?))) from intake_draft_line where draft_id=?",
                Boolean.class,
                ItemCategory.STORAGE_BIN.label(),
                DraftCategory.UNKNOWN.label(),
                ownDraft))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select name<>? and venue<>? from event where id=?",
                Boolean.class,
                owner.marker,
                owner.marker,
                owner.event))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select name<>? from organization where id=?",
                Boolean.class,
                owner.marker,
                owner.org))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select boxes='[]'::jsonb from handover_log where reservation_id=?",
                Boolean.class,
                reservation))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select retention_until is not null from reservation where id=?",
                Boolean.class,
                reservation))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from idempotency_record where idempotency_key in (?,?)",
                Integer.class,
                ownCopy,
                counterpartyCopy))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from idempotency_record where idempotency_key=?",
                Integer.class,
                unaffectedCopy))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select label=? and conditions_transcript=? from item where id=?",
                Boolean.class,
                other.marker,
                other.marker,
                otherItem))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select purpose=? and transcript=? from pickup_request where id=?",
                Boolean.class,
                other.marker,
                other.marker,
                otherRequest))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select name=? and venue=? from event where id=?",
                Boolean.class,
                other.marker,
                other.marker,
                other.event))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select failure_message=? from intake_draft where id=?",
                Boolean.class,
                other.marker,
                otherDraft))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from auth_challenge where member_id=?",
                Integer.class,
                other.member))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from refresh_session where member_id=? and revoked_at is null",
                Integer.class,
                other.member))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from csrf_session where member_id=?", Integer.class, other.member))
        .isEqualTo(otherCsrfBefore);
  }

  private long upload(Account owner) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(owner.principal, null, List.of()));
    try {
      return intake.create(owner.event, bytes.toByteArray(), "image/png").id();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private List<Runnable> queue() {
    List<Runnable> queued = new CopyOnWriteArrayList<>();
    doAnswer(
            call -> {
              queued.add(call.getArgument(0));
              return null;
            })
        .when(executor)
        .execute(any());
    return queued;
  }

  @Test
  void queuedActualIntakeIsDiscardedAndCannotStoreAfterWithdrawal() throws Exception {
    Account owner = account();
    var queued = queue();
    long draft = upload(owner);
    withdrawals.withdraw(owner.principal, new WithdrawMemberRequest(owner.password));
    queued.forEach(Runnable::run);
    verify(pipeline, never()).estimate(any());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from intake_draft_line where draft_id=?", Integer.class, draft))
        .isZero();
  }

  @Test
  void failedWithdrawalPreservesQueuedActualIntakeAndItsFinalWrites() throws Exception {
    Account owner = account();
    var queued = queue();
    long draft = upload(owner);
    when(pipeline.estimate(any())).thenReturn(List.of());
    assertThatThrownBy(
            () ->
                withdrawals.withdraw(
                    owner.principal, new WithdrawMemberRequest(UUID.randomUUID().toString())))
        .isInstanceOf(com.afterbooth.global.error.BusinessException.class);
    queued.forEach(Runnable::run);
    assertThat(
            jdbc.queryForObject(
                "select status='READY' from intake_draft where id=?", Boolean.class, draft))
        .isTrue();
  }

  @Test
  void activeActualIntakeDrainsBeforeWithdrawalAndFinalStoredDetailsAreScrubbed() throws Exception {
    Account owner = account();
    var queued = queue();
    long draft = upload(owner);
    CountDownLatch estimating = new CountDownLatch(1), release = new CountDownLatch(1);
    var line =
        new EstimatedLine(
            DraftCategory.STORAGE_BIN,
            1,
            List.of(new BoundingBox(0, 0, 0.2, 0.2)),
            10,
            10,
            6,
            Condition.GOOD,
            BigDecimal.ONE,
            false);
    when(pipeline.estimate(any()))
        .thenAnswer(
            call -> {
              estimating.countDown();
              if (!release.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("Synthetic timeout");
              return List.of(line);
            });
    when(pipeline.segment(any(), any())).thenReturn(List.of(line));
    try (var workers = Executors.newFixedThreadPool(2)) {
      var processing = workers.submit(queued.getFirst());
      assertThat(estimating.await(5, TimeUnit.SECONDS)).isTrue();
      var withdrawal =
          workers.submit(
              () ->
                  withdrawals.withdraw(owner.principal, new WithdrawMemberRequest(owner.password)));
      try {
        assertThatThrownBy(() -> withdrawal.get(100, TimeUnit.MILLISECONDS))
            .isInstanceOf(java.util.concurrent.TimeoutException.class);
      } finally {
        release.countDown();
      }
      processing.get(10, TimeUnit.SECONDS);
      withdrawal.get(10, TimeUnit.SECONDS);
    }
    assertThat(
            jdbc.queryForObject(
                "select state='WITHDRAWN' from member where id=?", Boolean.class, owner.member))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select bool_and(boxes='[]'::jsonb and label=?) from intake_draft_line where draft_id=?",
                Boolean.class,
                ItemCategory.STORAGE_BIN.label(),
                draft))
        .isTrue();
  }

  private record Account(
      long member,
      long org,
      long event,
      String password,
      String marker,
      AuthenticatedMember principal) {
    @Override
    public String toString() {
      return "Account[redacted]";
    }
  }
}
