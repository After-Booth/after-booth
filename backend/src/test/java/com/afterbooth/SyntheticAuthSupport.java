package com.afterbooth;

import com.afterbooth.global.security.JwtService;
import java.security.KeyPairGenerator;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;

public final class SyntheticAuthSupport {
  private static final Map<String, Object> PROPERTIES = generate();

  private SyntheticAuthSupport() {}

  private static Map<String, Object> generate() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var keys = generator.generateKeyPair();
      return Map.of(
          "afterbooth.auth.jwt.issuer",
          "synthetic-issuer",
          "afterbooth.auth.jwt.audience",
          "synthetic-audience",
          "afterbooth.auth.jwt.current-kid",
          "synthetic",
          "afterbooth.auth.jwt.private-key",
          Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()),
          "afterbooth.auth.jwt.public-keys.synthetic",
          Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
    } catch (Exception e) {
      throw new IllegalStateException("Synthetic key generation failed");
    }
  }

  public static void configure(DynamicPropertyRegistry registry) {
    PROPERTIES.forEach((name, value) -> registry.add(name, () -> value));
  }

  public static void configure(Map<String, Object> properties) {
    properties.putAll(PROPERTIES);
  }

  public static String bearer(
      JdbcTemplate jdbc, JwtService jwt, java.time.Clock clock, String hash) {
    long id =
        jdbc.queryForObject(
            "insert into member(email_normalized,password_hash,state,auth_version,created_at,verified_at) values(?,?,'ACTIVE',0,clock_timestamp(),clock_timestamp()) returning id",
            Long.class,
            UUID.randomUUID() + "@example.invalid",
            hash);
    UUID sid = UUID.randomUUID();
    Instant now = Instant.now();
    jdbc.update(
        "insert into refresh_session(id,member_id,auth_version,created_at,expires_at) values(?,?,0,?,?)",
        sid,
        id,
        Timestamp.from(now),
        Timestamp.from(now.plusSeconds(604800)));
    return "Bearer " + jwt.issue(id, sid, 0, clock.instant());
  }
}
