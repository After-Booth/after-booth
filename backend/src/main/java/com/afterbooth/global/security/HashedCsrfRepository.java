package com.afterbooth.global.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.DefaultCsrfToken;

final class HashedCsrfRepository implements CsrfTokenRepository {
  private final CsrfSessionService sessions;

  HashedCsrfRepository(CsrfSessionService sessions) {
    this.sessions = sessions;
  }

  @Override
  public CsrfToken generateToken(HttpServletRequest request) {
    return token(sessions.randomToken());
  }

  @Override
  public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
    // Only getCsrfToken may issue a persisted anonymous session and its cookie.
  }

  @Override
  public CsrfToken loadToken(HttpServletRequest request) {
    if (request.getCookies() == null) return null;
    var cookies =
        Arrays.stream(request.getCookies())
            .filter(cookie -> CsrfSessionService.COOKIE.equals(cookie.getName()))
            .toList();
    if (cookies.size() != 1) return null;
    String hash = sessions.storedTokenHash(cookies.getFirst().getValue());
    return hash == null ? null : token(hash);
  }

  private CsrfToken token(String value) {
    return new DefaultCsrfToken("X-CSRF-Token", "_csrf", value);
  }

  static CsrfTokenRequestHandler requestHandler() {
    return new CsrfTokenRequestHandler() {
      @Override
      public void handle(
          HttpServletRequest request,
          HttpServletResponse response,
          java.util.function.Supplier<CsrfToken> token) {
        request.setAttribute(CsrfToken.class.getName(), token);
      }

      @Override
      public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
        String supplied = request.getHeader("X-CSRF-Token");
        if (supplied == null || !supplied.matches("[A-Za-z0-9_-]{43}")) return null;
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(CsrfSessionService.hash(supplied));
      }
    };
  }
}
