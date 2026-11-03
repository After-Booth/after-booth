package com.afterbooth.member.dto;

public record EmailChallengeRequest(String email) {
  @com.fasterxml.jackson.annotation.JsonAnySetter
  public void rejectUnknown(String name, Object value) {
    throw new com.afterbooth.global.error.BusinessException(
        com.afterbooth.global.error.ErrorCode.INVALID_INPUT);
  }

  @Override
  public String toString() {
    return "EmailChallengeRequest[redacted]";
  }
}
