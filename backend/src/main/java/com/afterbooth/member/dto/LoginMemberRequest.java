package com.afterbooth.member.dto;

public record LoginMemberRequest(String email, String password) {
  @Override
  public String toString() {
    return "LoginMemberRequest[redacted]";
  }
}
