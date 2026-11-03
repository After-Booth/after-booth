package com.afterbooth.member.controller;

import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.error.ErrorResponse;
import com.afterbooth.global.security.AuthenticatedMember;
import com.afterbooth.global.security.CsrfSessionService;
import com.afterbooth.member.dto.LoginMemberRequest;
import com.afterbooth.member.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
  public static final String REFRESH_COOKIE = "__Host-afterbooth-refresh";
  private final SessionService sessions;

  public SessionController(SessionService sessions) {
    this.sessions = sessions;
  }

  @PostMapping("/api/auth/login")
  ResponseEntity<?> loginMember(@RequestBody LoginMemberRequest body, HttpServletRequest request) {
    return issued(sessions.login(body, cookie(request, CsrfSessionService.COOKIE)));
  }

  @PostMapping("/api/auth/refresh")
  ResponseEntity<?> refreshSession(HttpServletRequest request) {
    var result =
        sessions.refresh(
            cookie(request, REFRESH_COOKIE), cookie(request, CsrfSessionService.COOKIE));
    if (result == null)
      return ResponseEntity.status(401)
          .header(HttpHeaders.CACHE_CONTROL, "no-store")
          .header(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, "", 0))
          .body(
              new ErrorResponse(
                  ErrorCode.UNAUTHENTICATED.name(), ErrorCode.UNAUTHENTICATED.message()));
    return issued(result);
  }

  @PostMapping("/api/auth/logout")
  ResponseEntity<Void> logoutSession(HttpServletRequest request) {
    sessions.logout(cookie(request, REFRESH_COOKIE));
    return ResponseEntity.noContent()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, "", 0))
        .build();
  }

  @PostMapping("/api/auth/logout-all")
  ResponseEntity<Void> logoutAllSessions(@AuthenticationPrincipal AuthenticatedMember principal) {
    sessions.logoutAll(principal);
    return ResponseEntity.noContent()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, "", 0))
        .header(HttpHeaders.SET_COOKIE, cookie(CsrfSessionService.COOKIE, "", 0))
        .build();
  }

  private ResponseEntity<?> issued(SessionService.Issued result) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, result.refresh(), result.maxAge()))
        .header(
            HttpHeaders.SET_COOKIE,
            cookie(CsrfSessionService.COOKIE, result.csrf().sessionId(), 3600))
        .body(result.response());
  }

  private static String cookie(String name, String value, long maxAge) {
    return ResponseCookie.from(name, value)
        .httpOnly(true)
        .secure(true)
        .sameSite("Lax")
        .path("/")
        .maxAge(maxAge)
        .build()
        .toString();
  }

  private static String cookie(HttpServletRequest request, String name) {
    if (request.getCookies() == null) return null;
    var matching =
        Arrays.stream(request.getCookies()).filter(value -> name.equals(value.getName())).toList();
    return matching.size() == 1 ? matching.getFirst().getValue() : null;
  }
}
