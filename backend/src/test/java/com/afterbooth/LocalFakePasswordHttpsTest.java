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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class LocalFakePasswordHttpsTest {
  @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17-alpine");
  private static ConfigurableApplicationContext context;
  private static final ByteArrayOutputStream console = new ByteArrayOutputStream();
  private static PrintStream privateOutput;
  private static HttpClient client;
  private static JsonMapper json;
  private static JdbcTemplate jdbc;
  private static int port;

  @BeforeAll
  static void startRealLocalFakeHttpsServer() throws Exception {
    var properties = new HashMap<String, Object>();
    properties.put("server.port", 0);
    properties.put("spring.docker.compose.enabled", false);
    properties.put("spring.datasource.url", DB.getJdbcUrl());
    properties.put("spring.datasource.username", DB.getUsername());
    properties.put("spring.datasource.password", DB.getPassword());
    properties.put("afterbooth.mail.fake-enabled", true);
    properties.put("afterbooth.auth.allowed-origin", "https://localhost:5443");
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    properties.put("afterbooth.auth.hmac.current-version", "password-https");
    properties.put(
        "afterbooth.auth.hmac.keys.password-https", Base64.getEncoder().encodeToString(key));
    SyntheticAuthSupport.configure(properties);
    LoopbackTls.configure((name, supplier) -> properties.put(name, supplier.get()));
    PrintStream original = System.out;
    privateOutput = new PrintStream(console, true, StandardCharsets.UTF_8);
    try {
      System.setOut(privateOutput);
      context =
          new SpringApplicationBuilder(AfterboothApplication.class)
              .profiles("local")
              .properties(properties)
              .run();
    } finally {
      System.setOut(original);
    }
    port = ((WebServerApplicationContext) context).getWebServer().getPort();
    client = HttpClient.newBuilder().sslContext(LoopbackTls.clientContext()).build();
    json = context.getBean(JsonMapper.class);
    jdbc = context.getBean(JdbcTemplate.class);
  }

  @AfterAll
  static void stop() {
    if (context != null) context.close();
    if (privateOutput != null) privateOutput.close();
  }

  @BeforeEach
  void resetOnlyDisposableRateRows() {
    jdbc.update("delete from auth_rate_limit");
    console.reset();
  }

  private Account account() {
    String email = UUID.randomUUID() + "@example.invalid",
        password = " " + UUID.randomUUID() + " e\u0301 ";
    long id =
        jdbc.queryForObject(
            "insert into member(email_normalized,password_hash,state,created_at,verified_at) values(?,?,'ACTIVE',clock_timestamp(),clock_timestamp()) returning id",
            Long.class,
            email,
            context.getBean(PasswordEncoder.class).encode(password));
    return new Account(id, email, password);
  }

  private static URI uri(String path) {
    return URI.create("https://localhost:" + port + path);
  }

  private static class Browser {
    Map<String, String> cookies = new HashMap<>();
    String csrf;
    String access;

    void csrf() throws Exception {
      var builder = HttpRequest.newBuilder(uri("/api/auth/csrf")).GET();
      if (!cookies.isEmpty()) builder.header("Cookie", cookie());
      var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
      absorb(response);
      csrf = json.readTree(response.body()).get("csrfToken").asText();
    }

    String cookie() {
      return String.join("; ", cookies.values());
    }

    void absorb(HttpResponse<?> response) {
      for (String header : response.headers().allValues("set-cookie")) {
        String pair = header.split(";", 2)[0];
        cookies.put(pair.substring(0, pair.indexOf('=')), pair);
      }
    }

    HttpResponse<String> post(String path, Map<String, ?> body) throws Exception {
      return post(path, body, "https://localhost:5443", csrf, access);
    }

    HttpResponse<String> post(
        String path, Map<String, ?> body, String origin, String token, String bearer)
        throws Exception {
      var builder =
          HttpRequest.newBuilder(uri(path))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      if (!cookies.isEmpty()) builder.header("Cookie", cookie());
      if (origin != null) builder.header("Origin", origin);
      if (token != null) builder.header("X-CSRF-Token", token);
      if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
      return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> delete(
        String path, Map<String, ?> body, String origin, String token, String bearer)
        throws Exception {
      var builder =
          HttpRequest.newBuilder(uri(path))
              .header("Content-Type", "application/json")
              .method("DELETE", HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      if (!cookies.isEmpty()) builder.header("Cookie", cookie());
      if (origin != null) builder.header("Origin", origin);
      if (token != null) builder.header("X-CSRF-Token", token);
      if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
      return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    void login(Account a) throws Exception {
      var response = post("/api/auth/login", Map.of("email", a.email(), "password", a.password()));
      assertThat(response.statusCode()).isEqualTo(200);
      absorb(response);
      access = json.readTree(response.body()).get("accessToken").asText();
      csrf();
    }
  }

  private String resetCode() throws Exception {
    return deliveredCode("PASSWORD_RESET");
  }

  private String deliveredCode(String purpose) throws Exception {
    var pattern = Pattern.compile("(?m)^" + purpose + " ([0-9]{6})\\r?$");
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < until) {
      var matcher = pattern.matcher(console.toString(StandardCharsets.UTF_8));
      String code = null;
      while (matcher.find()) code = matcher.group(1);
      if (code != null) return code;
      Thread.sleep(20);
    }
    throw new AssertionError("Synthetic Fake output did not arrive");
  }

  private void error(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(json.readTree(response.body()).get("code").asText()).isEqualTo(code);
    assertThat(response.headers().firstValue("cache-control")).contains("no-store");
  }

  private void cleared(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(response.body()).isEmpty();
    var headers = response.headers().allValues("set-cookie");
    assertThat(headers).hasSize(2);
    assertThat(
            headers.stream()
                .allMatch(
                    h ->
                        h.contains("HttpOnly")
                            && h.contains("Secure")
                            && h.contains("SameSite=Lax")
                            && h.contains("Path=/")
                            && h.contains("Max-Age=0")
                            && !h.contains("Domain=")))
        .isTrue();
    assertThat(headers.stream().anyMatch(h -> h.startsWith("__Host-afterbooth-refresh="))).isTrue();
    assertThat(headers.stream().anyMatch(h -> h.startsWith("__Host-afterbooth-csrf-session=")))
        .isTrue();
    assertThat(response.headers().firstValue("cache-control")).contains("no-store");
  }

  @Test
  void localFakeResetOverHttpsLogsInWithNewPasswordAndRejectsUsedOrExpiredCode() throws Exception {
    var a = account();
    var browser = new Browser();
    browser.csrf();
    browser.login(a);
    String previousAccess = browser.access;
    var issuance = browser.post("/api/auth/password-resets/request", Map.of("email", a.email()));
    assertThat(issuance.statusCode()).isEqualTo(202);
    String id = json.readTree(issuance.body()).get("challengeId").asText(),
        code = resetCode(),
        next = UUID.randomUUID().toString();
    var body = Map.of("email", a.email(), "challengeId", id, "code", code, "newPassword", next);
    cleared(browser.post("/api/auth/password-resets/confirm", body));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from csrf_session where member_id=?", Long.class, a.id()))
        .isZero();
    browser.csrf();
    error(browser.post("/api/auth/password-resets/confirm", body), 400, "INVALID_AUTH_CHALLENGE");
    error(
        browser.post(
            "/api/members/me/password",
            Map.of("currentPassword", next, "newPassword", a.password())),
        401,
        "UNAUTHENTICATED");
    browser.access = null;
    browser.login(new Account(a.id(), a.email(), next));
    assertThat(!browser.access.equals(previousAccess)).isTrue();
    jdbc.update("delete from auth_rate_limit");
    console.reset();
    var expired = browser.post("/api/auth/password-resets/request", Map.of("email", a.email()));
    String expiredId = json.readTree(expired.body()).get("challengeId").asText(),
        expiredCode = resetCode();
    jdbc.update(
        "update auth_challenge set created_at=statement_timestamp()-interval '11 minutes',expires_at=statement_timestamp()-interval '1 minute' where id=?",
        expiredId);
    error(
        browser.post(
            "/api/auth/password-resets/confirm",
            Map.of(
                "email",
                a.email(),
                "challengeId",
                expiredId,
                "code",
                expiredCode,
                "newPassword",
                a.password())),
        400,
        "INVALID_AUTH_CHALLENGE");
    String output = console.toString(StandardCharsets.UTF_8);
    assertThat(
            !output.contains(a.email())
                && !output.contains(id)
                && !output.contains(next)
                && !output.contains(browser.access)
                && !output.contains(browser.csrf))
        .isTrue();
  }

  @Test
  void changeRequiresBearerExactOriginCsrfAndRejectsUnknownFields() throws Exception {
    var a = account();
    var browser = new Browser();
    browser.csrf();
    browser.login(a);
    var body = Map.of("currentPassword", a.password(), "newPassword", UUID.randomUUID().toString());
    error(
        browser.post(
            "/api/members/me/password", body, "https://localhost:5443", browser.csrf, null),
        401,
        "UNAUTHENTICATED");
    for (String path : new String[] {"/api/members/me/password", "/api/members/me/%70assword"}) {
      error(browser.post(path, body, null, browser.csrf, browser.access), 403, "CSRF_REJECTED");
      error(
          browser.post(
              path, body, "https://localhost:5443.evil.invalid", browser.csrf, browser.access),
          403,
          "CSRF_REJECTED");
      error(
          browser.post(path, body, "https://localhost:5443", null, browser.access),
          403,
          "CSRF_REJECTED");
    }
    error(
        browser.post(
            "/api/members/me/password",
            Map.of("currentPassword", a.password(), "newPassword", a.password(), "memberId", 999)),
        400,
        "INVALID_INPUT");
    error(
        browser.post("/api/members/me/password", Map.of("newPassword", a.password())),
        400,
        "INVALID_INPUT");
    error(
        browser.post(
            "/api/members/me/password",
            Map.of("currentPassword", a.password(), "newPassword", "short")),
        400,
        "INVALID_INPUT");
    error(
        browser.post(
            "/api/members/me/password",
            Map.of("currentPassword", UUID.randomUUID().toString(), "newPassword", a.password())),
        403,
        "INVALID_CURRENT_PASSWORD");
    cleared(browser.post("/api/members/me/password", body));
    browser.csrf();
    error(browser.post("/api/members/me/password", body), 401, "UNAUTHENTICATED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from idempotency_record where actor_member_id=?",
                Long.class,
                a.id()))
        .isZero();
  }

  @Test
  void resetEndpointsAndEncodedAliasesEnforceOriginAndCsrf() throws Exception {
    var browser = new Browser();
    browser.csrf();
    for (String path :
        new String[] {
          "/api/auth/password-resets/request",
          "/api/auth/password-resets/confirm",
          "/api/auth/%70assword-resets/request",
          "/api/auth/password-resets/%63onfirm"
        }) {
      error(
          browser.post(
              path,
              Map.of("email", UUID.randomUUID() + "@example.invalid"),
              null,
              browser.csrf,
              null),
          403,
          "CSRF_REJECTED");
      error(
          browser.post(path, Map.of(), "https://localhost:5443", null, null), 403, "CSRF_REJECTED");
    }
    error(
        browser.post(
            "/api/auth/password-resets/confirm",
            Map.of(
                "email",
                UUID.randomUUID() + "@example.invalid",
                "challengeId",
                "bad",
                "code",
                "12345",
                "newPassword",
                UUID.randomUUID().toString())),
        400,
        "INVALID_INPUT");
    error(
        browser.post(
            "/api/auth/password-resets/confirm",
            Map.of(
                "email",
                UUID.randomUUID() + "@example.invalid",
                "challengeId",
                "a".repeat(43),
                "code",
                "123456",
                "newPassword",
                UUID.randomUUID().toString(),
                "memberId",
                1)),
        400,
        "INVALID_INPUT");
  }

  @Test
  void passwordChangeFailureLimitReturnsRetryAfterAndDoesNotShareLoginScope() throws Exception {
    var a = account();
    var browser = new Browser();
    browser.csrf();
    browser.login(a);
    var body = Map.of("currentPassword", UUID.randomUUID().toString(), "newPassword", a.password());
    for (int i = 0; i < 5; i++)
      error(browser.post("/api/members/me/password", body), 403, "INVALID_CURRENT_PASSWORD");
    var blocked =
        browser.post(
            "/api/members/me/password",
            Map.of("currentPassword", a.password(), "newPassword", a.password()));
    error(blocked, 429, "RATE_LIMITED");
    assertThat(Long.parseLong(blocked.headers().firstValue("retry-after").orElseThrow()))
        .isBetween(1L, 900L);
    browser.login(a);
  }

  @Test
  void allPasswordEndpointsApplyTwentyRequestIpQuotaWithRetryAfter() throws Exception {
    var browser = new Browser();
    browser.csrf();
    for (String path :
        new String[] {
          "/api/auth/password-resets/request",
          "/api/auth/password-resets/confirm",
          "/api/members/me/password"
        }) {
      for (int i = 0; i < 20; i++) {
        var response = browser.post(path, Map.of(), null, null, null);
        assertThat(response.statusCode()).isEqualTo(403);
      }
      var blocked = browser.post(path, Map.of(), null, null, null);
      error(blocked, 429, "RATE_LIMITED");
      assertThat(Long.parseLong(blocked.headers().firstValue("retry-after").orElseThrow()))
          .isBetween(1L, 3600L);
    }
  }

  @Test
  void realFakeSignupWithdrawsOverHttpsAndEncodedDeleteHasIdenticalProtection() throws Exception {
    String email = UUID.randomUUID() + "@example.invalid", password = UUID.randomUUID().toString();
    var browser = new Browser();
    browser.csrf();
    assertThat(
            browser
                .post("/api/auth/registrations", Map.of("email", email, "password", password))
                .statusCode())
        .isEqualTo(202);
    var challenge = browser.post("/api/auth/email-verifications/request", Map.of("email", email));
    assertThat(challenge.statusCode()).isEqualTo(202);
    String id = json.readTree(challenge.body()).get("challengeId").asText();
    String code = deliveredCode("SIGNUP_VERIFY");
    assertThat(
            browser
                .post(
                    "/api/auth/email-verifications/confirm",
                    Map.of("email", email, "password", password, "challengeId", id, "code", code))
                .statusCode())
        .isEqualTo(204);
    long member =
        jdbc.queryForObject("select id from member where email_normalized=?", Long.class, email);
    browser.login(new Account(member, email, password));
    var body = Map.of("password", password);
    for (String path : new String[] {"/api/members/me", "/api/members/%6de", "/%61pi/members/me"}) {
      error(browser.delete(path, body, null, browser.csrf, browser.access), 403, "CSRF_REJECTED");
      error(
          browser.delete(
              path, body, "https://localhost:5443.evil.invalid", browser.csrf, browser.access),
          403,
          "CSRF_REJECTED");
      error(
          browser.delete(path, body, "https://localhost:5443", null, browser.access),
          403,
          "CSRF_REJECTED");
      error(
          browser.delete(path, body, "https://localhost:5443", browser.csrf, null),
          401,
          "UNAUTHENTICATED");
    }
    error(
        browser.delete(
            "/api/members/me",
            Map.of("password", password, "memberId", member + 1),
            "https://localhost:5443",
            browser.csrf,
            browser.access),
        400,
        "INVALID_INPUT");
    error(
        browser.delete(
            "/api/members/me",
            Map.of("password", password, "organizationId", member + 1),
            "https://localhost:5443",
            browser.csrf,
            browser.access),
        400,
        "INVALID_INPUT");
    error(
        browser.delete(
            "/api/members/me",
            Map.of("password", UUID.randomUUID().toString()),
            "https://localhost:5443",
            browser.csrf,
            browser.access),
        401,
        "UNAUTHENTICATED");
    for (Object invalid :
        java.util.List.of(
            123456789012L,
            true,
            java.util.List.of("synthetic"),
            java.util.Map.of("synthetic", true)))
      error(
          browser.delete(
              "/api/members/me",
              Map.of("password", invalid),
              "https://localhost:5443",
              browser.csrf,
              browser.access),
          400,
          "INVALID_INPUT");
    var deleted =
        browser.delete(
            "/api/members/%6de", body, "https://localhost:5443", browser.csrf, browser.access);
    cleared(deleted);
    browser.csrf();
    error(
        browser.delete(
            "/api/members/me", body, "https://localhost:5443", browser.csrf, browser.access),
        401,
        "UNAUTHENTICATED");
    error(browser.post("/api/auth/refresh", Map.of()), 401, "UNAUTHENTICATED");
    error(
        browser.post("/api/auth/login", Map.of("email", email, "password", password)),
        401,
        "UNAUTHENTICATED");
    error(
        browser.post(
            "/api/members/me/password",
            Map.of("currentPassword", password, "newPassword", UUID.randomUUID().toString())),
        401,
        "UNAUTHENTICATED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from idempotency_record where actor_member_id=?",
                Long.class,
                member))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select email_normalized is null and password_hash is null from member where id=?",
                Boolean.class,
                member))
        .isTrue();
    String output = console.toString(StandardCharsets.UTF_8);
    assertThat(
            !output.contains(email)
                && !output.contains(password)
                && !output.contains(browser.access)
                && !output.contains(browser.csrf))
        .isTrue();
  }

  private record Account(long id, String email, String password) {
    @Override
    public String toString() {
      return "Account[redacted]";
    }
  }
}
