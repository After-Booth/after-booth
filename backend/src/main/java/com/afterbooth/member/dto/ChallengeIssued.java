package com.afterbooth.member.dto;

public record ChallengeIssued(String message, String challengeId) {
  @Override
  public String toString() {
    return "ChallengeIssued[redacted]";
  }
}
