package com.afterbooth.member.mail;

import java.io.PrintStream;
import java.time.Clock;

final class FakeMailGateway implements MailGateway {
  private final Clock clock;
  private final PrintStream console;

  FakeMailGateway(Clock clock, PrintStream console) {
    this.clock = clock;
    this.console = console;
  }

  @Override
  public void send(MailMessage message) {
    if (clock.instant().isBefore(message.expiresAt())) {
      console.println(message.purpose() + " " + message.code());
    }
  }
}
