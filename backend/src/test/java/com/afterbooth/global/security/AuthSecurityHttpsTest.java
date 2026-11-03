package com.afterbooth.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.afterbooth.member.dto.RegisterMemberRequest;
import com.afterbooth.member.service.SignupService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
  @Autowired SignupService signup;
  @Autowired HmacKeyRing keys;
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
  void encodedSignupAliasesRejectInvalidSecurityWithoutBusinessMutations() throws Exception {
    var session = csrfSession();
    String email = syntheticEmail();
    String password = key();
    signup.register(new RegisterMemberRequest(email, password));
    String challenge = signup.requestVerification(email).challengeId();
    String code = syntheticCode();
    jdbc.update(
        "update auth_challenge set code_hmac=? where id=?",
        keys.currentDigest("challenge-code", challenge + "\0SIGNUP_VERIFY\0" + email + "\0" + code),
        challenge);
    jdbc.update("delete from auth_rate_limit");
    List<String> bodies =
        List.of(
            json.writeValueAsString(Map.of("email", syntheticEmail(), "password", password)),
            json.writeValueAsString(Map.of("email", email)),
            json.writeValueAsString(
                Map.of(
                    "email", email, "challengeId", challenge, "code", code, "password", password)));
    List<Long> before = businessCounts();
    List<String> passwordHashes =
        jdbc.queryForList("select password_hash from member order by id", String.class);
    for (int i = 0; i < encodedPaths().size(); i++) {
      String path = encodedPaths().get(i);
      String body = bodies.get(i);
      assertRejected(post(path, null, session[0], session[1], body));
      assertRejected(post(path, "https://attacker.invalid", session[0], session[1], body));
      assertRejected(post(path, ORIGIN, null, session[1], body));
      assertRejected(
          post(
              path,
              ORIGIN,
              session[0].split("=", 2)[0] + "=" + UUID.randomUUID(),
              session[1],
              body));
      assertRejected(post(path, ORIGIN, session[0], null, body));
      assertRejected(post(path, ORIGIN, session[0], key(), body));
      assertThat(businessCounts()).isEqualTo(before);
      assertThat(
              jdbc.queryForList("select password_hash from member order by id", String.class)
                  .equals(passwordHashes))
          .isTrue();
    }
  }

  @Test
  void encodedSignupAliasesAcceptValidSecurityAndDoNotCacheSuccessfulResponses() throws Exception {
    var session = csrfSession();
    String email = syntheticEmail();
    String password = key();
    var registered =
        post(
            encodedPaths().get(0),
            ORIGIN,
            session[0],
            session[1],
            json.writeValueAsString(Map.of("email", email, "password", password)));
    assertThat(registered.statusCode()).isEqualTo(202);
    assertThat(registered.headers().firstValue("cache-control")).contains("no-store");
    var issued =
        post(
            encodedPaths().get(1),
            ORIGIN,
            session[0],
            session[1],
            json.writeValueAsString(Map.of("email", email)));
    assertThat(issued.statusCode()).isEqualTo(202);
    assertThat(issued.headers().firstValue("cache-control")).contains("no-store");
    String challenge = json.readTree(issued.body()).get("challengeId").asText();
    String code = syntheticCode();
    jdbc.update(
        "update auth_challenge set code_hmac=? where id=?",
        keys.currentDigest("challenge-code", challenge + "\0SIGNUP_VERIFY\0" + email + "\0" + code),
        challenge);
    var confirmed =
        post(
            encodedPaths().get(2),
            ORIGIN,
            session[0],
            session[1],
            json.writeValueAsString(
                Map.of(
                    "email", email, "password", password, "challengeId", challenge, "code", code)));
    assertThat(confirmed.statusCode()).isEqualTo(204);
    assertThat(confirmed.headers().firstValue("cache-control")).contains("no-store");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from member where email_normalized=? and state='ACTIVE'",
                Long.class,
                email))
        .isEqualTo(1L);
  }

  @Test
  void canonicalAndEncodedAliasesShareEachApiIpQuota() throws Exception {
    List<String> canonical =
        List.of(
            "/api/auth/registrations",
            "/api/auth/email-verifications/request",
            "/api/auth/email-verifications/confirm");
    List<String> scopes = List.of("signup:registration:ip", "signup:issue:ip", "signup:confirm:ip");
    for (int i = 0; i < canonical.size(); i++) {
      for (int request = 0; request < 20; request++) {
        String path = request % 2 == 0 ? canonical.get(i) : encodedPaths().get(i);
        assertRejected(post(path, null, null, null));
      }
      var limited = post(encodedPaths().get(i), null, null, null);
      assertThat(limited.statusCode()).isEqualTo(429);
      assertThat(Long.parseLong(limited.headers().firstValue("retry-after").orElseThrow()))
          .isBetween(1L, 3600L);
      assertThat(post(canonical.get(i), null, null, null).statusCode()).isEqualTo(429);
      assertThat(
              jdbc.queryForObject(
                  "select sum(count) from auth_rate_limit where scope=?",
                  Long.class,
                  scopes.get(i)))
          .isEqualTo(22L);
    }
  }

  private static List<String> encodedPaths() {
    return List.of(
        "/%61pi/auth/registrations",
        "/api/%61uth/email-verifications/%72equest", "/api/auth/email-verifications/%63onfirm");
  }

  private String[] csrfSession() throws Exception {
    var issued =
        client.send(
            HttpRequest.newBuilder(url("/api/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(issued.statusCode()).isEqualTo(200);
    return new String[] {
      issued.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0],
      json.readTree(issued.body()).get("csrfToken").asText()
    };
  }

  private List<Long> businessCounts() {
    return List.of(
        jdbc.queryForObject("select count(*) from member", Long.class),
        jdbc.queryForObject("select count(*) from organization", Long.class),
        jdbc.queryForObject("select count(*) from auth_challenge", Long.class),
        jdbc.queryForObject("select count(*) from member where state='ACTIVE'", Long.class),
        jdbc.queryForObject("select coalesce(sum(attempts),0) from auth_challenge", Long.class),
        jdbc.queryForObject(
            "select count(*) from auth_challenge where consumed_at is not null", Long.class));
  }

  private static String syntheticEmail() {
    return UUID.randomUUID() + "@example.invalid";
  }

  private static String syntheticCode() {
    return String.format(java.util.Locale.ROOT, "%06d", new SecureRandom().nextInt(1_000_000));
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
    return post(path, origin, cookie, token, "{}");
  }

  private HttpResponse<String> post(
      String path, String origin, String cookie, String token, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(url(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
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
