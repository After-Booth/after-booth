package com.afterbooth.member.service;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
class SignupMailConfiguration {
  @Bean(name = "signupMailExecutor")
  Executor signupMailExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(32);
    executor.setThreadNamePrefix("signup-mail-");
    executor.initialize();
    return executor;
  }
}
