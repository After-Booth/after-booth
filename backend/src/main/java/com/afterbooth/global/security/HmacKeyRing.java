package com.afterbooth.global.security;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class HmacKeyRing {
  private final AuthSecurityProperties properties;
  private final Clock clock;

  public HmacKeyRing(AuthSecurityProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  public String currentVersion() {
    var hmac = properties.hmac();
    if (hmac == null
        || hmac.currentVersion() == null
        || hmac.keys() == null
        || !hmac.keys().containsKey(hmac.currentVersion())) throw unavailable();
    return hmac.currentVersion();
  }

  public List<String> versions() {
    return versions(clock.instant());
  }

  public List<String> versions(java.time.Instant now) {
    String current = currentVersion();
    var hmac = properties.hmac();
    return hmac.keys().keySet().stream()
        .filter(
            version ->
                version.equals(current)
                    || hmac.previousValidUntil() != null
                        && hmac.previousValidUntil().get(version) != null
                        && hmac.previousValidUntil().get(version).isAfter(now))
        .sorted()
        .toList();
  }

  public byte[] digest(String version, String purpose, String value) {
    try {
      currentVersion();
      if (!properties.hmac().keys().containsKey(version)) throw unavailable();
      byte[] key = Base64.getDecoder().decode(properties.hmac().keys().get(version));
      if (key.length < 32) throw unavailable();
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal((purpose + "\u0000" + value).getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw unavailable();
    }
  }

  public byte[] currentDigest(String purpose, String value) {
    return digest(currentVersion(), purpose, value);
  }

  private BusinessException unavailable() {
    return new BusinessException(ErrorCode.AUTH_UNAVAILABLE);
  }
}
