package com.afterbooth.global.security;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.error.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties({AuthSecurityProperties.class, JwtProperties.class})
class AuthSecurityConfiguration {
  private static final List<SignupEndpoint> SIGNUP =
      List.of(
          new SignupEndpoint(
              PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/registrations"),
              "signup:registration"),
          new SignupEndpoint(
              PathPatternRequestMatcher.pathPattern(
                  HttpMethod.POST, "/api/auth/email-verifications/request"),
              "signup:issue"),
          new SignupEndpoint(
              PathPatternRequestMatcher.pathPattern(
                  HttpMethod.POST, "/api/auth/email-verifications/confirm"),
              "signup:confirm"));
  private static final RequestMatcher LOGIN =
      PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/login");
  private static final RequestMatcher REFRESH =
      PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/refresh");
  private static final RequestMatcher LOGOUT =
      PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/logout");
  private static final RequestMatcher LOGOUT_ALL =
      PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/logout-all");
  private static final RequestMatcher RESET_REQUEST =
      PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/password-resets/request");
  private static final RequestMatcher RESET_CONFIRM =
      PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/password-resets/confirm");
  private static final RequestMatcher PASSWORD_CHANGE =
      PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/members/me/password");
  private static final RequestMatcher SIGNUP_REQUESTS =
      new OrRequestMatcher(SIGNUP.stream().map(SignupEndpoint::matcher).toList());
  private static final RequestMatcher AUTH_POSTS =
      new OrRequestMatcher(
          SIGNUP_REQUESTS,
          LOGIN,
          REFRESH,
          LOGOUT,
          LOGOUT_ALL,
          RESET_REQUEST,
          RESET_CONFIRM,
          PASSWORD_CHANGE);
  private static final RequestMatcher AUTH_RESPONSES =
      new OrRequestMatcher(
          PathPatternRequestMatcher.pathPattern("/api/auth/**"),
          PathPatternRequestMatcher.pathPattern("/api/members/**"));

  private static final RequestMatcher BEARER_REQUESTS =
      new OrRequestMatcher(
          LOGOUT_ALL,
          PASSWORD_CHANGE,
          PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/members/me"),
          PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/events"),
          PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/organizations"),
          PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/items"),
          PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/items/{id}"),
          PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/events"),
          PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/events/{id}"),
          PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/stats/reuse"),
          PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/intake/drafts/{id}"),
          PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/pickup-requests/{id}"),
          PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/reservations"),
          PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/reservations/{id}"),
          PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/intake/drafts"),
          PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/intake/drafts/{id}/confirm"),
          PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/conditions/interpret"),
          PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/pickup-requests"),
          PathPatternRequestMatcher.pathPattern(
              HttpMethod.POST, "/api/pickup-requests/{id}/allocation"),
          PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/reservations"),
          PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/reservations/{id}/confirm"),
          PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/reservations/{id}/cancel"),
          PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/reservations/{id}/handover"),
          PathPatternRequestMatcher.pathPattern(
              HttpMethod.POST, "/api/reservations/{id}/handover/resolve"));

