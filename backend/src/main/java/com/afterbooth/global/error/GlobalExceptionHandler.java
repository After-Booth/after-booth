package com.afterbooth.global.error;

import com.afterbooth.global.security.RateLimitedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
class GlobalExceptionHandler {

  private static final org.springframework.security.web.util.matcher.RequestMatcher AUTH_REQUESTS =
      new org.springframework.security.web.util.matcher.OrRequestMatcher(
          org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
              .pathPattern("/api/auth/**"),
          org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
              .pathPattern("/api/members/**"));

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
    if (e instanceof RateLimitedException limited) {
      return ResponseEntity.status(e.errorCode().status())
          .header("Retry-After", Long.toString(limited.retryAfter()))
          .header("Cache-Control", "no-store")
          .body(new ErrorResponse(e.errorCode().name(), e.errorCode().message()));
    }
    return respond(e.errorCode().status(), e.errorCode().name(), e.getMessage());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    TypeMismatchException.class,
    MissingRequestValueException.class,
    MissingServletRequestPartException.class,
    MaxUploadSizeExceededException.class
  })
  ResponseEntity<ErrorResponse> handleInvalidInput(Exception e) {
    return respond(ErrorCode.INVALID_INPUT);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorResponse> handleUnexpected(
      Exception e, jakarta.servlet.http.HttpServletRequest request) {
    // Spring MVC exceptions such as 405, 415 and 404 (unknown path) keep their own 4xx status
    if (e instanceof org.springframework.web.ErrorResponse framework
        && framework.getStatusCode().is4xxClientError()) {
      return handleClientError(framework.getStatusCode());
    }
    if (AUTH_REQUESTS.matches(request)) {
      log.error("Authentication request failed");
      return respond(ErrorCode.AUTH_UNAVAILABLE);
    }
    log.error("Unhandled exception", e);
    return respond(ErrorCode.INTERNAL_ERROR);
  }

  private ResponseEntity<ErrorResponse> handleClientError(HttpStatusCode status) {
    if (status.value() == HttpStatus.NOT_FOUND.value()) {
      return respond(ErrorCode.NOT_FOUND);
    }
    return respond(status, "UNSUPPORTED_REQUEST", ErrorCode.INVALID_INPUT.message());
  }

  private ResponseEntity<ErrorResponse> respond(ErrorCode code) {
    return respond(code.status(), code.name(), code.message());
  }

  private ResponseEntity<ErrorResponse> respond(
      HttpStatusCode status, String code, String message) {
    return ResponseEntity.status(status).body(new ErrorResponse(code, message));
  }
}
