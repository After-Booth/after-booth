package com.afterbooth.member.dto;

public record AuthAccepted(String message) {
  public static AuthAccepted accepted() {
    return new AuthAccepted("요청을 처리했어요. 이메일 인증을 진행해 주세요.");
  }
}
