package com.afterbooth.global.security;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;

public class RateLimitedException extends BusinessException {
  private final long retryAfter;

  public RateLimitedException(long retryAfter) {
    super(ErrorCode.RATE_LIMITED);
    this.retryAfter = Math.max(1, retryAfter);
  }

  public long retryAfter() {
    return retryAfter;
  }
}
