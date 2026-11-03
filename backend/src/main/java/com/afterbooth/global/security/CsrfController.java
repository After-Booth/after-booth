package com.afterbooth.global.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CsrfController {
  private final CsrfSessionService sessions;

  CsrfController(CsrfSessionService sessions) {
    this.sessions = sessions;
  }

  @GetMapping("/api/auth/csrf")
  ResponseEntity<TokenResponse> getCsrfToken(jakarta.servlet.http.HttpServletRequest request) {
    String previous = null;
    if (request.getCookies() != null) {
      var cookies =
          java.util.Arrays.stream(request.getCookies())
              .filter(c -> CsrfSessionService.COOKIE.equals(c.getName()))
              .toList();
      if (cookies.size() == 1) previous = cookies.getFirst().getValue();
    }
    var issued = sessions.reissue(previous);
    var cookie =
        ResponseCookie.from(CsrfSessionService.COOKIE, issued.sessionId())
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax")
            .path("/")
            .maxAge(3600)
            .build();
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(new TokenResponse(issued.csrfToken()));
  }

  record TokenResponse(String csrfToken) {
    @Override
    public String toString() {
      return "TokenResponse[redacted]";
    }
  }
}
