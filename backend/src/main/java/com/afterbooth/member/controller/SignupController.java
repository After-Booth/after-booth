package com.afterbooth.member.controller;

import com.afterbooth.member.dto.AuthAccepted;
import com.afterbooth.member.dto.ChallengeIssued;
import com.afterbooth.member.dto.EmailChallengeRequest;
import com.afterbooth.member.dto.RegisterMemberRequest;
import com.afterbooth.member.dto.VerifyEmailRequest;
import com.afterbooth.member.service.SignupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SignupController {
  private final SignupService service;

  SignupController(SignupService service) {
    this.service = service;
  }

  @PostMapping("/api/auth/registrations")
  ResponseEntity<AuthAccepted> register(@RequestBody RegisterMemberRequest request) {
    return ResponseEntity.accepted().body(service.register(request));
  }

  @PostMapping("/api/auth/email-verifications/request")
  ResponseEntity<ChallengeIssued> requestVerification(@RequestBody EmailChallengeRequest request) {
    return ResponseEntity.accepted().body(service.requestVerification(request.email()));
  }

  @PostMapping("/api/auth/email-verifications/confirm")
  ResponseEntity<Void> verify(@RequestBody VerifyEmailRequest request) {
    service.verify(request);
    return ResponseEntity.noContent().build();
  }
}
