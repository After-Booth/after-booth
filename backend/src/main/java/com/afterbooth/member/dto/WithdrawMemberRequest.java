package com.afterbooth.member.dto;

public record WithdrawMemberRequest(
    @tools.jackson.databind.annotation.JsonDeserialize(using = StrictPasswordDeserializer.class)
        String password) {
  @com.fasterxml.jackson.annotation.JsonAnySetter
  public void rejectUnknown(String name, Object value) {
    throw new com.afterbooth.global.error.BusinessException(
        com.afterbooth.global.error.ErrorCode.INVALID_INPUT);
  }

  @Override
  public String toString() {
    return "WithdrawMemberRequest[redacted]";
  }
}
