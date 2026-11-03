package com.afterbooth.member.mail;

public interface MailGateway {
  void send(MailMessage message);
}
