package com.afterbooth.member.service;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.security.AuthenticatedMember;
import com.afterbooth.global.security.CsrfSessionService;
import com.afterbooth.global.security.HmacKeyRing;
import com.afterbooth.global.security.JwtService;
import com.afterbooth.global.security.MemberWorkRegistry;
import com.afterbooth.member.domain.MemberState;
import com.afterbooth.member.domain.SignupCredentials;
import com.afterbooth.member.dto.WithdrawMemberRequest;
import com.afterbooth.member.repository.MemberRepository;
import com.afterbooth.member.repository.SessionRepository;
import com.afterbooth.member.repository.WithdrawalRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WithdrawalService {
  private final MemberRepository members;
  private final WithdrawalRepository withdrawals;
  private final MemberLockService locks;
  private final SessionRepository sessions;
  private final CsrfSessionService csrf;
  private final JwtService jwt;
  private final HmacKeyRing keys;
  private final PasswordEncoder passwords;
  private final TransactionTemplate transactions;
  private final MemberWorkRegistry work;

  public WithdrawalService(
      MemberRepository members,
      WithdrawalRepository withdrawals,
      MemberLockService locks,
      SessionRepository sessions,
      CsrfSessionService csrf,
      JwtService jwt,
      HmacKeyRing keys,
      PasswordEncoder passwords,
      PlatformTransactionManager manager,
      MemberWorkRegistry work) {
    this.members = members;
    this.withdrawals = withdrawals;
    this.locks = locks;
    this.sessions = sessions;
    this.csrf = csrf;
    this.jwt = jwt;
    this.keys = keys;
    this.passwords = passwords;
    transactions = new TransactionTemplate(manager);
    this.work = work;
  }

  public void withdraw(AuthenticatedMember principal, WithdrawMemberRequest request) {
    String submitted = SignupCredentials.password(request.password());
    try (var guard = work.erase(principal.memberId())) {
      transactions.executeWithoutResult(
          status -> {
            String email = withdrawals.email(principal.memberId());
            if (email == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
            members.lockEmail(email);
            locks.lockAll(withdrawals.relatedMembers(principal.memberId()));
            var member = locks.lock(principal.memberId());
            var now = members.databaseNow();
            if (member == null
                || member.state() != MemberState.ACTIVE
                || member.authVersion() != principal.authVersion())
              throw new BusinessException(ErrorCode.UNAUTHENTICATED);
            jwt.verifyPrincipal(principal, now);
            if (!passwords.matches(submitted, member.passwordHash()))
              throw new BusinessException(ErrorCode.UNAUTHENTICATED);
            if (withdrawals.blocked(member.id()))
              throw new BusinessException(ErrorCode.WITHDRAWAL_BLOCKED);
            String version = keys.currentVersion();
            byte[] digest = keys.digest(version, "withdrawn-email", email);
            sessions.revokeAll(member.id(), now);
            csrf.invalidateMember(member.id());
            withdrawals.erase(member.id(), version, digest, now);
          });
      guard.commit();
    } catch (DataAccessException | TransactionException error) {
      throw new BusinessException(ErrorCode.AUTH_UNAVAILABLE);
    }
  }
}
