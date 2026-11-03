package com.afterbooth.member.service;

import com.afterbooth.member.repository.MemberRepository;
import com.afterbooth.member.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SignupCleanup {
  private static final Logger log = LoggerFactory.getLogger(SignupCleanup.class);
  private final MemberRepository members;
  private final TransactionTemplate transactions;
  private final SessionRepository sessions;
  private final RetentionCleanup retention;

  public SignupCleanup(
      MemberRepository members,
      SessionRepository sessions,
      PlatformTransactionManager manager,
      RetentionCleanup retention) {
    this.members = members;
    this.sessions = sessions;
    this.transactions = new TransactionTemplate(manager);
    this.retention = retention;
  }

  @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
  public void clean() {
    try {
      transactions.executeWithoutResult(status -> sessions.cleanup());
    } catch (RuntimeException ignored) {
      log.warn("Session cleanup unavailable; next scheduled run will retry");
    }
    try {
      transactions.executeWithoutResult(status -> members.cleanup());
    } catch (RuntimeException ignored) {
      log.warn("Signup cleanup unavailable; next scheduled run will retry");
    }
    try {
      retention.clean();
    } catch (RuntimeException ignored) {
      log.warn("Retention cleanup unavailable; next scheduled run will retry");
    }
    try {
      long overdue = retention.overdueCount();
      if (overdue > 0) log.warn("Retention cleanup exceeds one hour; overdueCount={}", overdue);
    } catch (RuntimeException ignored) {
      log.warn("Retention cleanup monitoring unavailable; next scheduled run will retry");
    }
  }
}
