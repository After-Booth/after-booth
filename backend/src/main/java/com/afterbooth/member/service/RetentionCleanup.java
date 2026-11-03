package com.afterbooth.member.service;

import com.afterbooth.global.security.MemberWorkRegistry;
import com.afterbooth.member.repository.RetentionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class RetentionCleanup {
  private final RetentionRepository repository;
  private final MemberWorkRegistry work;

  public RetentionCleanup(RetentionRepository repository, MemberWorkRegistry work) {
    this.repository = repository;
    this.work = work;
  }

  @Transactional
  public int clean() {
    var result = repository.cleanup();
    if (!result.deletedMembers().isEmpty())
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              work.forgetPurged(result.deletedMembers());
            }
          });
    return result.transactions();
  }

  @Transactional(readOnly = true)
  public long overdueCount() {
    return repository.overdueCount();
  }
}
