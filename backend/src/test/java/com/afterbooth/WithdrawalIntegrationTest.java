package com.afterbooth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.security.AuthenticatedMember;
import com.afterbooth.global.security.JwtService;
import com.afterbooth.member.dto.LoginMemberRequest;
import com.afterbooth.member.dto.WithdrawMemberRequest;
import com.afterbooth.member.service.SessionService;
import com.afterbooth.member.service.WithdrawalService;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    properties = {
      "spring.docker.compose.enabled=false",
      "afterbooth.auth.allowed-origin=https://client.example.invalid"
    })
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc(
    print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Import(TestcontainersConfiguration.class)
class WithdrawalIntegrationTest {
  @Autowired WithdrawalService withdrawals;
  @Autowired SessionService sessions;
  @Autowired PasswordEncoder encoder;
  @Autowired com.afterbooth.member.repository.MemberRepository members;
  @Autowired com.afterbooth.member.service.SignupService signup;
  @Autowired com.afterbooth.global.security.HmacKeyRing hmac;
  @Autowired com.afterbooth.global.security.AuthSecurityProperties authProperties;
  @Autowired com.afterbooth.global.security.AuthRateLimiter limits;
  @Autowired com.afterbooth.member.mail.MailGateway mail;
  @Autowired com.afterbooth.global.security.MemberWorkRegistry work;
  @Autowired com.afterbooth.global.idempotency.IdempotencyService idempotency;
  @Autowired JdbcTemplate jdbc;
  @Autowired JwtService jwt;
  @Autowired Clock clock;
  @Autowired com.afterbooth.global.security.DomainAccess access;
  @Autowired com.afterbooth.member.service.PasswordService passwords;
  @Autowired com.afterbooth.allocation.service.ReservationService reservations;
  @Autowired com.afterbooth.handover.service.HandoverCompletionService handovers;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  com.afterbooth.member.repository.WithdrawalRepository withdrawalRepository;

  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @Autowired com.afterbooth.global.security.CsrfSessionService csrf;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    SyntheticAuthSupport.configure(registry);
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    registry.add("afterbooth.auth.hmac.current-version", () -> "withdrawal-test");
    registry.add(
        "afterbooth.auth.hmac.keys.withdrawal-test", () -> Base64.getEncoder().encodeToString(key));
  }

  private Account account() {
    String email = UUID.randomUUID() + "@example.invalid", password = UUID.randomUUID().toString();
    long id =
        jdbc.queryForObject(
            "insert into member(email_normalized,password_hash,state,created_at,verified_at) values(?,?,'ACTIVE',clock_timestamp(),clock_timestamp()) returning id",
            Long.class,
            email,
            encoder.encode(password));
    long org =
        jdbc.queryForObject(
            "insert into organization(name,primary_member_id) values('Synthetic member',?) returning id",
            Long.class,
            id);
    var issued = sessions.login(new LoginMemberRequest(email, password), null);
    return new Account(
        id,
        org,
        email,
        password,
        jwt.verify(issued.response().accessToken(), clock.instant()),
        issued.response().accessToken(),
        issued.refresh());
  }

  @Test
  void withdrawalErasesCredentialsRevokesSessionsAndPreservesOtherMember() {
    var a = account();
    var b = account();
    withdrawals.withdraw(a.principal(), new WithdrawMemberRequest(a.password()));
    assertThat(
            jdbc.queryForObject(
                "select state='WITHDRAWN' and email_normalized is null and password_hash is null and auth_version=1 and purge_after=withdrawn_at+interval '720 hours' from member where id=?",
                Boolean.class,
                a.id()))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from refresh_session where member_id=? and revoked_at is null",
                Integer.class,
                a.id()))
        .isZero();
    assertThat(jdbc.queryForObject("select state from member where id=?", String.class, b.id()))
        .isEqualTo("ACTIVE");
    assertThatThrownBy(() -> jwt.verifyPrincipal(a.principal(), clock.instant()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    assertThatThrownBy(
            () -> withdrawals.withdraw(a.principal(), new WithdrawMemberRequest(a.password())))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    assertThatThrownBy(() -> sessions.login(new LoginMemberRequest(a.email(), a.password()), null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
  }

  @Test
  void incorrectPasswordLeavesMemberAndSessionsUnchanged() {
    var a = account();
    assertThatThrownBy(
            () ->
                withdrawals.withdraw(
                    a.principal(), new WithdrawMemberRequest(UUID.randomUUID().toString())))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    assertThat(
            jdbc.queryForObject(
                "select state='ACTIVE' and auth_version=0 and email_normalized is not null from member where id=?",
                Boolean.class,
                a.id()))
        .isTrue();
    jwt.verifyPrincipal(a.principal(), clock.instant());
  }

  @Test
  void pendingMemberWithdrawalIsRejectedWithoutSideEffects() {
    String email = UUID.randomUUID() + "@example.invalid";
    String password = UUID.randomUUID().toString();
    long id =
        jdbc.queryForObject(
            "insert into member(email_normalized,password_hash,state,created_at) values(?,?,'PENDING_VERIFICATION',clock_timestamp()) returning id",
            Long.class,
            email,
            encoder.encode(password));
    var principal = new AuthenticatedMember(id, UUID.randomUUID(), 0);
    assertThatThrownBy(() -> withdrawals.withdraw(principal, new WithdrawMemberRequest(password)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    assertThat(jdbc.queryForObject("select state from member where id=?", String.class, id))
        .isEqualTo("PENDING_VERIFICATION");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from withdrawn_email_block where key_version=? and email_hmac=?",
                Integer.class,
                hmac.currentVersion(),
                hmac.currentDigest("withdrawn-email", email)))
        .isZero();
  }

  @Test
  void stalePrincipalAfterPasswordChangeCannotWithdrawButFreshLoginCan() {
    var a = account();
    String next = UUID.randomUUID().toString();
    passwords.change(
        a.principal(), new com.afterbooth.member.dto.ChangePasswordRequest(a.password(), next));
    assertThatThrownBy(() -> withdrawals.withdraw(a.principal(), new WithdrawMemberRequest(next)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    var fresh = sessions.login(new LoginMemberRequest(a.email(), next), null);
    withdrawals.withdraw(
        jwt.verify(fresh.response().accessToken(), clock.instant()),
        new WithdrawMemberRequest(next));
    assertThat(jdbc.queryForObject("select state from member where id=?", String.class, a.id()))
        .isEqualTo("WITHDRAWN");
  }

  @Test
  void giverAndReceiverMatrixUsesActualReservationStateIncludingExpiredHeld() {
    for (String state : new String[] {"HELD", "CONFIRMED", "HANDED_OVER", "RELEASED", "EXPIRED"}) {
      for (boolean giver : new boolean[] {true, false}) {
        var a = account();
        var b = account();
        long event =
            jdbc.queryForObject(
                "insert into event(organization_id,name,venue,teardown_deadline) values(?,'Synthetic event','Synthetic venue',clock_timestamp()+interval '1 day') returning id",
                Long.class,
                a.org());
        long item =
            jdbc.queryForObject(
                "insert into item(event_id,organization_id,category,label,width_cm,depth_cm,height_cm,condition,total_qty,available_qty,pickup_deadline) values(?,?,'STORAGE_BIN','Synthetic label',10,10,6,'GOOD',5,4,clock_timestamp()+interval '1 day') returning id",
                Long.class,
                event,
                a.org());
        long request =
            jdbc.queryForObject(
                "insert into pickup_request(organization_id,category,quantity,vehicle_type,purpose,transcript,created_at) values(?,'STORAGE_BIN',5,'VAN','Synthetic purpose','Synthetic transcript',clock_timestamp()) returning id",
                Long.class,
                b.org());
        jdbc.update(
            "insert into reservation(request_id,item_id,organization_id,quantity,handed_over_qty,status,hold_expires_at,pickup_deadline,created_at) values(?,?,?,1,?,?,clock_timestamp()-interval '1 hour',clock_timestamp()+interval '1 day',clock_timestamp()-interval '2 hours')",
            request,
            item,
            b.org(),
            state.equals("HANDED_OVER") ? 1 : null,
            state);
        long historyReservation =
            jdbc.queryForObject("select id from reservation where item_id=?", Long.class, item);
        jdbc.update(
            "insert into handover_log(reservation_id,expected_qty,counted_qty,status,boxes,created_at) values(?,1,0,'MISMATCH','[]',clock_timestamp())",
            historyReservation);
        if (state.equals("HANDED_OVER"))
          jdbc.update(
              "insert into handover_log(reservation_id,expected_qty,counted_qty,accepted_qty,status,boxes,created_at) values(?,1,1,1,'COMPLETED','[{\"x\":0,\"y\":0,\"width\":1,\"height\":1}]',clock_timestamp())",
              historyReservation);
        var target = giver ? a : b;
        if (state.equals("HELD") || state.equals("CONFIRMED")) {
          assertThatThrownBy(
                  () ->
                      withdrawals.withdraw(
                          target.principal(), new WithdrawMemberRequest(target.password())))
              .isInstanceOfSatisfying(
                  BusinessException.class,
                  e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.WITHDRAWAL_BLOCKED));
        } else {
          withdrawals.withdraw(target.principal(), new WithdrawMemberRequest(target.password()));
          assertThat(
                  jdbc.queryForObject(
                      "select retention_until is not null from reservation where item_id=?",
                      Boolean.class,
                      item))
              .isTrue();
          assertThat(
                  jdbc.queryForObject(
                      "select conditions_transcript is null from item where id=?",
                      Boolean.class,
                      item))
              .isTrue();
        }
      }
    }
  }

  @Test
  void httpWithdrawalValidatesOriginCsrfUnknownFieldsAndInvalidatesExistingBearer()
      throws Exception {
    var a = account();
    var token = csrf.issue(a.id(), null);
    String input =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .writeValueAsString(java.util.Map.of("password", a.password()));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/api/members/me")
                .header("Authorization", "Bearer " + a.token())
                .contentType("application/json")
                .content(input))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/api/members/me")
                .header("Authorization", "Bearer " + a.token())
                .header("Origin", "https://client.example.invalid")
                .header("X-CSRF-Token", token.csrfToken())
                .cookie(
                    new jakarta.servlet.http.Cookie(
                        com.afterbooth.global.security.CsrfSessionService.COOKIE,
                        token.sessionId()))
                .contentType("application/json")
                .content(input))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isNoContent())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Cache-Control", "no-store"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie()
                .maxAge("__Host-afterbooth-refresh", 0))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie()
                .maxAge(com.afterbooth.global.security.CsrfSessionService.COOKIE, 0));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/members/me")
                .header("Authorization", "Bearer " + a.token()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isUnauthorized());
  }

  @Test
  void committedWithdrawalWinsAgainstWaitingLoginRefreshAndPasswordChange() throws Exception {
    for (String operation : java.util.List.of("login", "refresh", "password", "reset")) {
      var a = account();
      var reset = resetChallenge(a);
      var locked = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      org.mockito.Mockito.doAnswer(
              call -> {
                if ((long) call.getArgument(0) == a.id()) {
                  locked.countDown();
                  if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("Synthetic withdrawal gate timed out");
                }
                return call.callRealMethod();
              })
          .when(withdrawalRepository)
          .erase(
              org.mockito.ArgumentMatchers.anyLong(),
              org.mockito.ArgumentMatchers.anyString(),
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any());
      try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
        var withdrawal =
            executor.submit(
                () -> withdrawals.withdraw(a.principal(), new WithdrawMemberRequest(a.password())));
        assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var competitor =
            executor.submit(
                () -> {
                  try {
                    switch (operation) {
                      case "login" ->
                          sessions.login(new LoginMemberRequest(a.email(), a.password()), null);
                      case "refresh" -> {
                        if (sessions.refresh(a.refresh(), null) == null)
                          return ErrorCode.UNAUTHENTICATED;
                      }
                      case "reset" -> passwords.reset(reset);
                      default ->
                          passwords.change(
                              a.principal(),
                              new com.afterbooth.member.dto.ChangePasswordRequest(
                                  a.password(), UUID.randomUUID().toString()));
                    }
                    return null;
                  } catch (BusinessException error) {
                    return error.errorCode();
                  }
                });
        try {
          awaitDatabaseLockWait();
        } finally {
          release.countDown();
        }
        withdrawal.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(competitor.get(10, java.util.concurrent.TimeUnit.SECONDS))
            .as(operation)
            .isEqualTo(
                operation.equals("reset")
                    ? ErrorCode.INVALID_AUTH_CHALLENGE
                    : ErrorCode.UNAUTHENTICATED);
        assertThat(
                jdbc.queryForObject(
                    "select count(*) from refresh_session where member_id=? and revoked_at is null",
                    Integer.class,
                    a.id()))
            .isZero();
      } finally {
        release.countDown();
        org.mockito.Mockito.reset(withdrawalRepository);
      }
    }
  }

  private com.afterbooth.member.dto.ResetPasswordRequest resetChallenge(Account account) {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    String code = String.format(java.util.Locale.ROOT, "%06d", new SecureRandom().nextInt(1000000));
    new org.springframework.transaction.support.TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              members.lockEmail(account.email());
              var now = members.databaseNow();
              members.insertChallenge(
                  id,
                  account.id(),
                  hmac.currentDigest("challenge-email:PASSWORD_RESET", account.email()),
                  hmac.currentVersion(),
                  "PASSWORD_RESET",
                  hmac.currentDigest(
                      "challenge-code", id + "\0PASSWORD_RESET\0" + account.email() + "\0" + code),
                  now);
            });
    return new com.afterbooth.member.dto.ResetPasswordRequest(
        account.email(), id, code, UUID.randomUUID().toString());
  }

  private void awaitDatabaseLockWait() throws Exception {
    long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
    while (System.nanoTime() < until) {
      if (Boolean.TRUE.equals(
          jdbc.queryForObject(
              "select exists(select 1 from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and wait_event_type='Lock')",
              Boolean.class))) return;
      Thread.sleep(10);
    }
    throw new AssertionError("Synthetic concurrent operation did not reach a PostgreSQL lock wait");
  }

  @Test
  void failureDuringImmediateEraseRollsBackMemberSessionsAndBlock() {
    var a = account();
    org.mockito.Mockito.doAnswer(
            call -> {
              call.callRealMethod();
              throw new org.springframework.dao.DataAccessResourceFailureException(
                  "Synthetic failure");
            })
        .when(withdrawalRepository)
        .erase(
            org.mockito.ArgumentMatchers.eq(a.id()),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    try {
      assertThatThrownBy(
              () -> withdrawals.withdraw(a.principal(), new WithdrawMemberRequest(a.password())))
          .isInstanceOfSatisfying(
              BusinessException.class,
              error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.AUTH_UNAVAILABLE));
      assertThat(
              jdbc.queryForObject(
                  "select state='ACTIVE' and auth_version=0 and purge_after is null and email_normalized is not null and password_hash is not null from member where id=?",
                  Boolean.class,
                  a.id()))
          .isTrue();
      jwt.verifyPrincipal(a.principal(), clock.instant());
    } finally {
      org.mockito.Mockito.reset(withdrawalRepository);
    }
  }

  private Trade trade() {
    var giver = account();
    var receiver = account();
    long event =
        jdbc.queryForObject(
            "insert into event(organization_id,name,venue,teardown_deadline) values(?,'Synthetic event','Synthetic venue',statement_timestamp()+interval '1 day') returning id",
            Long.class,
            giver.org());
    long item =
        jdbc.queryForObject(
            "insert into item(event_id,organization_id,category,label,width_cm,depth_cm,height_cm,condition,total_qty,available_qty,pickup_deadline) values(?,?,'STORAGE_BIN','Synthetic label',10,10,6,'GOOD',5,5,statement_timestamp()+interval '1 day') returning id",
            Long.class,
            event,
            giver.org());
    long request =
        jdbc.queryForObject(
            "insert into pickup_request(organization_id,category,quantity,vehicle_type,created_at) values(?,'STORAGE_BIN',5,'VAN',statement_timestamp()) returning id",
            Long.class,
            receiver.org());
    return new Trade(giver, receiver, item, request);
  }

  @Test
  void committedHoldConfirmAndHandoverSerializeWithWithdrawal() throws Exception {
    for (String operation : java.util.List.of("hold", "confirm", "handover")) {
      var trade = trade();
      Long existing = null;
      if (!operation.equals("hold")) {
        DomainAuthTestSupport.use(trade.receiver().principal());
        try {
          existing =
              reservations
                  .hold(
                      new com.afterbooth.allocation.dto.HoldItemRequest(
                          trade.request(), trade.item(), 1))
                  .id();
          if (operation.equals("handover"))
            assertThat(reservations.confirm(existing).error()).isNull();
        } finally {
          org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
      }
      Long reservationId = existing;
      var locked = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
        var writer =
            executor.submit(
                () -> {
                  DomainAuthTestSupport.use(
                      (operation.equals("handover") ? trade.giver() : trade.receiver())
                          .principal());
                  try {
                    new org.springframework.transaction.support.TransactionTemplate(manager)
                        .executeWithoutResult(
                            status -> {
                              switch (operation) {
                                case "hold" ->
                                    reservations.hold(
                                        new com.afterbooth.allocation.dto.HoldItemRequest(
                                            trade.request(), trade.item(), 1));
                                case "confirm" -> {
                                  if (reservations.confirm(reservationId).error() != null)
                                    throw new AssertionError("Synthetic confirmation rejected");
                                }
                                default ->
                                    handovers.record(
                                        reservationId,
                                        1,
                                        java.util.List.of(
                                            new com.afterbooth.global.domain.BoundingBox(
                                                0, 0, 1, 1)),
                                        true);
                              }
                              locked.countDown();
                              try {
                                if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                  throw new AssertionError("Synthetic trade gate timed out");
                              } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError("Synthetic trade interrupted");
                              }
                            });
                  } finally {
                    org.springframework.security.core.context.SecurityContextHolder.clearContext();
                  }
                });
        assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var withdrawal =
            executor.submit(
                () -> {
                  try {
                    withdrawals.withdraw(
                        trade.giver().principal(),
                        new WithdrawMemberRequest(trade.giver().password()));
                    return null;
                  } catch (BusinessException error) {
                    return error.errorCode();
                  }
                });
        try {
          awaitDatabaseLockWait();
        } finally {
          release.countDown();
        }
        writer.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(withdrawal.get(10, java.util.concurrent.TimeUnit.SECONDS))
            .isEqualTo(operation.equals("handover") ? null : ErrorCode.WITHDRAWAL_BLOCKED);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void simultaneousPartyWithdrawalsKeepEarlierDeadlineAndFinishWithoutDeadlock() throws Exception {
    var trade = trade();
    DomainAuthTestSupport.use(trade.receiver().principal());
    long reservation;
    try {
      reservation =
          reservations
              .hold(
                  new com.afterbooth.allocation.dto.HoldItemRequest(
                      trade.request(), trade.item(), 1))
              .id();
      reservations.cancel(reservation);
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> {
                start.await();
                withdrawals.withdraw(
                    trade.giver().principal(), new WithdrawMemberRequest(trade.giver().password()));
                return null;
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                withdrawals.withdraw(
                    trade.receiver().principal(),
                    new WithdrawMemberRequest(trade.receiver().password()));
                return null;
              });
      start.countDown();
      first.get(10, java.util.concurrent.TimeUnit.SECONDS);
      second.get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(
              jdbc.queryForObject(
                  "select r.retention_until=least(g.purge_after,v.purge_after) from reservation r join member g on g.id=? join member v on v.id=? where r.id=?",
                  Boolean.class,
                  trade.giver().id(),
                  trade.receiver().id(),
                  reservation))
          .isTrue();
    }
  }

  @Test
  void simultaneousSameMemberWithdrawalsSerializeToOneWinner() throws Exception {
    var a = account();
    var start = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.Callable<ErrorCode> attempt =
        () -> {
          start.await();
          try {
            withdrawals.withdraw(a.principal(), new WithdrawMemberRequest(a.password()));
            return null;
          } catch (BusinessException error) {
            return error.errorCode();
          }
        };
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(attempt);
      var second = executor.submit(attempt);
      start.countDown();
      assertThat(
              java.util.Arrays.asList(
                  first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                  second.get(10, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(null, ErrorCode.UNAUTHENTICATED);
    }
    assertThat(jdbc.queryForObject("select state from member where id=?", String.class, a.id()))
        .isEqualTo("WITHDRAWN");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from withdrawn_email_block where key_version=? and email_hmac=?",
                Integer.class,
                hmac.currentVersion(),
                hmac.currentDigest("withdrawn-email", a.email())))
        .isEqualTo(1);
  }

  @Test
  void committedWithdrawalPreventsWaitingNewHold() throws Exception {
    var trade = trade();
    var locked = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    org.mockito.Mockito.doAnswer(
            call -> {
              locked.countDown();
              if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw new AssertionError("Synthetic withdrawal gate timed out");
              return call.callRealMethod();
            })
        .when(withdrawalRepository)
        .erase(
            org.mockito.ArgumentMatchers.eq(trade.giver().id()),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var withdrawal =
          executor.submit(
              () ->
                  withdrawals.withdraw(
                      trade.giver().principal(),
                      new WithdrawMemberRequest(trade.giver().password())));
      assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var hold =
          executor.submit(
              () -> {
                DomainAuthTestSupport.use(trade.receiver().principal());
                try {
                  reservations.hold(
                      new com.afterbooth.allocation.dto.HoldItemRequest(
                          trade.request(), trade.item(), 1));
                  return null;
                } catch (BusinessException error) {
                  return error.errorCode();
                } finally {
                  org.springframework.security.core.context.SecurityContextHolder.clearContext();
                }
              });
      try {
        awaitDatabaseLockWait();
      } finally {
        release.countDown();
      }
      withdrawal.get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(hold.get(10, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo(ErrorCode.NOT_FOUND);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from reservation where item_id=?", Integer.class, trade.item()))
          .isZero();
    } finally {
      release.countDown();
      org.mockito.Mockito.reset(withdrawalRepository);
    }
  }

  @Test
  void oldKeyBlocksAcrossRotationRetirementFailsClosedAndExpiryAllowsFreshAccount() {
    var a = account();
    withdrawals.withdraw(a.principal(), new WithdrawMemberRequest(a.password()));
    byte[] digest = hmac.currentDigest("withdrawn-email", a.email());
    java.time.Instant deadline =
        jdbc.queryForObject(
                "select blocked_until from withdrawn_email_block where key_version=? and email_hmac=?",
                java.sql.Timestamp.class,
                hmac.currentVersion(),
                digest)
            .toInstant();
    assertThat(members.blocked(hmac.currentVersion(), digest, deadline.minusNanos(1000))).isTrue();
    assertThat(members.blocked(hmac.currentVersion(), digest, deadline)).isFalse();
    assertThat(members.blocked(hmac.currentVersion(), digest, deadline.plusNanos(1000))).isFalse();
    assertThat(
            jdbc.queryForList(
                "select column_name from information_schema.columns where table_name='withdrawn_email_block' order by 1",
                String.class))
        .containsExactly("blocked_until", "email_hmac", "key_version");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.table_constraints where table_name='withdrawn_email_block' and constraint_type='FOREIGN KEY'",
                Integer.class))
        .isZero();
    byte[] next = new byte[32];
    new SecureRandom().nextBytes(next);
    var rotatedKeys = new java.util.HashMap<>(authProperties.hmac().keys());
    rotatedKeys.put("next", Base64.getEncoder().encodeToString(next));
    var rotated =
        new com.afterbooth.global.security.HmacKeyRing(
            new com.afterbooth.global.security.AuthSecurityProperties(
                "https://client.example.invalid",
                new com.afterbooth.global.security.AuthSecurityProperties.Hmac(
                    "next", rotatedKeys, java.util.Map.of(hmac.currentVersion(), deadline))),
            clock);
    var rotatedSignup =
        new com.afterbooth.member.service.SignupService(
            members, encoder, limits, rotated, mail, Runnable::run, manager, work);
    rotatedSignup.register(
        new com.afterbooth.member.dto.RegisterMemberRequest(a.email(), a.password()));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from member where email_normalized=?", Integer.class, a.email()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select blocked_until=? from withdrawn_email_block where key_version=? and email_hmac=?",
                Boolean.class,
                java.sql.Timestamp.from(deadline),
                hmac.currentVersion(),
                digest))
        .isTrue();
    var retired =
        new com.afterbooth.global.security.HmacKeyRing(
            new com.afterbooth.global.security.AuthSecurityProperties(
                "https://client.example.invalid",
                new com.afterbooth.global.security.AuthSecurityProperties.Hmac(
                    "next", java.util.Map.of("next", rotatedKeys.get("next")), java.util.Map.of())),
            clock);
    var retiredSignup =
        new com.afterbooth.member.service.SignupService(
            members, encoder, limits, retired, mail, Runnable::run, manager, work);
    assertThatThrownBy(
            () ->
                retiredSignup.register(
                    new com.afterbooth.member.dto.RegisterMemberRequest(a.email(), a.password())))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.AUTH_UNAVAILABLE));
    jdbc.update(
        "update withdrawn_email_block set blocked_until=statement_timestamp() where key_version=? and email_hmac=?",
        hmac.currentVersion(),
        digest);
    rotatedSignup.register(
        new com.afterbooth.member.dto.RegisterMemberRequest(a.email(), a.password()));
    long fresh =
        jdbc.queryForObject(
            "select id from member where email_normalized=?", Long.class, a.email());
    long freshOrg =
        jdbc.queryForObject(
            "select id from organization where primary_member_id=?", Long.class, fresh);
    assertThat(fresh).isNotEqualTo(a.id());
    assertThat(freshOrg).isNotEqualTo(a.org());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from refresh_session where member_id=?", Integer.class, fresh))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from reservation where organization_id=?",
                Integer.class,
                freshOrg))
        .isZero();
  }

  @Test
  void freshAccountAfterWithdrawalBlockExpiryCannotOwnPreviousOrganizationEventOrRequest() {
    var trade = trade();
    var old = trade.receiver();
    long oldEvent =
        jdbc.queryForObject(
            "insert into event(organization_id,name,venue,teardown_deadline) values(?,'Synthetic past owner','Synthetic venue',statement_timestamp()+interval '1 day') returning id",
            Long.class,
            old.org());
    withdrawals.withdraw(old.principal(), new WithdrawMemberRequest(old.password()));
    jdbc.update(
        "update withdrawn_email_block set blocked_until=statement_timestamp()-interval '1 second' where key_version=? and email_hmac=?",
        hmac.currentVersion(),
        hmac.currentDigest("withdrawn-email", old.email()));
    signup.register(
        new com.afterbooth.member.dto.RegisterMemberRequest(old.email(), old.password()));
    long fresh =
        jdbc.queryForObject(
            "select id from member where email_normalized=?", Long.class, old.email());
    long freshOrg =
        jdbc.queryForObject(
            "select id from organization where primary_member_id=?", Long.class, fresh);
    assertThat(fresh).isNotEqualTo(old.id());
    assertThat(freshOrg).isNotEqualTo(old.org());
    // Activate only this synthetic pending row to test the real ownership policy without email.
    jdbc.update(
        "update member set state='ACTIVE',verified_at=statement_timestamp() where id=?", fresh);
    var login = sessions.login(new LoginMemberRequest(old.email(), old.password()), null);
    DomainAuthTestSupport.use(jwt.verify(login.response().accessToken(), clock.instant()));
    try {
      assertThat(access.organizationId()).isEqualTo(freshOrg);
      assertThat(access.owner(old.org())).isFalse();
      assertThatThrownBy(() -> access.event(oldEvent))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
      assertThatThrownBy(() -> access.request(trade.request()))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
      assertThat(
              jdbc.queryForObject(
                  "select organization_id from pickup_request where id=?",
                  Long.class,
                  trade.request()))
          .isEqualTo(old.org());
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
  }

  @Test
  void lateIdempotentPreparationCannotRecreateErasedResponseOrFingerprint() throws Exception {
    var trade = trade();
    DomainAuthTestSupport.use(trade.receiver().principal());
    long reservation;
    try {
      reservation =
          reservations
              .hold(
                  new com.afterbooth.allocation.dto.HoldItemRequest(
                      trade.request(), trade.item(), 1))
              .id();
      reservations.cancel(reservation);
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
    UUID key = UUID.randomUUID();
    var prepared = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var action =
          executor.submit(
              () -> {
                DomainAuthTestSupport.use(trade.receiver().principal());
                try {
                  idempotency.executePrepared(
                      key,
                      "POST",
                      "/api/reservations/" + reservation + "/cancel",
                      new byte[0],
                      200,
                      () -> {
                        prepared.countDown();
                        try {
                          if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            throw new AssertionError("Synthetic preparation gate timed out");
                        } catch (InterruptedException error) {
                          Thread.currentThread().interrupt();
                          throw new AssertionError("Synthetic preparation interrupted");
                        }
                        return () -> reservations.cancel(reservation);
                      });
                  return null;
                } catch (BusinessException error) {
                  return error.errorCode();
                } finally {
                  org.springframework.security.core.context.SecurityContextHolder.clearContext();
                }
              });
      assertThat(prepared.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      withdrawals.withdraw(
          trade.giver().principal(), new WithdrawMemberRequest(trade.giver().password()));
      release.countDown();
      assertThat(action.get(10, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo(ErrorCode.NOT_FOUND);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from idempotency_record where idempotency_key=?",
                  Integer.class,
                  key))
          .isZero();
    } finally {
      release.countDown();
    }
  }

  @Test
  void delayedPhysicalDeletionDoesNotExposeExpiredHistoryToEitherPartyOrReplay() throws Exception {
    var trade = trade();
    UUID key = UUID.randomUUID();
    long reservation;
    DomainAuthTestSupport.use(trade.receiver().principal());
    try {
      reservation =
          reservations
              .hold(
                  new com.afterbooth.allocation.dto.HoldItemRequest(
                      trade.request(), trade.item(), 1))
              .id();
      long id = reservation;
      idempotency.execute(
          key,
          "POST",
          "/api/reservations/" + id + "/cancel",
          new byte[0],
          200,
          () -> reservations.cancel(id));
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
    withdrawals.withdraw(
        trade.giver().principal(), new WithdrawMemberRequest(trade.giver().password()));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/reservations/" + reservation)
                .header("Authorization", "Bearer " + trade.receiver().token()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    jdbc.update(
        "update reservation set retention_until=statement_timestamp() where id=?", reservation);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from reservation where id=?", Integer.class, reservation))
        .isOne();
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/reservations/" + reservation)
                .header("Authorization", "Bearer " + trade.receiver().token()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isNotFound());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/reservations")
                .param("requestId", Long.toString(trade.request()))
                .header("Authorization", "Bearer " + trade.receiver().token()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("[]"));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/reservations/" + reservation)
                .header("Authorization", "Bearer " + trade.giver().token()))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isUnauthorized());
    DomainAuthTestSupport.use(trade.receiver().principal());
    try {
      long id = reservation;
      assertThatThrownBy(
              () ->
                  idempotency.execute(
                      key,
                      "POST",
                      "/api/reservations/" + id + "/cancel",
                      new byte[0],
                      200,
                      () -> {
                        throw new AssertionError("Expired replay action executed");
                      }))
          .isInstanceOfSatisfying(
              BusinessException.class,
              error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from idempotency_record where idempotency_key=?",
                  Integer.class,
                  key))
          .isZero();
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
  }

  private record Trade(Account giver, Account receiver, long item, long request) {}

  private record Account(
      long id,
      long org,
      String email,
      String password,
      AuthenticatedMember principal,
      String token,
      String refresh) {
    @Override
    public String toString() {
      return "Account[redacted]";
    }
  }
}
