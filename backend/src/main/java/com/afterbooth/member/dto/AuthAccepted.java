package com.afterbooth.member.dto;

public record AuthAccepted(String message) {
  public static AuthAccepted accepted() {
    return new AuthAccepted("Request processed. Please verify your email.");
  }
}
