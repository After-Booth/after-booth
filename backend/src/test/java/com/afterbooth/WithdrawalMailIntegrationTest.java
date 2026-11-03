package com.afterbooth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;

import com.afterbooth.global.security.JwtService;
import com.afterbooth.member.dto.LoginMemberRequest;
import com.afterbooth.member.dto.WithdrawMemberRequest;
import com.afterbooth.member.mail.MailGateway;
import com.afterbooth.member.service.SessionService;
import com.afterbooth.member.service.SignupService;
import com.afterbooth.member.service.WithdrawalService;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@Import(TestcontainersConfiguration.class)
class WithdrawalMailIntegrationTest {
  @Autowired SignupService signup;
  @Autowired WithdrawalService withdrawals;
  @Autowired SessionService sessions;
  @Autowired JwtService jwt;
  @Autowired PasswordEncoder encoder;
  @Autowired JdbcTemplate jdbc;
  @Autowired Clock clock;

  @MockitoBean(name = "signupMailExecutor")
  Executor executor;

  @MockitoBean MailGateway gateway;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    SyntheticAuthSupport.configure(registry);
    byte[] key = new byte[32];
    new java.security.SecureRandom().nextBytes(key);
    registry.add("afterbooth.auth.hmac.current-version", () -> "withdrawal-mail");
    registry.add(
        "afterbooth.auth.hmac.keys.withdrawal-mail",
        () -> java.util.Base64.getEncoder().encodeToString(key));
  }

  private Account account() {
    String email = UUID.randomUUID() + "@example.invalid";
    String password = UUID.randomUUID().toString();
    long id =
        jdbc.queryForObject(
            "insert into member(email_normalized,password_hash,state,created_at,verified_at) values(?,?,'ACTIVE',clock_timestamp(),clock_timestamp()) returning id",
            Long.class,
            email,
            encoder.encode(password));
    jdbc.update(
        "insert into organization(name,primary_member_id) values('Synthetic member',?)", id);
    return new Account(id, email, password);
  }

  private void withdraw(Account account) {
    var login = sessions.login(new LoginMemberRequest(account.email, account.password), null);
    var principal = jwt.verify(login.response().accessToken(), clock.instant());
    withdrawals.withdraw(principal, new WithdrawMemberRequest(account.password));
  }

  @Test
  void actualResetIssuanceQueueIsDiscardedBeforeWithdrawalReturns() {
    Account account = account();
    List<Runnable> queued = new CopyOnWriteArrayList<>();
    doAnswer(
            call -> {
              queued.add(call.getArgument(0));
              return null;
            })
        .when(executor)
        .execute(any());
    signup.requestPasswordReset(account.email);
    assertThat(queued.size()).isEqualTo(1);
    withdraw(account);
    queued.forEach(Runnable::run);
    verifyNoInteractions(gateway);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from auth_challenge where member_id=?", Integer.class, account.id))
        .isZero();
  }

  @Test
  void rejectedWithdrawalPreservesTheOriginalQueuedDelivery() {
    Account account = account();
    List<Runnable> queued = new CopyOnWriteArrayList<>();
    doAnswer(
            call -> {
              queued.add(call.getArgument(0));
              return null;
            })
        .when(executor)
        .execute(any());
    java.util.concurrent.atomic.AtomicBoolean sent =
        new java.util.concurrent.atomic.AtomicBoolean();
    doAnswer(
            call -> {
              sent.set(true);
              return null;
            })
        .when(gateway)
        .send(any());
    signup.requestPasswordReset(account.email);
    var login = sessions.login(new LoginMemberRequest(account.email, account.password), null);
    var principal = jwt.verify(login.response().accessToken(), clock.instant());
    assertThatThrownBy(
            () ->
                withdrawals.withdraw(
                    principal, new WithdrawMemberRequest(UUID.randomUUID().toString())))
        .isInstanceOf(com.afterbooth.global.error.BusinessException.class);
    queued.forEach(Runnable::run);
    assertThat(sent).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select state='ACTIVE' from member where id=?", Boolean.class, account.id))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from auth_challenge where member_id=?", Integer.class, account.id))
        .isEqualTo(1);
  }

  @Test
  void actualWithdrawalWaitsForRunningDeliveryWithoutHoldingMemberLock() throws Exception {
    Account account = account();
    List<Runnable> queued = new CopyOnWriteArrayList<>();
    doAnswer(
            call -> {
              queued.add(call.getArgument(0));
              return null;
            })
        .when(executor)
        .execute(any());
    CountDownLatch sending = new CountDownLatch(1), release = new CountDownLatch(1);
    doAnswer(
            call -> {
              sending.countDown();
              if (!release.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("Synthetic timeout");
              return null;
            })
        .when(gateway)
        .send(any());
    signup.requestPasswordReset(account.email);
    try (var threads = Executors.newFixedThreadPool(2)) {
      var delivery = threads.submit(queued.getFirst());
      assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
      var withdrawal = threads.submit(() -> withdraw(account));
      try {
        assertThatThrownBy(() -> withdrawal.get(100, TimeUnit.MILLISECONDS))
            .isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(
                jdbc.queryForObject(
                    "select state='ACTIVE' from member where id=?", Boolean.class, account.id))
            .isTrue();
      } finally {
        release.countDown();
      }
      delivery.get(5, TimeUnit.SECONDS);
      withdrawal.get(10, TimeUnit.SECONDS);
      assertThat(
              jdbc.queryForObject(
                  "select state='WITHDRAWN' from member where id=?", Boolean.class, account.id))
          .isTrue();
    }
  }

  private record Account(long id, String email, String password) {
    @Override
    public String toString() {
      return "Account[redacted]";
    }
  }
}
