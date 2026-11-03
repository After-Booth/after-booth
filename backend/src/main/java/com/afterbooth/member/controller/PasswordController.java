package com.afterbooth.member.controller;

import com.afterbooth.global.security.AuthenticatedMember;
import com.afterbooth.global.security.CsrfSessionService;
import com.afterbooth.member.dto.ChallengeIssued;
import com.afterbooth.member.dto.ChangePasswordRequest;
import com.afterbooth.member.dto.EmailChallengeRequest;
import com.afterbooth.member.dto.ResetPasswordRequest;
import com.afterbooth.member.service.PasswordService;
import com.afterbooth.member.service.SignupService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PasswordController {
  private final PasswordService passwords;
  private final SignupService challenges;

  PasswordController(PasswordService passwords, SignupService challenges) {
    this.passwords = passwords;
    this.challenges = challenges;
  }

  @PostMapping("/api/auth/password-resets/request")
  ResponseEntity<ChallengeIssued> requestPasswordReset(@RequestBody EmailChallengeRequest request) {
    return ResponseEntity.accepted().body(challenges.requestPasswordReset(request.email()));
  }

  @PostMapping("/api/auth/password-resets/confirm")
  ResponseEntity<Void> resetPassword(@RequestBody ResetPasswordRequest request) {
    passwords.reset(request);
    return cleared();
  }

  @PostMapping("/api/members/me/password")
  ResponseEntity<Void> changePassword(
      @AuthenticationPrincipal AuthenticatedMember principal,
      @RequestBody ChangePasswordRequest request) {
    passwords.change(principal, request);
    return cleared();
  }

  private static ResponseEntity<Void> cleared() {
    return ResponseEntity.noContent()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header(HttpHeaders.SET_COOKIE, expired(SessionController.REFRESH_COOKIE))
        .header(HttpHeaders.SET_COOKIE, expired(CsrfSessionService.COOKIE))
        .build();
  }

  private static String expired(String name) {
    return ResponseCookie.from(name, "")
        .httpOnly(true)
        .secure(true)
        .sameSite("Lax")
        .path("/")
        .maxAge(0)
        .build()
        .toString();
  }
}
