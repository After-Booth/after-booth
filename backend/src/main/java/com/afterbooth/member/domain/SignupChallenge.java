package com.afterbooth.member.domain;

import java.security.MessageDigest;
import java.time.Instant;

public record SignupChallenge(
    String id,
    Long memberId,
    byte[] emailKey,
    String keyVersion,
    String purpose,
    byte[] codeHmac,
    int attempts,
    Instant expiresAt,
    Instant consumedAt) {
  public boolean accepts(Member member, byte[] expectedEmailKey, byte[] expectedCode, Instant now) {
    return member != null
        && member.acceptsSignupVerification()
        && member.id().equals(memberId)
        && "SIGNUP_VERIFY".equals(purpose)
        && consumedAt == null
        && now.isBefore(expiresAt)
        && attempts < 5
        && codeHmac != null
        && MessageDigest.isEqual(emailKey, expectedEmailKey)
        && MessageDigest.isEqual(codeHmac, expectedCode);
  }

  @Override
  public String toString() {
    return "SignupChallenge[redacted]";
  }
}
