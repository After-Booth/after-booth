package com.afterbooth.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.afterbooth.member.repository.MemberRepository;
import com.afterbooth.member.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class SignupCleanupIsolationTest {
  @Test
  void failuresDoNotSkipOtherCleanupOrRetryAndOverdueMonitoring() {
    var members = mock(MemberRepository.class);
    var sessions = mock(SessionRepository.class);
    var retention = mock(RetentionCleanup.class);
    var manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
    when(sessions.cleanup()).thenThrow(new IllegalStateException("Synthetic failure"));
    doThrow(new IllegalStateException("Synthetic failure")).when(members).cleanup();
    when(retention.clean()).thenThrow(new IllegalStateException("Synthetic failure"));
    when(retention.overdueCount()).thenReturn(2L);
    var cleanup = new SignupCleanup(members, sessions, manager, retention);
    Logger logger = (Logger) LoggerFactory.getLogger(SignupCleanup.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      cleanup.clean();
      verify(members).cleanup();
      verify(retention).clean();
      verify(retention).overdueCount();
      assertThat(appender.list).hasSize(4);
      assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage))
          .anyMatch(message -> message.endsWith("overdueCount=2"));
      assertThat(appender.list).allMatch(event -> event.getThrowableProxy() == null);
      doReturn(0).when(sessions).cleanup();
      doReturn(0).when(retention).clean();
      when(retention.overdueCount()).thenReturn(0L);
      org.mockito.Mockito.doNothing().when(members).cleanup();
      appender.list.clear();
      cleanup.clean();
      assertThat(appender.list).isEmpty();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
