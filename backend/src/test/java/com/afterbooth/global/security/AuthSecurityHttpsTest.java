package com.afterbooth.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.docker.compose.enabled=false",
      "afterbooth.auth.allowed-origin=https://localhost:5443"
    })
class AuthSecurityHttpsTest {
  @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17-alpine");
  private static final String KEY = key();
  private static final String ORIGIN = "https://localhost:5443";
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired JsonMapper json;
  HttpClient client;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DB::getJdbcUrl);
    registry.add("spring.datasource.username", DB::getUsername);
    registry.add("spring.datasource.password", DB::getPassword);
    registry.add("afterbooth.auth.hmac.current-version", () -> "test");
    registry.add("afterbooth.auth.hmac.keys.test", () -> KEY);
    LoopbackTls.configure(registry);
  }

  static String key() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  @BeforeEach
  void prepare() throws Exception {
    jdbc.update("delete from auth_rate_limit");
    jdbc.update("delete from csrf_session");
    client = HttpClient.newBuilder().sslContext(LoopbackTls.clientContext()).build();
  }

  @Test
  void realHttpsIssuesSecureHashOnlySessionAndProtectsEverySignupPost() throws Exception {
    HttpResponse<String> issued =
        client.send(
            HttpRequest.newBuilder(url("/api/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(issued.statusCode()).isEqualTo(200);
    assertThat(issued.headers().firstValue("cache-control")).contains("no-store");
    String cookie = issued.headers().firstValue("set-cookie").orElseThrow();
    assertThat(
            cookie.contains("Secure")
                && cookie.contains("HttpOnly")
                && cookie.contains("SameSite=Lax")
                && cookie.contains("Path=/")
                && cookie.contains("Max-Age=3600")
                && !cookie.contains("Domain="))
        .isTrue();
    String pair = cookie.split(";", 2)[0];
    String token = json.readTree(issued.body()).get("csrfToken").asText();
    String id = pair.substring(pair.indexOf('=') + 1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from csrf_session where id_hash=? and token_hash=?",
                Integer.class,
                CsrfSessionService.hash(id),
                CsrfSessionService.hash(token)))
        .isEqualTo(1);
    for (String path :
        new String[] {
          "/api/auth/registrations",
          "/api/auth/email-verifications/request",
          "/api/auth/email-verifications/confirm"
        }) {
      assertRejected(post(path, null, pair, token));
      assertRejected(post(path, "https://attacker.invalid", pair, token));
      assertRejected(post(path, ORIGIN, pair, null));
      assertRejected(post(path, ORIGIN, pair, "invalid"));
      assertRejected(post(path, ORIGIN, null, token));
      HttpResponse<String> valid = post(path, ORIGIN, pair, token);
      assertThat(valid.statusCode()).isEqualTo(400);
      assertThat(valid.headers().firstValue("cache-control")).contains("no-store");
    }
  }

  @Test
  void invalidSecurityRequestsDurablyConsumePerApiIpLimit() throws Exception {
    for (int i = 0; i < 20; i++) assertRejected(post("/api/auth/registrations", null, null, null));
    var limited = post("/api/auth/registrations", null, null, null);
    assertThat(limited.statusCode()).isEqualTo(429);
    assertThat(json.readTree(limited.body()).get("code").asText()).isEqualTo("RATE_LIMITED");
    assertThat(Long.parseLong(limited.headers().firstValue("retry-after").orElseThrow()))
        .isBetween(1L, 3600L);
    assertThat(
            jdbc.queryForObject(
                "select sum(count) from auth_rate_limit where scope='signup:registration:ip'",
                Long.class))
        .isEqualTo(21L);
  }

  @Test
  void eachSignupApiHasAnIndependentTwentyRequestIpQuota() throws Exception {
    for (int i = 0; i < 20; i++) {
      assertRejected(post("/api/auth/registrations", null, null, null));
      assertRejected(post("/api/auth/email-verifications/request", null, null, null));
    }
    for (int i = 0; i < 20; i++)
      assertRejected(post("/api/auth/email-verifications/confirm", null, null, null));
    assertThat(post("/api/auth/email-verifications/confirm", null, null, null).statusCode())
        .isEqualTo(429);
  }

  @Test
  void expiredSessionFailsAndUnimplementedAuthenticationIsDenied() throws Exception {
    var issued =
        client.send(
            HttpRequest.newBuilder(url("/api/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    String pair = issued.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    String token = json.readTree(issued.body()).get("csrfToken").asText();
    jdbc.update(
        "update csrf_session set created_at=?,expires_at=?",
        Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")),
        Timestamp.from(Instant.parse("2000-01-01T01:00:00Z")));
    assertRejected(post("/api/auth/registrations", ORIGIN, pair, token));
    assertThat(post("/api/auth/login", ORIGIN, pair, token).statusCode()).isEqualTo(403);
    var health =
        client.send(
            HttpRequest.newBuilder(url("/api/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(health.statusCode()).isEqualTo(200);
  }

  private URI url(String path) {
    return URI.create("https://localhost:" + port + path);
  }

  private HttpResponse<String> post(String path, String origin, String cookie, String token)
      throws Exception {
    var request =
        HttpRequest.newBuilder(url(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"));
    if (origin != null) request.header("Origin", origin);
    if (cookie != null) request.header("Cookie", cookie);
    if (token != null) request.header("X-CSRF-Token", token);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private void assertRejected(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.headers().firstValue("cache-control")).contains("no-store");
    assertThat(json.readTree(response.body()).get("code").asText()).isEqualTo("CSRF_REJECTED");
  }
}
