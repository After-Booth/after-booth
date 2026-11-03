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
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(AuthSecurityProperties.class)
class AuthSecurityConfiguration {
  private static final List<String> SIGNUP =
      List.of(
          "/api/auth/registrations",
          "/api/auth/email-verifications/request",
          "/api/auth/email-verifications/confirm");

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      CsrfSessionService sessions,
      AuthRateLimiter limiter,
      AuthSecurityProperties properties,
      JsonMapper json)
      throws Exception {
    http.sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(cache -> cache.disable())
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(new HashedCsrfRepository(sessions))
                    .csrfTokenRequestHandler(HashedCsrfRepository.requestHandler())
                    .requireCsrfProtectionMatcher(AuthSecurityConfiguration::isSignup))
        .addFilterBefore(
            new OncePerRequestFilter() {
              @Override
              protected void doFilterInternal(
                  HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                  throws ServletException, IOException {
                boolean auth =
                    request.getRequestURI().startsWith("/api/auth/")
                        || request.getRequestURI().startsWith("/api/members/");
                if (auth) response.setHeader("Cache-Control", "no-store");
                try {
                  if (isSignup(request)) {
                    String scope =
                        switch (request.getRequestURI()) {
                          case "/api/auth/registrations" -> "signup:registration";
                          case "/api/auth/email-verifications/request" -> "signup:issue";
                          default -> "signup:confirm";
                        };
                    limiter.checkIp(scope, request.getRemoteAddr());
                    String origin = request.getHeader("Origin");
                    if (!properties.validOrigin())
                      throw new BusinessException(ErrorCode.AUTH_UNAVAILABLE);
                    if (!properties.allowedOrigin().equals(origin)) {
                      write(response, ErrorCode.CSRF_REJECTED, json);
                      return;
                    }
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
                    .requestMatchers(HttpMethod.POST, SIGNUP.toArray(String[]::new))
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/health",
                        "/actuator/health",
                        "/actuator/info",
                        "/api/organizations",
                        "/api/items",
                        "/api/items/{id}",
                        "/api/events",
                        "/api/events/{id}",
                        "/api/stats/reuse",
                        "/api/intake/drafts/{id}",
                        "/api/pickup-requests/{id}",
                        "/api/reservations",
                        "/api/reservations/{id}",
                        "/",
                        "/index.html",
                        "/assets/**",
                        "/favicon.ico")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
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
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, error) -> write(response, ErrorCode.FORBIDDEN, json))
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

  private static boolean isSignup(HttpServletRequest request) {
    return "POST".equals(request.getMethod()) && SIGNUP.contains(request.getRequestURI());
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
