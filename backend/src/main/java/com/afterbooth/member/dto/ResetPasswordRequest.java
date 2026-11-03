package com.afterbooth.member.dto;

public record ResetPasswordRequest(
    String email, String challengeId, String code, String newPassword) {
  @com.fasterxml.jackson.annotation.JsonAnySetter
  public void rejectUnknown(String name, Object value) {
    throw new com.afterbooth.global.error.BusinessException(
        com.afterbooth.global.error.ErrorCode.INVALID_INPUT);
  }

  @Override
  public String toString() {
    return "ResetPasswordRequest[redacted]";
  }
}
