package com.afterbooth.member.dto;

public record RegisterMemberRequest(String email, String password) {
  @com.fasterxml.jackson.annotation.JsonAnySetter
  public void rejectUnknown(String name, Object value) {
    throw new com.afterbooth.global.error.BusinessException(
        com.afterbooth.global.error.ErrorCode.INVALID_INPUT);
  }

  @Override
  public String toString() {
    return "RegisterMemberRequest[redacted]";
  }
}
