package com.afterbooth.member.controller;

import com.afterbooth.global.security.AuthenticatedMember;
import com.afterbooth.global.security.CsrfSessionService;
import com.afterbooth.member.dto.WithdrawMemberRequest;
import com.afterbooth.member.service.WithdrawalService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class WithdrawalController {
  private final WithdrawalService withdrawals;

  WithdrawalController(WithdrawalService withdrawals) {
    this.withdrawals = withdrawals;
  }

  @DeleteMapping("/api/members/me")
  ResponseEntity<Void> withdraw(
      @AuthenticationPrincipal AuthenticatedMember principal,
      @RequestBody WithdrawMemberRequest request) {
    withdrawals.withdraw(principal, request);
    return ResponseEntity.noContent()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header(
            HttpHeaders.SET_COOKIE,
            SessionController.cookie(SessionController.REFRESH_COOKIE, "", 0))
        .header(HttpHeaders.SET_COOKIE, SessionController.cookie(CsrfSessionService.COOKIE, "", 0))
        .build();
  }
}
