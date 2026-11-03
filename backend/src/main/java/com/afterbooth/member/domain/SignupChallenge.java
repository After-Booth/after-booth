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
    return accepts(
        member,
        expectedEmailKey,
        expectedCode,
        now,
        "SIGNUP_VERIFY",
        MemberState.PENDING_VERIFICATION);
  }

  public boolean acceptsPasswordReset(
      Member member, byte[] expectedEmailKey, byte[] expectedCode, Instant now) {
    return accepts(
        member, expectedEmailKey, expectedCode, now, "PASSWORD_RESET", MemberState.ACTIVE);
  }

  private boolean accepts(
      Member member,
      byte[] expectedEmailKey,
      byte[] expectedCode,
      Instant now,
      String requiredPurpose,
      MemberState requiredState) {
    return member != null
        && member.state() == requiredState
        && member.id().equals(memberId)
        && requiredPurpose.equals(purpose)
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
