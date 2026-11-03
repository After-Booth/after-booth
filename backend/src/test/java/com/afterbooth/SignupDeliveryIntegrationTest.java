package com.afterbooth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.afterbooth.member.dto.RegisterMemberRequest;
import com.afterbooth.member.mail.MailGateway;
import com.afterbooth.member.service.SignupService;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SignupDeliveryIntegrationTest {
  @Autowired SignupService signup;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean MailGateway gateway;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    registry.add("afterbooth.auth.hmac.current-version", () -> "delivery-test");
    registry.add(
        "afterbooth.auth.hmac.keys.delivery-test", () -> Base64.getEncoder().encodeToString(bytes));
  }

  @Test
  void blockedExternalDeliveryHoldsNoTransactionOrMemberLocksAndDoesNotDelayPublicResponse()
      throws Exception {
    String email = UUID.randomUUID() + "@example.test";
    signup.register(new RegisterMemberRequest(email, UUID.randomUUID().toString()));
    CountDownLatch sending = new CountDownLatch(1),
        release = new CountDownLatch(1),
        finished = new CountDownLatch(1);
    AtomicBoolean noTransaction = new AtomicBoolean();
    doAnswer(
            call -> {
              noTransaction.set(!TransactionSynchronizationManager.isActualTransactionActive());
              sending.countDown();
              try {
                release.await(20, TimeUnit.SECONDS);
              } finally {
                finished.countDown();
              }
              throw new IllegalStateException("Synthetic provider failure");
            })
        .when(gateway)
        .send(any());
    try (var callers = Executors.newFixedThreadPool(2)) {
      try {
        var real = callers.submit(() -> signup.requestVerification(email));
        assertThat(sending.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(real.get(2, TimeUnit.SECONDS).challengeId().length() == 43).isTrue();
        assertThat(finished.getCount()).isEqualTo(1);
        assertThat(noTransaction.get()).isTrue();
        var dummy =
            callers.submit(() -> signup.requestVerification(UUID.randomUUID() + "@example.test"));
        assertThat(dummy.get(2, TimeUnit.SECONDS).challengeId().length() == 43).isTrue();
        var locked =
            callers.submit(
                () -> {
                  try (var connection = jdbc.getDataSource().getConnection()) {
                    connection.setAutoCommit(false);
                    try (var statement =
                        connection.prepareStatement(
                            "SELECT id FROM member WHERE email_normalized=? FOR UPDATE NOWAIT")) {
                      statement.setString(1, email);
                      statement.executeQuery().close();
                    } finally {
                      connection.rollback();
                    }
                    return true;
                  }
                });
        assertThat(locked.get(2, TimeUnit.SECONDS)).isTrue();
      } finally {
        release.countDown();
      }
    }
    assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
  }
}
