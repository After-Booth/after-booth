package com.afterbooth.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class JwtServiceTest {
  private java.security.KeyPair pair;
  private JwtProperties properties;
  private final JsonMapper json = JsonMapper.builder().build();
  private JwtService jwt;
  private JdbcTemplate jdbc;
  private final Instant now = Instant.parse("2026-11-03T00:00:00Z");
  private final UUID session = UUID.randomUUID();

  @BeforeEach
  void prepare() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    pair = generator.generateKeyPair();
    properties =
        new JwtProperties(
            "synthetic-issuer",
            "synthetic-audience",
            "current",
            Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
            Map.of("current", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())));
    jdbc = mock(JdbcTemplate.class);
    jwt = new JwtService(properties, JsonMapper.builder().build(), jdbc);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any(), any())).thenReturn(true);
  }

  @Test
  void roundTripAndExactLifetime() {
    String token = jwt.issue(42, session, 3, now);
    assertThat(jwt.verify(token, now.plusSeconds(599)))
        .isEqualTo(new AuthenticatedMember(42, session, 3));
    rejected(token, now.plusSeconds(600), ErrorCode.UNAUTHENTICATED);
    rejected(token, now.minusSeconds(1), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void alteredSignatureAndDatabaseRevocationAreRejected() {
    String token = jwt.issue(42, session, 3, now);
    String[] parts = token.split("\\.");
    byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
    signature[0] ^= 1;
    rejected(
        parts[0]
            + "."
            + parts[1]
            + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(signature),
        now,
        ErrorCode.UNAUTHENTICATED);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any(), any()))
        .thenReturn(false);
    rejected(token, now, ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void databaseFailureFailsClosed() {
    String token = jwt.issue(42, session, 3, now);
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any(), any()))
        .thenThrow(new DataAccessResourceFailureException("synthetic"));
    rejected(token, now, ErrorCode.AUTH_UNAVAILABLE);
  }

  @Test
  void absentConfigurationIsUnavailable() {
    var unavailable =
        new JwtService(
            new JwtProperties(null, null, null, null, null), JsonMapper.builder().build(), jdbc);
    assertThatThrownBy(() -> unavailable.issue(42, session, 3, now))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.AUTH_UNAVAILABLE));
  }

  @Test
  void signedWrongIssuerAudienceAlgorithmsAndKeysAreRejected() throws Exception {
    for (String field : java.util.List.of("iss", "aud")) {
      var claims = claims();
      claims.put(field, "wrong");
      rejected(sign(header(), claims), now, ErrorCode.UNAUTHENTICATED);
    }
    for (String algorithm : java.util.List.of("none", "HS256", "RS512")) {
      var header = header();
      header.put("alg", algorithm);
      rejected(sign(header, claims()), now, ErrorCode.UNAUTHENTICATED);
    }
    var unknown = header();
    unknown.put("kid", "unknown");
    rejected(sign(unknown, claims()), now, ErrorCode.UNAUTHENTICATED);
    var external = header();
    external.put("jku", "https://example.invalid/keys");
    rejected(sign(external, claims()), now, ErrorCode.UNAUTHENTICATED);
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    rejected(
        sign(
            json.writeValueAsString(header()),
            json.writeValueAsString(claims()),
            generator.generateKeyPair()),
        now,
        ErrorCode.UNAUTHENTICATED);
    String token = sign(header(), claims());
    var changed = claims();
    changed.put("authVersion", 4);
    String[] parts = token.split("\\.");
    rejected(
        parts[0] + "." + encode(json.writeValueAsBytes(changed)) + "." + parts[2],
        now,
        ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void everyRequiredClaimAndItsTypeAreValidated() throws Exception {
    for (String field : claims().keySet()) {
      var missing = claims();
      missing.remove(field);
      rejected(sign(header(), missing), now, ErrorCode.UNAUTHENTICATED);
      var wrong = claims();
      wrong.put(field, java.util.List.of("wrong"));
      rejected(sign(header(), wrong), now, ErrorCode.UNAUTHENTICATED);
    }
    for (String field : java.util.List.of("authVersion", "iat", "exp")) {
      var wrong = claims();
      wrong.put(field, "1");
      rejected(sign(header(), wrong), now, ErrorCode.UNAUTHENTICATED);
      wrong = claims();
      wrong.put(field, 1.5);
      rejected(sign(header(), wrong), now, ErrorCode.UNAUTHENTICATED);
      wrong = claims();
      wrong.put(field, new java.math.BigInteger("18446744073709551616"));
      rejected(sign(header(), wrong), now, ErrorCode.UNAUTHENTICATED);
    }
    for (String field : java.util.List.of("sid", "jti")) {
      var wrong = claims();
      wrong.put(field, "1-1-1-1-1");
      rejected(sign(header(), wrong), now, ErrorCode.UNAUTHENTICATED);
    }
    for (String subject : java.util.List.of("0", "-1", "01", "9223372036854775808")) {
      var wrong = claims();
      wrong.put("sub", subject);
      rejected(sign(header(), wrong), now, ErrorCode.UNAUTHENTICATED);
    }
    for (String field : java.util.List.of("authVersion", "iat", "exp")) {
      var wrong = claims();
      wrong.put(field, -1);
      rejected(sign(header(), wrong), now, ErrorCode.UNAUTHENTICATED);
    }
    var wrong = claims();
    wrong.put("iat", Long.MAX_VALUE);
    wrong.put("exp", Long.MAX_VALUE);
    rejected(sign(header(), wrong), now, ErrorCode.UNAUTHENTICATED);
    wrong = claims();
    wrong.put("exp", now.getEpochSecond() + 601);
    rejected(sign(header(), wrong), now, ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void duplicateKeysAndNoncanonicalEncodingAreRejected() throws Exception {
    String claims = json.writeValueAsString(claims());
    String duplicate = claims.substring(0, claims.length() - 1) + ",\"sub\":\"42\"}";
    rejected(
        sign(json.writeValueAsString(header()), duplicate, pair), now, ErrorCode.UNAUTHENTICATED);
    String header = "{\"alg\":\"none\",\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"current\"}";
    rejected(sign(header, claims, pair), now, ErrorCode.UNAUTHENTICATED);
    rejected(
        sign(json.writeValueAsString(header()), claims + " {}", pair),
        now,
        ErrorCode.UNAUTHENTICATED);
    rejected(
        sign(json.writeValueAsString(header()) + " {}", claims, pair),
        now,
        ErrorCode.UNAUTHENTICATED);
    String token = sign(header(), claims());
    String[] parts = token.split("\\.");
    rejected(parts[0] + "=." + parts[1] + "." + parts[2], now, ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void configuredRotationOverlapAcceptsBothKeysAndRetirementRejectsOldKey() throws Exception {
    String old = jwt.issue(42, session, 3, now);
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var next = generator.generateKeyPair();
    var overlap =
        new JwtProperties(
            properties.issuer(),
            properties.audience(),
            "next",
            base64(next.getPrivate().getEncoded()),
            Map.of(
                "current",
                base64(pair.getPublic().getEncoded()),
                "next",
                base64(next.getPublic().getEncoded())));
    var rotated = new JwtService(overlap, json, jdbc);
    String fresh = rotated.issue(42, session, 3, now);
    assertThat(rotated.verify(old, now).memberId() == 42).isTrue();
    assertThat(rotated.verify(fresh, now).memberId() == 42).isTrue();
    var retired =
        new JwtService(
            new JwtProperties(
                overlap.issuer(),
                overlap.audience(),
                "next",
                overlap.privateKey(),
                Map.of("next", base64(next.getPublic().getEncoded()))),
            json,
            jdbc);
    assertThatThrownBy(() -> retired.verify(old, now))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
  }

  @Test
  void malformedMismatchedAndWeakConfigurationFailClosed() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var other = generator.generateKeyPair();
    generator.initialize(1024);
    var weak = generator.generateKeyPair();
    var invalid =
        java.util.List.of(
            new JwtProperties("issuer", "audience", "current", "invalid", properties.publicKeys()),
            new JwtProperties(
                "issuer",
                "audience",
                "current",
                properties.privateKey(),
                Map.of("current", "invalid")),
            new JwtProperties(
                "issuer",
                "audience",
                "current",
                properties.privateKey(),
                Map.of("current", base64(other.getPublic().getEncoded()))),
            new JwtProperties(
                "issuer",
                "audience",
                "current",
                base64(weak.getPrivate().getEncoded()),
                Map.of("current", base64(weak.getPublic().getEncoded()))));
    for (var config : invalid) {
      var service = new JwtService(config, json, jdbc);
      assertThatThrownBy(() -> service.issue(42, session, 3, now))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.AUTH_UNAVAILABLE));
    }
  }

  private java.util.Map<String, Object> header() {
    return new java.util.HashMap<>(Map.of("alg", "RS256", "typ", "JWT", "kid", "current"));
  }

  private java.util.Map<String, Object> claims() {
    return new java.util.HashMap<>(
        Map.of(
            "sub",
            "42",
            "sid",
            session.toString(),
            "authVersion",
            3,
            "iss",
            properties.issuer(),
            "aud",
            properties.audience(),
            "iat",
            now.getEpochSecond(),
            "exp",
            now.getEpochSecond() + 600,
            "jti",
            UUID.randomUUID().toString()));
  }

  private String sign(Map<String, Object> header, Map<String, Object> claims) throws Exception {
    return sign(json.writeValueAsString(header), json.writeValueAsString(claims), pair);
  }

  private String sign(String header, String claims, java.security.KeyPair key) throws Exception {
    String input =
        encode(header.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            + "."
            + encode(claims.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var signer = java.security.Signature.getInstance("SHA256withRSA");
    signer.initSign(key.getPrivate());
    signer.update(input.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    return input + "." + encode(signer.sign());
  }

  private static String encode(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String base64(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  private void rejected(String token, Instant at, ErrorCode expected) {
    assertThatThrownBy(() -> jwt.verify(token, at))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(expected));
  }
}