  private record SignupEndpoint(RequestMatcher matcher, String scope) {}

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      CsrfSessionService sessions,
      AuthRateLimiter limiter,
      AuthSecurityProperties properties,
      JsonMapper json,
      JwtService jwt,
      java.time.Clock clock)
      throws Exception {
    http.sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(cache -> cache.disable())
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(new HashedCsrfRepository(sessions))
                    .csrfTokenRequestHandler(HashedCsrfRepository.requestHandler())
                    .requireCsrfProtectionMatcher(AUTH_POSTS))
        .addFilterBefore(
            new OncePerRequestFilter() {
              @Override
              protected void doFilterInternal(
                  HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                  throws ServletException, IOException {
                if (AUTH_RESPONSES.matches(request))
                  response.setHeader("Cache-Control", "no-store");
                try {
                  SignupEndpoint endpoint =
                      SIGNUP.stream()
                          .filter(candidate -> candidate.matcher().matches(request))
                          .findFirst()
                          .orElse(null);
                  if (endpoint != null) limiter.checkIp(endpoint.scope(), request.getRemoteAddr());
                  if (LOGIN.matches(request)) limiter.checkIp("login", request.getRemoteAddr());
                  if (RESET_REQUEST.matches(request))
                    limiter.checkIp("password-reset:issue", request.getRemoteAddr());
                  if (RESET_CONFIRM.matches(request))
                    limiter.checkIp("password-reset:confirm", request.getRemoteAddr());
                  if (PASSWORD_CHANGE.matches(request))
                    limiter.checkIp("password-change", request.getRemoteAddr());
                  if (AUTH_POSTS.matches(request)) {
                    String origin = request.getHeader("Origin");
                    if (!properties.validOrigin())
                      throw new BusinessException(ErrorCode.AUTH_UNAVAILABLE);
                    if (!properties.allowedOrigin().equals(origin)) {
                      write(response, ErrorCode.CSRF_REJECTED, json);
                      return;
                    }
                  }
                  var authorization =
                      java.util.Collections.list(request.getHeaders("Authorization"));
                  if (BEARER_REQUESTS.matches(request) && !authorization.isEmpty()) {
                    if (authorization.size() != 1
                        || !authorization.getFirst().startsWith("Bearer "))
                      throw new BusinessException(ErrorCode.UNAUTHENTICATED);
                    var principal =
                        jwt.verify(authorization.getFirst().substring(7), clock.instant());
                    var authentication =
                        org.springframework.security.authentication
                            .UsernamePasswordAuthenticationToken.authenticated(
                            principal, null, java.util.List.of());
                    org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .setAuthentication(authentication);
                  }
                  chain.doFilter(request, response);
                } catch (RateLimitedException e) {
                  response.setHeader("Retry-After", Long.toString(e.retryAfter()));
                  write(response, e.errorCode(), json);
                } catch (BusinessException e) {
                  write(response, e.errorCode(), json);
                }
              }
            },
            CsrfFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.GET, "/api/auth/csrf")
                    .permitAll()
                    .requestMatchers(
                        SIGNUP_REQUESTS, LOGIN, REFRESH, LOGOUT, RESET_REQUEST, RESET_CONFIRM)
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/health",
                        "/actuator/health",
                        "/actuator/info",
                        "/",
                        "/index.html",
                        "/assets/**",
                        "/favicon.ico")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/members/me",
                        "/api/organizations",
                        "/api/items",
                        "/api/items/{id}",
                        "/api/events",
                        "/api/events/{id}",
                        "/api/stats/reuse",
                        "/api/intake/drafts/{id}",
                        "/api/pickup-requests/{id}",
                        "/api/reservations",
                        "/api/reservations/{id}")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/events",
                        "/api/intake/drafts",
                        "/api/intake/drafts/{id}/confirm",
                        "/api/conditions/interpret",
                        "/api/pickup-requests",
                        "/api/pickup-requests/{id}/allocation",
                        "/api/reservations",
                        "/api/reservations/{id}/confirm",
                        "/api/reservations/{id}/cancel",
                        "/api/reservations/{id}/handover",
                        "/api/reservations/{id}/handover/resolve")
                    .authenticated()
                    .requestMatchers(LOGOUT_ALL, PASSWORD_CHANGE)
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, error) ->
                            write(response, ErrorCode.UNAUTHENTICATED, json))
                    .accessDeniedHandler(
                        (request, response, error) ->
                            write(
                                response,
                                error instanceof org.springframework.security.web.csrf.CsrfException
                                    ? ErrorCode.CSRF_REJECTED
                                    : ErrorCode.FORBIDDEN,
                                json)));
    return http.build();
  }

  private static void write(HttpServletResponse response, ErrorCode code, JsonMapper json)
      throws IOException {
    response.setStatus(code.status().value());
    response.setHeader("Cache-Control", "no-store");
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response
        .getWriter()
        .write(json.writeValueAsString(new ErrorResponse(code.name(), code.message())));
  }
}
