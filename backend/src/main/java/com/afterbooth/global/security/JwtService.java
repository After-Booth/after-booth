package com.afterbooth.global.security;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class JwtService {
  private final JwtProperties properties;
  private final JsonMapper json;
  private final JdbcTemplate jdbc;
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

  public JwtService(JwtProperties properties, JsonMapper json, JdbcTemplate jdbc) {
    this.properties = properties;
    this.json =
        json.rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    this.jdbc = jdbc;
  }

  public String issue(long memberId, UUID sessionId, long authVersion, Instant now) {
    validateConfiguration();
    try {
      var factory = KeyFactory.getInstance("RSA");
      var key =
          factory.generatePrivate(
              new PKCS8EncodedKeySpec(Base64.getDecoder().decode(properties.privateKey())));
      if (((RSAKey) key).getModulus().bitLength() < 2048) throw unavailable();
      String header =
          encode(
              json.writeValueAsBytes(
                  Map.of("alg", "RS256", "typ", "JWT", "kid", properties.currentKid())));
      String claims =
          encode(
              json.writeValueAsBytes(
                  Map.of(
                      "sub",
                      Long.toString(memberId),
                      "sid",
                      sessionId.toString(),
                      "authVersion",
                      authVersion,
                      "iss",
                      properties.issuer(),
                      "aud",
                      properties.audience(),
                      "iat",
                      now.getEpochSecond(),
                      "exp",
                      now.getEpochSecond() + 600,
                      "jti",
                      UUID.randomUUID().toString())));
      String input = header + "." + claims;
      var signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(key);
      signer.update(input.getBytes(StandardCharsets.US_ASCII));
      String token = input + "." + encode(signer.sign());
      // Detect an incorrectly paired signing key before handing an unusable token to a client.
      verifySignature(token.split("\\.", -1), properties.currentKid());
      return token;
    } catch (Exception e) {
      throw unavailable();
    }
  }

  public AuthenticatedMember verify(String token, Instant now) {
    validateConfiguration();
    AuthenticatedMember principal;
    try {
      if (token == null
          || token.length() > 8192
          || !token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw invalid();
      String[] parts = token.split("\\.", -1);
      for (String part : parts)
        if (!encode(Base64.getUrlDecoder().decode(part)).equals(part)) throw invalid();
      JsonNode header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
      if (!header.isObject()
          || header.size() != 3
          || !"RS256".equals(text(header, "alg"))
          || !"JWT".equals(text(header, "typ"))) throw invalid();
      String kid = text(header, "kid");
      if (!properties.publicKeys().containsKey(kid)) throw invalid();
      verifySignature(parts, kid);
      JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
      if (!claims.isObject()
          || claims.size() != 8
          || !properties.issuer().equals(text(claims, "iss"))
          || !properties.audience().equals(text(claims, "aud"))) throw invalid();
      String subject = text(claims, "sub");
      if (!subject.matches("[1-9][0-9]*")) throw invalid();
      long memberId = Long.parseLong(subject);
      long version = integer(claims, "authVersion");
      long issued = integer(claims, "iat");
      long expires = integer(claims, "exp");
      if (version < 0
          || issued > now.getEpochSecond()
          || expires <= now.getEpochSecond()
          || issued < 0
          || issued > Long.MAX_VALUE - 600
          || expires != issued + 600) throw invalid();
      uuid(text(claims, "jti"));
      principal = new AuthenticatedMember(memberId, uuid(text(claims, "sid")), version);
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw invalid();
    }
    verifyPrincipal(principal, now);
    return principal;
  }

  public void verifyPrincipal(AuthenticatedMember principal, Instant now) {
    try {
      Boolean active =
          jdbc.queryForObject(
              """
          select exists(select 1 from member m join refresh_session s on s.member_id=m.id
          where m.id=? and m.state='ACTIVE' and m.auth_version=? and s.id=?
          and s.auth_version=m.auth_version and s.revoked_at is null and s.expires_at>clock_timestamp())
          """,
              Boolean.class,
              principal.memberId(),
              principal.authVersion(),
              principal.sessionId());
      if (!Boolean.TRUE.equals(active)) throw invalid();
    } catch (DataAccessException e) {
      throw unavailable();
    }
  }

  private void validateConfiguration() {
    if (properties.issuer() == null
        || properties.issuer().isBlank()
        || properties.audience() == null
        || properties.audience().isBlank()
        || properties.currentKid() == null
        || properties.currentKid().isBlank()
        || properties.privateKey() == null
        || properties.privateKey().isBlank()
        || properties.publicKeys() == null
        || !properties.publicKeys().containsKey(properties.currentKid())) throw unavailable();
    try {
      var factory = KeyFactory.getInstance("RSA");
      var privateKey =
          factory.generatePrivate(
              new PKCS8EncodedKeySpec(Base64.getDecoder().decode(properties.privateKey())));
      if (((RSAKey) privateKey).getModulus().bitLength() < 2048) throw unavailable();
      for (var entry : properties.publicKeys().entrySet()) {
        if (entry.getKey() == null || entry.getKey().isBlank()) throw unavailable();
        var key =
            factory.generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(entry.getValue())));
        if (((RSAKey) key).getModulus().bitLength() < 2048) throw unavailable();
        if (entry.getKey().equals(properties.currentKid())
            && !((RSAKey) privateKey).getModulus().equals(((RSAKey) key).getModulus()))
          throw unavailable();
      }
    } catch (Exception e) {
      throw unavailable();
    }
  }

  private void verifySignature(String[] parts, String kid) throws Exception {
    var key =
        KeyFactory.getInstance("RSA")
            .generatePublic(
                new X509EncodedKeySpec(
                    Base64.getDecoder().decode(properties.publicKeys().get(kid))));
    var signature = Signature.getInstance("SHA256withRSA");
    signature.initVerify(key);
    signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) throw invalid();
  }

  private static UUID uuid(String value) {
    UUID result = UUID.fromString(value);
    if (!result.toString().equals(value)) throw invalid();
    return result;
  }

  private static String text(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isString() || value.asString().isBlank()) throw invalid();
    return value.asString();
  }

  private static long integer(JsonNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
    return value.asLong();
  }

  private static String encode(byte[] bytes) {
    return ENCODER.encodeToString(bytes);
  }

  private static BusinessException invalid() {
    return new BusinessException(ErrorCode.UNAUTHENTICATED);
  }

  private static BusinessException unavailable() {
    return new BusinessException(ErrorCode.AUTH_UNAVAILABLE);
  }
}
