package com.afterbooth.member.dto;

public record AccessTokenResponse(String accessToken, String tokenType, int expiresIn) {
  @Override
  public String toString() {
    return "AccessTokenResponse[redacted]";
  }
}
