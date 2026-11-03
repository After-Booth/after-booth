package com.afterbooth.member.mail;

final class DisabledMailGateway implements MailGateway {
  @Override
  public void send(MailMessage message) {}
}
