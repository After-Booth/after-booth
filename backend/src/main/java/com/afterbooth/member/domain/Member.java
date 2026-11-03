package com.afterbooth.member.domain;

import java.time.Instant;

public record Member(
    Long id,
    String emailNormalized,
    String passwordHash,
    MemberState state,
    long authVersion,
    Instant createdAt,
    Instant verifiedAt) {
  public boolean acceptsSignupVerification() {
    return state == MemberState.PENDING_VERIFICATION;
  }

  @Override
  public String toString() {
    return "Member[redacted]";
  }
}
