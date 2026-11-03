package com.afterbooth.global.security;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("afterbooth.auth.jwt")
public record JwtProperties(
    String issuer,
    String audience,
    String currentKid,
    String privateKey,
    Map<String, String> publicKeys) {
  @Override
  public String toString() {
    return "JwtProperties[redacted]";
  }
}
