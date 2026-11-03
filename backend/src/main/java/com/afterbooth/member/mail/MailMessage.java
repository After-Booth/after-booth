package com.afterbooth.member.mail;

import java.time.Instant;
import java.util.Objects;

public record MailMessage(
    String challengeId, Purpose purpose, String recipient, String code, Instant expiresAt) {
  public MailMessage {
    Objects.requireNonNull(challengeId);
    Objects.requireNonNull(purpose);
    Objects.requireNonNull(recipient);
    Objects.requireNonNull(expiresAt);
    if (code == null || !code.matches("[0-9]{6}")) {
      throw new IllegalArgumentException("Invalid mail code format");
    }
  }

  public enum Purpose {
    SIGNUP_VERIFY,
    PASSWORD_RESET
  }

  @Override
  public String toString() {
    return "MailMessage[redacted]";
  }
}
