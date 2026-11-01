package com.afterbooth.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
  QTY_UNAVAILABLE(HttpStatus.CONFLICT, "남은 수량이 부족해요."),
  PICKUP_CLOSED(HttpStatus.CONFLICT, "픽업 마감 시간이 지났어요."),
  VEHICLE_TOO_SMALL(HttpStatus.CONFLICT, "차량에 물품을 실을 수 없어요."),
  QTY_EXCEEDS_REQUEST(HttpStatus.CONFLICT, "요청 수량을 초과했어요."),
  REQUEST_ALREADY_HELD(HttpStatus.CONFLICT, "이미 배정된 물품이 있어요. 예약 현황을 확인해 주세요."),
  INVALID_TRANSITION(HttpStatus.CONFLICT, "변경할 수 없는 예약 상태예요."),
  HOLD_EXPIRED(HttpStatus.CONFLICT, "예약 대기 시간이 만료됐어요."),
  IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "중복 요청 키를 확인해 주세요."),
  INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
  DRAFT_NOT_READY(HttpStatus.CONFLICT, "초안 처리가 끝난 뒤 확정해 주세요."),
  DRAFT_ALREADY_CONFIRMED(HttpStatus.CONFLICT, "이미 확정한 초안이에요."),
  NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없어요."),
  UNSUPPORTED_REQUEST(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식이에요."),
  AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "음성이나 조건을 해석하지 못했어요. 잠시 후 다시 시도해 주세요."),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "잠시 후 다시 시도해 주세요.");

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
