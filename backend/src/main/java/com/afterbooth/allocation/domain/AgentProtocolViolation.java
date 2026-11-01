package com.afterbooth.allocation.domain;

public final class AgentProtocolViolation extends RuntimeException {
  public AgentProtocolViolation(String message) {
    super(message);
  }
}
