package com.afterbooth.member.mail;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

final class ResendMailGateway implements MailGateway {
  private final RestClient client;
  private final String from;
  private final Clock clock;
  private final Sleeper sleeper;
  private final MeterRegistry registry;

  ResendMailGateway(
      RestClient client, String from, Clock clock, Sleeper sleeper, MeterRegistry registry) {
    this.client = client;
    this.from = from;
    this.clock = clock;
    this.sleeper = sleeper;
    this.registry = registry;
  }

  @Override
  public void send(MailMessage message) {
    String subject =
        message.purpose() == MailMessage.Purpose.SIGNUP_VERIFY
            ? "AfterBooth signup verification"
            : "AfterBooth password reset";
    Map<String, Object> body =
        Map.of(
            "from",
            from,
            "to",
            List.of(message.recipient()),
            "subject",
            subject,
            "text",
            "Your verification code is " + message.code() + ". It expires in 10 minutes.");
    for (int attempt = 0; attempt < 3; attempt++) {
      if (!clock.instant().isBefore(message.expiresAt())) {
        record("expired");
        return;
      }
      boolean retry;
      try {
        int status =
            client
                .post()
                .uri("/emails")
                .header(
                    "Idempotency-Key", "auth/" + message.purpose() + "/" + message.challengeId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> response.getStatusCode().value());
        if (status >= 200 && status < 300) {
          record("accepted");
          return;
        }
        retry = status == 429 || status >= 500;
      } catch (ResourceAccessException ignored) {
        retry = true;
      } catch (RuntimeException ignored) {
        record("failed");
        return;
      }
      if (!retry || attempt == 2) {
        record("failed");
        return;
      }
      long delay = (attempt + 1) * 1000L;
      if (!clock.instant().plusMillis(delay).isBefore(message.expiresAt())) {
        record("expired");
        return;
      }
      try {
        sleeper.sleep(delay);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
        record("failed");
        return;
      }
    }
  }

  private void record(String outcome) {
    registry.counter("mail.delivery", "outcome", outcome).increment();
  }

  @FunctionalInterface
  interface Sleeper {
    void sleep(long milliseconds) throws InterruptedException;
  }
}
