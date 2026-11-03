package com.afterbooth;

import static org.assertj.core.api.Assertions.assertThat;

import com.afterbooth.global.security.LoopbackTls;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class LocalFakeSignupHttpsTest {
  @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17-alpine");

  @Test
  void configuredLocalFakeCodeCompletesRealHttpsFlowAndSetsFinalPassword() throws Exception {
    Map<String, Object> properties = new HashMap<>();
    properties.put("server.port", 0);
    properties.put("spring.docker.compose.enabled", false);
    properties.put("spring.datasource.url", DB.getJdbcUrl());
    properties.put("spring.datasource.username", DB.getUsername());
    properties.put("spring.datasource.password", DB.getPassword());
    properties.put("afterbooth.mail.fake-enabled", true);
    properties.put("afterbooth.auth.allowed-origin", "https://localhost:5443");
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    properties.put("afterbooth.auth.hmac.current-version", "local-test");
    properties.put("afterbooth.auth.hmac.keys.local-test", Base64.getEncoder().encodeToString(key));
    LoopbackTls.configure((name, supplier) -> properties.put(name, supplier.get()));
    PrintStream original = System.out;
    ByteArrayOutputStream console = new ByteArrayOutputStream();
    try (PrintStream captured = new PrintStream(console, true, StandardCharsets.UTF_8)) {
      System.setOut(captured);
      try (var context =
          new SpringApplicationBuilder(AfterboothApplication.class)
              .profiles("local")
              .properties(properties)
              .run()) {
        System.setOut(original);
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        HttpClient client = HttpClient.newBuilder().sslContext(LoopbackTls.clientContext()).build();
        JsonMapper json = context.getBean(JsonMapper.class);
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        var csrf =
            client.send(
                HttpRequest.newBuilder(uri(port, "/api/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(csrf.statusCode()).isEqualTo(200);
        String cookie = csrf.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        String token = json.readTree(csrf.body()).get("csrfToken").asText();
        String email = UUID.randomUUID() + "@example.test",
            registered = UUID.randomUUID().toString(),
            finalPassword = " " + UUID.randomUUID() + " e\u0301 ";
        var registration =
            post(
                client,
                json,
                port,
                "/api/auth/registrations",
                cookie,
                token,
                Map.of("email", email, "password", registered));
        assertThat(registration.statusCode()).isEqualTo(202);
        assertThat(json.readTree(registration.body()).size()).isEqualTo(1);
        assertThat(registration.headers().firstValue("cache-control")).contains("no-store");
        var request =
            post(
                client,
                json,
                port,
                "/api/auth/email-verifications/request",
                cookie,
                token,
                Map.of("email", email));
        assertThat(request.statusCode()).isEqualTo(202);
        String challenge = json.readTree(request.body()).get("challengeId").asText();
        Pattern line = Pattern.compile("(?m)^SIGNUP_VERIFY ([0-9]{6})\\r?$");
        String code = null;
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
          var matcher = line.matcher(console.toString(StandardCharsets.UTF_8));
          if (matcher.find()) {
            code = matcher.group(1);
            break;
          }
          Thread.sleep(20);
        }
        assertThat(code != null).isTrue();
        var confirmation =
            post(
                client,
                json,
                port,
                "/api/auth/email-verifications/confirm",
                cookie,
                token,
                Map.of(
                    "email",
                    email,
                    "challengeId",
                    challenge,
                    "code",
                    code,
                    "password",
                    finalPassword));
        assertThat(confirmation.statusCode()).isEqualTo(204);
        assertThat(confirmation.headers().firstValue("cache-control")).contains("no-store");
        assertThat(
                jdbc.queryForObject(
                    "SELECT state FROM member WHERE email_normalized=?", String.class, email))
            .isEqualTo("ACTIVE");
        String hash =
            jdbc.queryForObject(
                "SELECT password_hash FROM member WHERE email_normalized=?", String.class, email);
        PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
        assertThat(encoder.matches(finalPassword, hash)).isTrue();
        assertThat(encoder.matches(registered, hash)).isFalse();
        String output = console.toString(StandardCharsets.UTF_8);
        assertThat(
                !output.contains(email)
                    && !output.contains(challenge)
                    && !output.contains(registered)
                    && !output.contains(finalPassword)
                    && !output.contains(token))
            .isTrue();
        var repeat =
            post(
                client,
                json,
                port,
                "/api/auth/email-verifications/confirm",
                cookie,
                token,
                Map.of(
                    "email",
                    email,
                    "challengeId",
                    challenge,
                    "code",
                    code,
                    "password",
                    UUID.randomUUID().toString()));
        assertThat(repeat.statusCode()).isEqualTo(400);
        assertThat(json.readTree(repeat.body()).get("code").asText())
            .isEqualTo("INVALID_AUTH_CHALLENGE");
        var unknown =
            post(
                client,
                json,
                port,
                "/api/auth/registrations",
                cookie,
                token,
                Map.of("email", email, "password", registered, "memberId", 1));
        assertThat(unknown.statusCode()).isEqualTo(400);
        String marker = "private-" + UUID.randomUUID().toString().replace("-", "");
        String failedEmail = UUID.randomUUID() + "@example.test";
        jdbc.execute(
            "CREATE FUNCTION synthetic_http_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.primary_member_id IS NOT NULL THEN RAISE EXCEPTION '"
                + marker
                + "'; END IF; RETURN NEW; END $$");
        jdbc.execute(
            "CREATE TRIGGER synthetic_http_failure BEFORE INSERT ON organization FOR EACH ROW EXECUTE FUNCTION synthetic_http_failure()");
        try {
          var failure =
              post(
                  client,
                  json,
                  port,
                  "/api/auth/registrations",
                  cookie,
                  token,
                  Map.of("email", failedEmail, "password", UUID.randomUUID().toString()));
          assertThat(failure.statusCode()).isEqualTo(503);
          assertThat(json.readTree(failure.body()).get("code").asText())
              .isEqualTo("AUTH_UNAVAILABLE");
          assertThat(
                  !failure.body().contains(marker)
                      && !console.toString(StandardCharsets.UTF_8).contains(marker))
              .isTrue();
          assertThat(
                  jdbc.queryForObject(
                      "SELECT count(*) FROM member WHERE email_normalized=?",
                      Long.class,
                      failedEmail))
              .isZero();
        } finally {
          jdbc.execute("DROP TRIGGER synthetic_http_failure ON organization");
          jdbc.execute("DROP FUNCTION synthetic_http_failure()");
        }
      }
    } finally {
      System.setOut(original);
      console.reset();
    }
  }

  private static URI uri(int port, String path) {
    return URI.create("https://localhost:" + port + path);
  }

  private static HttpResponse<String> post(
      HttpClient client,
      JsonMapper json,
      int port,
      String path,
      String cookie,
      String token,
      Object body)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(port, path))
            .header("Content-Type", "application/json")
            .header("Origin", "https://localhost:5443")
            .header("Cookie", cookie)
            .header("X-CSRF-Token", token)
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
