package com.afterbooth.member.mail;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class ResendMailGatewayTest {
  private final MutableClock clock = new MutableClock();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final AtomicInteger calls = new AtomicInteger();
  private final List<String> bodies = new ArrayList<>();
  private final List<String> keys = new ArrayList<>();
  private HttpServer server;
  private ExecutorService serverExecutor;

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
    if (serverExecutor != null) serverExecutor.shutdownNow();
    registry.close();
  }

  @Test
  void retry_reuses_body_and_challenge_idempotency_key_and_never_logs_payload(CapturedOutput output)
      throws Exception {
    RestClient client = server(503, 503, 202);
    List<Long> delays = new ArrayList<>();
    ResendMailGateway gateway = gateway(client, delays::add);
    MailMessage message =
        MailGatewayTest.message(
            MailMessage.Purpose.SIGNUP_VERIFY, clock.instant().plusSeconds(600));
    gateway.send(message);
    assertTrue(calls.get() == 3);
    assertTrue(delays.equals(List.of(1000L, 2000L)));
    assertTrue(
        bodies.stream().allMatch(bodies.getFirst()::equals), "Retries must preserve payload");
    assertTrue(
        keys.stream()
            .allMatch(key -> key.equals("auth/" + message.purpose() + "/" + message.challengeId())),
        "Retries must preserve challenge key");
    assertTrue(bodies.getFirst().contains(message.code()), "Mail must carry the code");
    assertTrue(bodies.getFirst().contains("signup verification"));
    assertTrue(!output.getAll().contains(message.code()), "Provider code must never be printed");
    assertTrue(!output.getAll().contains(message.challengeId()), "Challenge must never be printed");
    assertTrue(!output.getAll().contains(message.recipient()), "Recipient must never be printed");
    assertTrue(registry.counter("mail.delivery", "outcome", "accepted").count() == 1);
  }

  @Test
  void retry_exhaustion_is_bounded_and_failure_response_remains_private(CapturedOutput output)
      throws Exception {
    MailMessage message =
        MailGatewayTest.message(
            MailMessage.Purpose.PASSWORD_RESET, clock.instant().plusSeconds(600));
    gateway(server(503, 503, 503, 202), ignored -> {}).send(message);
    assertTrue(calls.get() == 3);
    assertTrue(registry.counter("mail.delivery", "outcome", "failed").count() == 1);
    assertTrue(bodies.getFirst().contains("password reset"));
    assertTrue(!output.getAll().contains(message.code()), "No failure body or code may be logged");
    assertTrue(
        !output.getAll().contains("private-provider-response"),
        "Provider response must not be logged");
  }

  @Test
  void permanent_failure_is_not_retried() throws Exception {
    gateway(server(400), ignored -> {})
        .send(
            MailGatewayTest.message(
                MailMessage.Purpose.SIGNUP_VERIFY, clock.instant().plusSeconds(600)));
    assertTrue(calls.get() == 1);
    assertTrue(registry.counter("mail.delivery", "outcome", "failed").count() == 1);
  }

  @Test
  void provider_rate_limit_is_retried_without_fallback() throws Exception {
    List<Long> delays = new ArrayList<>();
    gateway(server(429, 202), delays::add)
        .send(
            MailGatewayTest.message(
                MailMessage.Purpose.SIGNUP_VERIFY, clock.instant().plusSeconds(600)));
    assertTrue(calls.get() == 2);
    assertTrue(delays.equals(List.of(1000L)));
    assertTrue(registry.counter("mail.delivery", "outcome", "accepted").count() == 1);
  }

  @Test
  void interrupted_retry_stops_sending_and_preserves_interrupt() throws Exception {
    try {
      gateway(
              server(503, 202),
              ignored -> {
                throw new InterruptedException();
              })
          .send(
              MailGatewayTest.message(
                  MailMessage.Purpose.SIGNUP_VERIFY, clock.instant().plusSeconds(600)));
      assertTrue(calls.get() == 1);
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(registry.counter("mail.delivery", "outcome", "failed").count() == 1);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void expiry_is_checked_before_first_send_and_after_wait() throws Exception {
    RestClient client = server(503, 202);
    gateway(client, ignored -> {})
        .send(MailGatewayTest.message(MailMessage.Purpose.SIGNUP_VERIFY, clock.instant()));
    assertTrue(calls.get() == 0);
    MailMessage message =
        MailGatewayTest.message(MailMessage.Purpose.SIGNUP_VERIFY, clock.instant().plusSeconds(5));
    gateway(client, ignored -> clock.current = clock.current.plusSeconds(5)).send(message);
    assertTrue(calls.get() == 1);
    assertTrue(registry.counter("mail.delivery", "outcome", "expired").count() == 2);
  }

  @Test
  void timeout_retries_are_bounded_without_propagating_raw_exception(CapturedOutput output) {
    String privateMarker = MailGatewayTest.randomToken();
    RestClient client =
        RestClient.builder()
            .requestFactory(
                (uri, method) -> {
                  calls.incrementAndGet();
                  throw new ResourceAccessException(privateMarker);
                })
            .baseUrl("http://127.0.0.1")
            .build();
    gateway(client, ignored -> {})
        .send(
            MailGatewayTest.message(
                MailMessage.Purpose.SIGNUP_VERIFY, clock.instant().plusSeconds(600)));
    assertTrue(calls.get() == 3);
    assertTrue(
        !output.getAll().contains(privateMarker), "Raw transport exceptions must not escape");
  }

  @Test
  void actual_loopback_read_timeouts_retry_at_most_twice_without_payload_output(
      CapturedOutput output) throws Exception {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
    serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(serverExecutor);
    server.createContext(
        "/emails",
        exchange -> {
          calls.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          try {
            Thread.sleep(1000L);
            exchange.sendResponseHeaders(202, -1);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } catch (IOException ignored) {
          } finally {
            exchange.close();
          }
        });
    server.start();
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(1));
    factory.setReadTimeout(Duration.ofMillis(100));
    RestClient client =
        RestClient.builder()
            .requestFactory(factory)
            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
            .build();
    List<Long> delays = new ArrayList<>();
    MailMessage message =
        MailGatewayTest.message(
            MailMessage.Purpose.SIGNUP_VERIFY, clock.instant().plusSeconds(600));
    gateway(client, delays::add).send(message);
    assertTrue(calls.get() == 3, "Wire timeouts must make only three attempts");
    assertTrue(delays.equals(List.of(1000L, 2000L)));
    assertTrue(registry.counter("mail.delivery", "outcome", "failed").count() == 1);
    assertTrue(!output.getAll().contains(message.code()), "Timed-out payload must not be logged");
    assertTrue(
        !output.getAll().contains(message.recipient()), "Timed-out recipient must not be logged");
  }

  private ResendMailGateway gateway(RestClient client, ResendMailGateway.Sleeper sleeper) {
    return new ResendMailGateway(client, "sender@example.invalid", clock, sleeper, registry);
  }

  private RestClient server(int... statuses) throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
    server.createContext(
        "/emails",
        exchange -> {
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
          int index = calls.getAndIncrement();
          byte[] body = "private-provider-response".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(statuses[Math.min(index, statuses.length - 1)], body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    return RestClient.builder()
        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
        .build();
  }

  private static final class MutableClock extends Clock {
    private Instant current = Instant.parse("2026-11-03T00:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return current;
    }
  }
}
