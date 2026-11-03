package com.afterbooth.member.mail;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class MailConfiguration {
  @Bean
  MailGateway mailGateway(Environment environment, Clock clock, MeterRegistry registry) {
    boolean fake = environment.getProperty("afterbooth.mail.fake-enabled", Boolean.class, false);
    boolean resend =
        environment.getProperty("afterbooth.mail.resend-enabled", Boolean.class, false);
    boolean local = environment.acceptsProfiles(Profiles.of("local"));
    boolean deployment =
        environment.acceptsProfiles(Profiles.of("deployment", "prod", "production"));
    if (fake && resend || fake && (!local || deployment) || resend && (!deployment || local)) {
      throw new IllegalStateException("Invalid mail delivery selection");
    }
    if (fake) {
      return new FakeMailGateway(clock, System.out);
    }
    if (!resend) {
      return new DisabledMailGateway();
    }
    String key = environment.getProperty("afterbooth.mail.resend-api-key", "");
    String from = environment.getProperty("afterbooth.mail.resend-from", "");
    if (key.isBlank()
        || from.isBlank()
        || key.contains("\r")
        || key.contains("\n")
        || from.contains("\r")
        || from.contains("\n")) {
      throw new IllegalStateException("Missing or invalid mail delivery credentials");
    }
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(3));
    factory.setReadTimeout(Duration.ofSeconds(3));
    RestClient client =
        RestClient.builder()
            .baseUrl("https://api.resend.com")
            .requestFactory(factory)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + key)
            .build();
    return new ResendMailGateway(client, from, clock, Thread::sleep, registry);
  }
}
