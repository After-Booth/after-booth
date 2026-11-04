package com.afterbooth.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
  QTY_UNAVAILABLE(HttpStatus.CONFLICT, "Not enough items remain."),
  PICKUP_CLOSED(HttpStatus.CONFLICT, "The pickup deadline has passed."),
  VEHICLE_TOO_SMALL(HttpStatus.CONFLICT, "The item does not fit your vehicle."),
  QTY_EXCEEDS_REQUEST(HttpStatus.CONFLICT, "The quantity exceeds your request."),
  REQUEST_ALREADY_HELD(
      HttpStatus.CONFLICT, "Items are already allocated. Check your reservations."),
  INVALID_TRANSITION(
      HttpStatus.CONFLICT, "This reservation cannot be changed in its current state."),
  HOLD_EXPIRED(HttpStatus.CONFLICT, "The hold has expired."),
  IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Check the idempotency key."),
  WITHDRAWAL_BLOCKED(
      HttpStatus.CONFLICT, "Complete your active transactions before deleting your account."),
  INVALID_INPUT(HttpStatus.BAD_REQUEST, "Check your input."),
  DRAFT_NOT_READY(HttpStatus.CONFLICT, "Wait for draft processing before confirming."),
  DRAFT_ALREADY_CONFIRMED(HttpStatus.CONFLICT, "This draft is already confirmed."),
  NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource was not found."),
  UNSUPPORTED_REQUEST(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "This request format is not supported."),
  AI_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE,
      "AI processing could not be completed. Please try again shortly."),
  CSRF_REJECTED(HttpStatus.FORBIDDEN, "Check the request security information."),
  UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Please sign in."),
  EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "Please verify your email."),
  FORBIDDEN(HttpStatus.FORBIDDEN, "You do not have access."),
  RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Please try again shortly."),
  AUTH_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE,
      "Authentication could not be completed. Please try again shortly."),
  INVALID_AUTH_CHALLENGE(HttpStatus.BAD_REQUEST, "Check your verification information."),
  INVALID_CURRENT_PASSWORD(HttpStatus.FORBIDDEN, "Check your current password."),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Please try again shortly.");

  private final HttpStatus status;
  private final String message;

  ErrorCode(HttpStatus status, String message) {
    this.status = status;
    this.message = message;
  }

  public HttpStatus status() {
    return status;
  }

  public String message() {
    return message;
  }
}
