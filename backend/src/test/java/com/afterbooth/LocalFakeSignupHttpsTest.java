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
    SyntheticAuthSupport.configure(properties);
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
        verifySessionFlow(
            client,
            json,
            jdbc,
            context.getBean(com.afterbooth.global.security.JwtService.class),
            port,
            email,
            finalPassword,
            cookie,
            token);
      }
    } finally {
      System.setOut(original);
      console.reset();
    }
  }

  private static void verifySessionFlow(
      HttpClient client,
      JsonMapper json,
      JdbcTemplate jdbc,
      com.afterbooth.global.security.JwtService jwt,
      int port,
      String email,
      String password,
      String originalCookie,
      String originalToken)
      throws Exception {
    var login =
        post(
            client,
            json,
            port,
            "/api/auth/login",
            originalCookie,
            originalToken,
            Map.of("email", email, "password", password));
    assertThat(login.statusCode()).isEqualTo(200);
    assertThat(login.headers().firstValue("cache-control")).contains("no-store");
    assertThat(json.readTree(login.body()).size()).isEqualTo(3);
    String access = json.readTree(login.body()).get("accessToken").asText();
    var cookies = new HashMap<String, String>();
    absorb(cookies, login);
    assertThat(login.headers().allValues("set-cookie").size()).isEqualTo(2);
    assertThat(
            login.headers().allValues("set-cookie").stream()
                .allMatch(
                    h ->
                        h.contains("HttpOnly")
                            && h.contains("Secure")
                            && h.contains("SameSite=Lax")
                            && h.contains("Path=/")
                            && !h.contains("Domain=")))
        .isTrue();
    assertThat(protectedGet(client, port, access).statusCode()).isEqualTo(200);
    long memberId =
        jdbc.queryForObject("select id from member where email_normalized=?", Long.class, email);
    UUID sid =
        jdbc.queryForObject(
            "select id from refresh_session where member_id=?", UUID.class, memberId);
    String expired = jwt.issue(memberId, sid, 0, java.time.Instant.now().minusSeconds(601));
    assertThat(protectedGet(client, port, expired).statusCode()).isEqualTo(401);
    jdbc.update("update member set auth_version=auth_version+1 where id=?", memberId);
    assertThat(protectedGet(client, port, access).statusCode()).isEqualTo(401);
    jdbc.update("update member set auth_version=0 where id=?", memberId);
    jdbc.update("update member set state='PENDING_VERIFICATION' where id=?", memberId);
    assertThat(protectedGet(client, port, access).statusCode()).isEqualTo(401);
    jdbc.update("update member set state='ACTIVE' where id=?", memberId);
    String otherEmail = UUID.randomUUID() + "@example.invalid";
    long other =
        jdbc.queryForObject(
            "insert into member(email_normalized,password_hash,state,auth_version,created_at,verified_at) values(?,?,'ACTIVE',0,statement_timestamp(),statement_timestamp()) returning id",
            Long.class,
            otherEmail,
            jdbc.queryForObject(
                "select password_hash from member where id=?", String.class, memberId));
    String crossMember = jwt.issue(other, sid, 0, java.time.Instant.now());
    assertThat(protectedGet(client, port, crossMember).statusCode()).isEqualTo(401);
    jdbc.execute("alter table refresh_session rename to synthetic_unavailable_session");
    try {
      var unavailable = protectedGet(client, port, access);
      assertThat(unavailable.statusCode()).isEqualTo(503);
      assertThat(json.readTree(unavailable.body()).get("code").asText())
          .isEqualTo("AUTH_UNAVAILABLE");
    } finally {
      jdbc.execute("alter table synthetic_unavailable_session rename to refresh_session");
    }
    assertThat(protectedGet(client, port, access).statusCode()).isEqualTo(200);

    var anonymous =
        client.send(
            HttpRequest.newBuilder(uri(port, "/api/organizations")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(anonymous.statusCode()).isEqualTo(401);
    // Refresh cookie alone never authenticates a domain request.
    assertThat(
            client
                .send(
                    HttpRequest.newBuilder(uri(port, "/api/organizations"))
                        .header("Cookie", pairs(cookies))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode())
        .isEqualTo(401);
    String csrf = fetchCsrf(client, json, port, cookies);
    for (String path :
        new String[] {
          "/api/auth/login",
          "/api/auth/%6cogin",
          "/api/auth/refresh",
          "/api/auth/%72efresh",
          "/api/%61uth/logout",
          "/api/auth/logout-all"
        }) {
      var missing =
          client.send(
              HttpRequest.newBuilder(uri(port, path))
                  .header("Cookie", pairs(cookies))
                  .header("Authorization", "Bearer " + access)
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(missing.statusCode()).isEqualTo(403);
      assertThat(missing.headers().firstValue("cache-control")).contains("no-store");
      var wrong =
          client.send(
              HttpRequest.newBuilder(uri(port, path))
                  .header("Cookie", pairs(cookies))
                  .header("Authorization", "Bearer " + access)
                  .header("Origin", "https://localhost:5443.evil.invalid")
                  .header("X-CSRF-Token", csrf)
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(wrong.statusCode()).isEqualTo(403);
    }
    for (String path :
        new String[] {
          "/api/auth/login",
          "/api/auth/%6cogin",
          "/api/auth/refresh",
          "/api/%61uth/refresh",
          "/api/auth/logout",
          "/api/auth/logout-all"
        }) {
      var noCsrf =
          authPost(
              client,
              json,
              port,
              path,
              pairs(cookies),
              null,
              access,
              Map.of("email", email, "password", password));
      assertThat(noCsrf.statusCode()).isEqualTo(403);
      var wrongCsrf =
          authPost(
              client,
              json,
              port,
              path,
              pairs(cookies),
              UUID.randomUUID().toString(),
              access,
              Map.of("email", email, "password", password));
      assertThat(wrongCsrf.statusCode()).isEqualTo(403);
    }
    var refresh =
        authPost(
            client, json, port, "/api/auth/%72efresh", pairs(cookies), csrf, expired, Map.of());
    assertThat(refresh.statusCode()).isEqualTo(200);
    absorb(cookies, refresh);
    String renewed = json.readTree(refresh.body()).get("accessToken").asText();
    assertThat(protectedGet(client, port, renewed).statusCode()).isEqualTo(200);
    csrf = fetchCsrf(client, json, port, cookies);
    var logout =
        authPost(client, json, port, "/api/auth/logout", pairs(cookies), csrf, expired, Map.of());
    assertThat(logout.statusCode()).isEqualTo(204);
    assertDeletion(logout, 1);
    assertThat(protectedGet(client, port, renewed).statusCode()).isEqualTo(401);
    cookies.remove("__Host-afterbooth-refresh");
    assertThat(
            authPost(
                    client, json, port, "/api/auth/logout", pairs(cookies), csrf, expired, Map.of())
                .statusCode())
        .isEqualTo(204);
    var loginAgain =
        post(
            client,
            json,
            port,
            "/api/auth/login",
            pairs(cookies),
            csrf,
            Map.of("email", email, "password", password));
    assertThat(loginAgain.statusCode()).isEqualTo(200);
    absorb(cookies, loginAgain);
    String finalAccess = json.readTree(loginAgain.body()).get("accessToken").asText();
    csrf = fetchCsrf(client, json, port, cookies);
    var all =
        client.send(
            HttpRequest.newBuilder(uri(port, "/api/auth/logout-all"))
                .header("Cookie", pairs(cookies))
                .header("Origin", "https://localhost:5443")
                .header("X-CSRF-Token", csrf)
                .header("Authorization", "Bearer " + finalAccess)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(all.statusCode()).isEqualTo(204);
    assertDeletion(all, 2);
    assertThat(protectedGet(client, port, finalAccess).statusCode()).isEqualTo(401);
    assertThat(jdbc.queryForObject("select count(*) from idempotency_record", Long.class)).isZero();
    var noCookie = new HashMap<String, String>();
    String fresh = fetchCsrf(client, json, port, noCookie);
    var repeat =
        client.send(
            HttpRequest.newBuilder(uri(port, "/api/auth/logout-all"))
                .header("Cookie", pairs(noCookie))
                .header("Origin", "https://localhost:5443")
                .header("X-CSRF-Token", fresh)
                .header("Authorization", "Bearer " + finalAccess)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(repeat.statusCode()).isEqualTo(401);
    var extraLogin =
        authPost(
            client,
            json,
            port,
            "/api/auth/login",
            pairs(noCookie),
            fresh,
            null,
            Map.of("email", email, "password", password));
    assertThat(extraLogin.statusCode()).isEqualTo(200);
    absorb(noCookie, extraLogin);
    String beforeRefreshCookie = noCookie.get("__Host-afterbooth-refresh");
    String extraCsrf = fetchCsrf(client, json, port, noCookie);
    var extraRefresh =
        authPost(
            client, json, port, "/api/auth/refresh", pairs(noCookie), extraCsrf, expired, Map.of());
    assertThat(extraRefresh.statusCode()).isEqualTo(200);
    absorb(noCookie, extraRefresh);
    String afterRefreshCookie = noCookie.get("__Host-afterbooth-refresh");
    String extraAccess = json.readTree(extraRefresh.body()).get("accessToken").asText();
    extraCsrf = fetchCsrf(client, json, port, noCookie);
    noCookie.put("__Host-afterbooth-refresh", beforeRefreshCookie);
    var reuse =
        authPost(
            client, json, port, "/api/auth/refresh", pairs(noCookie), extraCsrf, null, Map.of());
    assertThat(reuse.statusCode()).isEqualTo(401);
    assertDeletion(reuse, 1);
    assertThat(protectedGet(client, port, extraAccess).statusCode()).isEqualTo(401);
    noCookie.put("__Host-afterbooth-refresh", afterRefreshCookie);
    assertThat(
            authPost(
                    client,
                    json,
                    port,
                    "/api/auth/refresh",
                    pairs(noCookie),
                    extraCsrf,
                    null,
                    Map.of())
                .statusCode())
        .isEqualTo(401);
    noCookie.clear();
    fresh = fetchCsrf(client, json, port, noCookie);
    assertThat(jdbc.queryForObject("select count(*) from idempotency_record", Long.class)).isZero();
    jdbc.update("delete from auth_rate_limit where scope='login:ip'");
    for (int attempt = 0; attempt < 20; attempt++) {
      var denied =
          authPost(
              client,
              json,
              port,
              attempt % 2 == 0 ? "/api/auth/login" : "/api/auth/%6cogin",
              pairs(noCookie),
              fresh,
              null,
              Map.of(
                  "email",
                  UUID.randomUUID() + "@example.invalid",
                  "password",
                  UUID.randomUUID().toString()));
      assertThat(denied.statusCode()).isEqualTo(401);
      assertThat(denied.headers().firstValue("cache-control")).contains("no-store");
    }
    var limited =
        authPost(
            client,
            json,
            port,
            "/api/%61uth/login",
            pairs(noCookie),
            fresh,
            null,
            Map.of(
                "email",
                UUID.randomUUID() + "@example.invalid",
                "password",
                UUID.randomUUID().toString()));
    assertThat(limited.statusCode()).isEqualTo(429);
    assertThat(Long.parseLong(limited.headers().firstValue("retry-after").orElseThrow()) > 0)
        .isTrue();
    assertThat(limited.headers().firstValue("cache-control")).contains("no-store");
    jdbc.update(
        "update csrf_session set created_at=statement_timestamp()-interval '2 hours',expires_at=statement_timestamp()-interval '1 hour' where member_id is null");
    assertThat(
            authPost(client, json, port, "/api/auth/logout", pairs(noCookie), fresh, null, Map.of())
                .statusCode())
        .isEqualTo(403);
  }

  private static HttpResponse<String> authPost(
      HttpClient client,
      JsonMapper json,
      int port,
      String path,
      String cookie,
      String csrf,
      String access,
      Object body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(uri(port, path))
            .header("Content-Type", "application/json")
            .header("Origin", "https://localhost:5443")
            .header("Cookie", cookie);
    if (csrf != null) request.header("X-CSRF-Token", csrf);
    if (access != null) request.header("Authorization", "Bearer " + access);
    return client.send(
        request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> protectedGet(HttpClient client, int port, String access)
      throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(port, "/api/organizations"))
            .header("Authorization", "Bearer " + access)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static void absorb(Map<String, String> cookies, HttpResponse<String> response) {
    for (String header : response.headers().allValues("set-cookie")) {
      String pair = header.split(";", 2)[0];
      int split = pair.indexOf('=');
      cookies.put(pair.substring(0, split), pair.substring(split + 1));
    }
  }

  private static String pairs(Map<String, String> cookies) {
    return cookies.entrySet().stream()
        .map(e -> e.getKey() + "=" + e.getValue())
        .collect(java.util.stream.Collectors.joining("; "));
  }

  private static String fetchCsrf(
      HttpClient client, JsonMapper json, int port, Map<String, String> cookies) throws Exception {
    var request = HttpRequest.newBuilder(uri(port, "/api/auth/csrf"));
    if (!cookies.isEmpty()) request.header("Cookie", pairs(cookies));
    var response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    absorb(cookies, response);
    return json.readTree(response.body()).get("csrfToken").asText();
  }

  private static void assertDeletion(HttpResponse<String> response, int count) {
    assertThat(response.headers().allValues("set-cookie").size()).isEqualTo(count);
    assertThat(
            response.headers().allValues("set-cookie").stream()
                .allMatch(
                    h ->
                        h.contains("Max-Age=0")
                            && h.contains("HttpOnly")
                            && h.contains("Secure")
                            && h.contains("SameSite=Lax")
                            && h.contains("Path=/")
                            && !h.contains("Domain=")))
        .isTrue();
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
