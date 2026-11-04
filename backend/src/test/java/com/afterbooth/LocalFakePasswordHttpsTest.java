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
  private static final com.afterbooth.ai.AiGateway syntheticAi =
      org.mockito.Mockito.mock(com.afterbooth.ai.AiGateway.class);

  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  static class SyntheticAi {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    com.afterbooth.ai.AiGateway syntheticTransactionGateway() {
      return syntheticAi;
    }
  }

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
          new SpringApplicationBuilder(AfterboothApplication.class, SyntheticAi.class)
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
    org.mockito.Mockito.reset(syntheticAi);
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
              .header("Idempotency-Key", UUID.randomUUID().toString())
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

    HttpResponse<String> get(String path) throws Exception {
      var request =
          HttpRequest.newBuilder(uri(path))
              .header("Authorization", "Bearer " + access)
              .GET()
              .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> photo(String path, Long eventId, byte[] image) throws Exception {
      String boundary = "synthetic" + UUID.randomUUID();
      var body = new ByteArrayOutputStream();
      if (eventId != null)
        body.write(
            ("--"
                    + boundary
                    + "\r\nContent-Disposition: form-data; name=\"eventId\"\r\n\r\n"
                    + eventId
                    + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
      body.write(
          ("--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"synthetic.png\"\r\nContent-Type: image/png\r\n\r\n")
              .getBytes(StandardCharsets.UTF_8));
      body.write(image);
      body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
      return client.send(
          HttpRequest.newBuilder(uri(path))
              .header("Authorization", "Bearer " + access)
              .header("Idempotency-Key", UUID.randomUUID().toString())
              .header("Content-Type", "multipart/form-data; boundary=" + boundary)
              .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    void login(Account a) throws Exception {
      var response = post("/api/auth/login", Map.of("email", a.email(), "password", a.password()));
      assertThat(response.statusCode()).isEqualTo(200);
      absorb(response);
      access = json.readTree(response.body()).get("accessToken").asText();
      csrf();
    }
  }

  private Account registerThroughLocalFake(Browser browser) throws Exception {
    browser.csrf();
    String email = UUID.randomUUID() + "@example.invalid", password = UUID.randomUUID().toString();
    assertThat(
            browser
                .post("/api/auth/registrations", Map.of("email", email, "password", password))
                .statusCode())
        .isEqualTo(202);
    console.reset();
    var issued = browser.post("/api/auth/email-verifications/request", Map.of("email", email));
    assertThat(issued.statusCode()).isEqualTo(202);
    String challenge = json.readTree(issued.body()).get("challengeId").asText();
    String code = deliveredCode("SIGNUP_VERIFY");
    assertThat(
            browser
                .post(
                    "/api/auth/email-verifications/confirm",
                    Map.of(
                        "email",
                        email,
                        "password",
                        password,
                        "challengeId",
                        challenge,
                        "code",
                        code))
                .statusCode())
        .isEqualTo(204);
    long id =
        jdbc.queryForObject("select id from member where email_normalized=?", Long.class, email);
    var account = new Account(id, email, password);
    browser.login(account);
    return account;
  }

  @Test
  void authenticatedTwoMemberTransactionCompletesHandoverAndStatsWithSyntheticAi()
      throws Exception {
    var giver = new Browser();
    var receiver = new Browser();
    var giverAccount = registerThroughLocalFake(giver);
    var receiverAccount = registerThroughLocalFake(receiver);
    long receiverOrg =
        json.readTree(receiver.get("/api/members/me").body()).get("organizationId").asLong();
    var eventResponse =
        giver.post(
            "/api/events",
            Map.of(
                "name",
                "Synthetic HTTPS event",
                "venue",
                "Synthetic venue",
                "teardownDeadline",
                java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(9))
                    .plusDays(1)
                    .toString()));
    assertThat(eventResponse.statusCode()).isEqualTo(201);
    long event = json.readTree(eventResponse.body()).get("id").asLong();
    assertThat(receiver.get("/api/events/" + event).statusCode()).isEqualTo(404);
    byte[] image;
    try (var bytes = new ByteArrayOutputStream()) {
      javax.imageio.ImageIO.write(
          new java.awt.image.BufferedImage(20, 20, java.awt.image.BufferedImage.TYPE_INT_RGB),
          "png",
          bytes);
      image = bytes.toByteArray();
    }
    var box =
        new com.afterbooth.ai.SamSegment(
            1,
            new com.afterbooth.ai.PixelBox(2, 2, 11, 11),
            0.99,
            new com.afterbooth.ai.SamSegment.Mask(0, 0, "AQ=="));
    org.mockito.Mockito.when(syntheticAi.segment(org.mockito.ArgumentMatchers.any()))
        .thenReturn(java.util.List.of(box));
    org.mockito.Mockito.when(syntheticAi.chat(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new com.afterbooth.ai.ChatResponse(
                "{\"lines\":[{\"category\":\"STORAGE_BIN\",\"count\":1,\"boxes\":[{\"x\":0.1,\"y\":0.1,\"width\":0.5,\"height\":0.5}],\"sizeCm\":{\"width\":10,\"depth\":10,\"height\":10},\"condition\":\"GOOD\",\"confidence\":0.99}]}",
                java.util.List.of(),
                "stop",
                null));
    var uploaded = giver.photo("/api/intake/drafts", event, image);
    assertThat(uploaded.statusCode()).isEqualTo(202);
    long draft = json.readTree(uploaded.body()).get("id").asLong();
    tools.jackson.databind.JsonNode ready = null;
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < until) {
      ready = json.readTree(giver.get("/api/intake/drafts/" + draft).body());
      if (ready.path("status").asText().equals("READY")) break;
      Thread.sleep(20);
    }
    assertThat(ready.path("status").asText()).isEqualTo("READY");
    assertThat(ready.path("lines").get(0).path("label").asText()).isEqualTo("Storage bin");
    assertThat(receiver.get("/api/intake/drafts/" + draft).statusCode()).isEqualTo(404);
    var confirmed =
        giver.post(
            "/api/intake/drafts/" + draft + "/confirm",
            Map.of(
                "lines",
                java.util.List.of(
                    Map.of(
                        "lineId",
                        ready.path("lines").get(0).path("lineId").asLong(),
                        "category",
                        "STORAGE_BIN",
                        "quantity",
                        2,
                        "sizeCm",
                        Map.of("width", 10, "depth", 10, "height", 10),
                        "condition",
                        "GOOD"))));
    assertThat(confirmed.statusCode()).isEqualTo(201);
    long item = json.readTree(confirmed.body()).get(0).get("id").asLong();
    var requestResponse =
        receiver.post(
            "/api/pickup-requests",
            Map.of(
                "organizationId",
                receiverOrg,
                "category",
                "STORAGE_BIN",
                "quantity",
                1,
                "vehicleType",
                "VAN",
                "purpose",
                "Synthetic event storage"));
    assertThat(requestResponse.statusCode()).isEqualTo(201);
    long request = json.readTree(requestResponse.body()).get("id").asLong();
    assertThat(giver.get("/api/pickup-requests/" + request).statusCode()).isEqualTo(404);
    org.mockito.Mockito.when(syntheticAi.chat(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new com.afterbooth.ai.ChatResponse(
                null,
                java.util.List.of(
                    new com.afterbooth.ai.ToolCall("search", "searchCandidates", "{}")),
                "tool_calls",
                null),
            new com.afterbooth.ai.ChatResponse(
                null,
                java.util.List.of(
                    new com.afterbooth.ai.ToolCall(
                        "fit", "checkVehicleFit", "{\"itemId\":" + item + "}")),
                "tool_calls",
                null),
            new com.afterbooth.ai.ChatResponse(
                null,
                java.util.List.of(
                    new com.afterbooth.ai.ToolCall(
                        "hold", "holdQuantity", "{\"itemId\":" + item + ",\"quantity\":1}")),
                "tool_calls",
                null),
            new com.afterbooth.ai.ChatResponse(
                "One storage bin is on hold. Please confirm your reservation.",
                java.util.List.of(),
                "stop",
                null));
    var allocated = receiver.post("/api/pickup-requests/" + request + "/allocation", Map.of());
    assertThat(allocated.statusCode()).isEqualTo(200);
    long reservation =
        json.readTree(allocated.body()).get("reservations").get(0).get("id").asLong();
    assertThat(
            receiver.post("/api/reservations/" + reservation + "/confirm", Map.of()).statusCode())
        .isEqualTo(200);
    error(
        giver.delete(
            "/api/members/me",
            Map.of("password", giverAccount.password()),
            "https://localhost:5443",
            giver.csrf,
            giver.access),
        409,
        "WITHDRAWAL_BLOCKED");
    error(
        receiver.delete(
            "/api/members/me",
            Map.of("password", receiverAccount.password()),
            "https://localhost:5443",
            receiver.csrf,
            receiver.access),
        409,
        "WITHDRAWAL_BLOCKED");
    org.mockito.Mockito.when(syntheticAi.chat(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new com.afterbooth.ai.ChatResponse(
                "{\"count\":0,\"boxes\":[]}", java.util.List.of(), "stop", null));
    var mismatch = giver.photo("/api/reservations/" + reservation + "/handover", null, image);
    assertThat(mismatch.statusCode()).isEqualTo(200);
    assertThat(json.readTree(mismatch.body()).get("status").asText()).isEqualTo("MISMATCH");
    assertThat(giver.post("/api/reservations/" + reservation + "/cancel", Map.of()).statusCode())
        .isEqualTo(403);
    org.mockito.Mockito.when(syntheticAi.chat(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new com.afterbooth.ai.ChatResponse(
                "{\"count\":1,\"boxes\":[{\"x\":0.1,\"y\":0.1,\"width\":0.5,\"height\":0.5}]}",
                java.util.List.of(),
                "stop",
                null));
    var completed = giver.photo("/api/reservations/" + reservation + "/handover", null, image);
    assertThat(completed.statusCode()).isEqualTo(200);
    assertThat(json.readTree(completed.body()).get("status").asText()).isEqualTo("COMPLETED");
    assertThat(
            json.readTree(receiver.get("/api/reservations/" + reservation).body())
                .get("status")
                .asText())
        .isEqualTo("HANDED_OVER");
    var stats = giver.get("/api/stats/reuse?eventId=" + event);
    assertThat(stats.statusCode()).isEqualTo(200);
    assertThat(json.readTree(stats.body()).get("totalReused").asLong()).isEqualTo(1);
    assertThat(json.readTree(stats.body()).get("reusedVolumeLiters").asLong()).isEqualTo(1);
    var secondRequest =
        receiver.post(
            "/api/pickup-requests",
            Map.of(
                "organizationId",
                receiverOrg,
                "category",
                "STORAGE_BIN",
                "quantity",
                1,
                "vehicleType",
                "VAN"));
    assertThat(secondRequest.statusCode()).isEqualTo(201);
    long secondRequestId = json.readTree(secondRequest.body()).get("id").asLong();
    var secondHold =
        receiver.post(
            "/api/reservations",
            Map.of("requestId", secondRequestId, "itemId", item, "quantity", 1));
    assertThat(secondHold.statusCode()).isEqualTo(201);
    long cancelled = json.readTree(secondHold.body()).get("id").asLong();
    assertThat(receiver.post("/api/reservations/" + cancelled + "/confirm", Map.of()).statusCode())
        .isEqualTo(200);
    org.mockito.Mockito.when(syntheticAi.chat(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new com.afterbooth.ai.ChatResponse(
                "{\"count\":0,\"boxes\":[]}", java.util.List.of(), "stop", null));
    assertThat(
            giver.photo("/api/reservations/" + cancelled + "/handover", null, image).statusCode())
        .isEqualTo(200);
    assertThat(giver.post("/api/reservations/" + cancelled + "/cancel", Map.of()).statusCode())
        .isEqualTo(403);
    var cancellation = receiver.post("/api/reservations/" + cancelled + "/cancel", Map.of());
    assertThat(cancellation.statusCode()).isEqualTo(200);
    assertThat(json.readTree(cancellation.body()).get("status").asText()).isEqualTo("RELEASED");
    cleared(
        giver.delete(
            "/api/members/me",
            Map.of("password", giverAccount.password()),
            "https://localhost:5443",
            giver.csrf,
            giver.access));
    assertThat(receiver.get("/api/reservations/" + reservation).statusCode()).isEqualTo(200);
    jdbc.update(
        "update reservation set retention_until=statement_timestamp()-interval '1 second' where id=?",
        reservation);
    var expired = receiver.get("/api/reservations/" + reservation);
    error(expired, 404, "NOT_FOUND");
    assertThat(json.readTree(expired.body()).get("message").asText())
        .isEqualTo("The requested resource was not found.");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from reservation where id=?", Integer.class, reservation))
        .isEqualTo(1);
    cleared(
        receiver.delete(
            "/api/members/me",
            Map.of("password", receiverAccount.password()),
            "https://localhost:5443",
            receiver.csrf,
            receiver.access));
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
    assertThat(response.headers().firstValue("cache-control").orElseThrow()).contains("no-store");
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
    assertThat(response.headers().firstValue("cache-control").orElseThrow()).contains("no-store");
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
    org.mockito.Mockito.reset(syntheticAi);
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
