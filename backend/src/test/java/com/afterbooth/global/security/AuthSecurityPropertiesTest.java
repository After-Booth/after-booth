package com.afterbooth.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthSecurityPropertiesTest {
  @Test
  void onlyExactHttpsOriginShapesAreAccepted() {
    assertThat(new AuthSecurityProperties("https://localhost:5443", null).validOrigin()).isTrue();
    for (String invalid :
        new String[] {
          "*",
          "https://*.example.invalid",
          "http://localhost:5443",
          "https://example.invalid/",
          "https://example.invalid/path",
          "https://user@example.invalid",
          "https://example.invalid?query",
          "https://example.invalid#fragment"
        }) assertThat(new AuthSecurityProperties(invalid, null).validOrigin()).isFalse();
  }

  @Test
  void previousKeysExpireAtBoundaryAndAbsentSecretsFailClosedWithoutBreakingStartup() {
    var now = Instant.parse("2026-01-01T00:00:00Z");
    var clock = Clock.fixed(now, ZoneOffset.UTC);
    var properties =
        new AuthSecurityProperties(
            null,
            new AuthSecurityProperties.Hmac(
                "current",
                Map.of("current", key(), "valid", key(), "expired", key()),
                Map.of("valid", now.plusSeconds(1), "expired", now)));
    assertThat(new HmacKeyRing(properties, clock).versions()).containsExactly("current", "valid");
    var absent = new HmacKeyRing(new AuthSecurityProperties(null, null), clock);
    assertThatThrownBy(absent::currentVersion)
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.AUTH_UNAVAILABLE));
  }

  private String key() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
