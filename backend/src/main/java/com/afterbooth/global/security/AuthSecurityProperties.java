package com.afterbooth.global.security;

import java.time.Instant;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("afterbooth.auth")
public record AuthSecurityProperties(String allowedOrigin, Hmac hmac) {
  boolean validOrigin() {
    if (allowedOrigin == null || allowedOrigin.isBlank()) return false;
    try {
      java.net.URI uri = java.net.URI.create(allowedOrigin);
      return "https".equals(uri.getScheme())
          && uri.getHost() != null
          && uri.getRawUserInfo() == null
          && uri.getRawQuery() == null
          && uri.getRawFragment() == null
          && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
          && !allowedOrigin.contains("*")
          && allowedOrigin.equals(uri.getScheme() + "://" + uri.getRawAuthority());
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  public record Hmac(
      String currentVersion, Map<String, String> keys, Map<String, Instant> previousValidUntil) {}
}
