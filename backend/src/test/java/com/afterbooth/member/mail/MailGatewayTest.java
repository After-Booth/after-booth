package com.afterbooth.member.mail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MailGatewayTest {
  private final Clock clock = Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC);
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(MailConfiguration.class)
          .withBean(Clock.class, () -> clock)
          .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new);

  @Test
  void defaults_and_local_profile_without_opt_in_are_disabled() {
    runner.run(
        context -> assertTrue(context.getBean(MailGateway.class) instanceof DisabledMailGateway));
    runner
        .withPropertyValues("spring.profiles.active=local")
        .run(
            context ->
                assertTrue(context.getBean(MailGateway.class) instanceof DisabledMailGateway));
  }

  @Test
  void disabled_gateway_produces_no_console_output() {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    PrintStream original = System.out;
    try (PrintStream captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
      System.setOut(captured);
      new DisabledMailGateway()
          .send(message(MailMessage.Purpose.SIGNUP_VERIFY, clock.instant().plusSeconds(600)));
    } finally {
      System.setOut(original);
    }
    assertTrue(output.size() == 0, "Disabled delivery must not reveal a code");
  }

  @Test
  void explicit_local_opt_in_needs_no_provider_key() {
    runner
        .withPropertyValues("spring.profiles.active=local", "afterbooth.mail.fake-enabled=true")
        .run(context -> assertTrue(context.getBean(MailGateway.class) instanceof FakeMailGateway));
  }

  @Test
  void unsafe_selections_are_rejected_instead_of_falling_back() {
    for (String profiles :
        new String[] {
          "default", "prod", "production", "local,prod", "local,production", "local,deployment"
        }) {
      runner
          .withPropertyValues(
              "spring.profiles.active=" + profiles, "afterbooth.mail.fake-enabled=true")
          .run(context -> assertTrue(context.getStartupFailure() != null));
    }
    runner
        .withPropertyValues(
            "spring.profiles.active=local",
            "afterbooth.mail.fake-enabled=true",
            "afterbooth.mail.resend-enabled=true")
        .run(context -> assertTrue(context.getStartupFailure() != null));
    runner
        .withPropertyValues("spring.profiles.active=local", "afterbooth.mail.resend-enabled=true")
        .run(context -> assertTrue(context.getStartupFailure() != null));
    runner
        .withPropertyValues(
            "spring.profiles.active=deployment", "afterbooth.mail.resend-enabled=true")
        .run(context -> assertTrue(context.getStartupFailure() != null));
  }

  @Test
  void explicit_deployment_resend_selection_never_selects_fake() {
    runner
        .withPropertyValues(
            "spring.profiles.active=deployment",
            "afterbooth.mail.resend-enabled=true",
            "afterbooth.mail.resend-api-key=" + randomToken(),
            "afterbooth.mail.resend-from=sender@example.invalid")
        .run(
            context -> assertTrue(context.getBean(MailGateway.class) instanceof ResendMailGateway));
  }

  @Test
  void fake_prints_only_purpose_and_unexpired_code_without_retaining_a_mailbox() {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    FakeMailGateway gateway =
        new FakeMailGateway(clock, new PrintStream(output, true, StandardCharsets.UTF_8));
    for (MailMessage.Purpose purpose : MailMessage.Purpose.values()) {
      output.reset();
      MailMessage message = message(purpose, clock.instant().plusSeconds(600));
      gateway.send(message);
      String actual = output.toString(StandardCharsets.UTF_8);
      assertTrue(
          actual.equals(purpose + " " + message.code() + System.lineSeparator()),
          "Console output must contain only purpose and code");
      assertFalse(actual.contains(message.recipient()), "Recipient must remain private");
      assertFalse(actual.contains(message.challengeId()), "Challenge must remain private");
      assertTrue(message.toString().equals("MailMessage[redacted]"));
    }
    output.reset();
    gateway.send(message(MailMessage.Purpose.SIGNUP_VERIFY, clock.instant()));
    assertTrue(output.size() == 0, "Expired codes must not be printed");
  }

  static MailMessage message(MailMessage.Purpose purpose, Instant expiresAt) {
    String code = String.format("%06d", new SecureRandom().nextInt(1_000_000));
    return new MailMessage(randomToken(), purpose, "recipient@example.invalid", code, expiresAt);
  }

  static String randomToken() {
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
  }
}
