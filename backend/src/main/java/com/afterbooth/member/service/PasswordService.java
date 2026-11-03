package com.afterbooth.member.service;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.security.AuthRateLimiter;
import com.afterbooth.global.security.AuthenticatedMember;
import com.afterbooth.global.security.CsrfSessionService;
import com.afterbooth.global.security.HmacKeyRing;
import com.afterbooth.global.security.JwtService;
import com.afterbooth.member.domain.MemberState;
import com.afterbooth.member.domain.SignupCredentials;
import com.afterbooth.member.dto.ChangePasswordRequest;
import com.afterbooth.member.dto.ResetPasswordRequest;
import com.afterbooth.member.repository.MemberRepository;
import com.afterbooth.member.repository.SessionRepository;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PasswordService {
  private static final String PURPOSE = "PASSWORD_RESET";
  private final MemberRepository members;
  private final MemberLockService locks;
  private final SessionRepository sessions;
  private final PasswordEncoder passwords;
  private final AuthRateLimiter limits;
  private final CsrfSessionService csrf;
  private final JwtService jwt;
  private final HmacKeyRing keys;
  private final TransactionTemplate transactions;

  public PasswordService(
      MemberRepository members,
      MemberLockService locks,
      SessionRepository sessions,
      PasswordEncoder passwords,
      AuthRateLimiter limits,
      CsrfSessionService csrf,
      JwtService jwt,
      HmacKeyRing keys,
      PlatformTransactionManager manager) {
    this.members = members;
    this.locks = locks;
    this.sessions = sessions;
    this.passwords = passwords;
    this.limits = limits;
    this.csrf = csrf;
    this.jwt = jwt;
    this.keys = keys;
    transactions = new TransactionTemplate(manager);
    // Commit rejected-attempt evidence with one connection, independently of a caller rollback.
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public void change(AuthenticatedMember principal, ChangePasswordRequest request) {
    String current = SignupCredentials.password(request.currentPassword());
    String hash = passwords.encode(SignupCredentials.password(request.newPassword()));
    ErrorCode error =
        transaction(
            () -> {
              var member = locks.lock(principal.memberId());
              Instant now = members.databaseNow();
              if (member == null
                  || member.state() != MemberState.ACTIVE
                  || member.authVersion() != principal.authVersion())
                return ErrorCode.UNAUTHENTICATED;
              jwt.verifyPrincipal(principal, now);
              limits.checkPasswordChangeWithinTransaction(member.id());
              if (!passwords.matches(current, member.passwordHash())) {
                limits.passwordChangeFailedWithinTransaction(member.id());
                return ErrorCode.INVALID_CURRENT_PASSWORD;
              }
              replace(member.id(), hash, now);
              return null;
            });
    if (error != null) throw new BusinessException(error);
  }

  public void reset(ResetPasswordRequest request) {
    String email = SignupCredentials.normalizeEmail(request.email());
    SignupCredentials.challenge(request.challengeId(), request.code());
    String hash = passwords.encode(SignupCredentials.password(request.newPassword()));
    boolean accepted =
        Boolean.TRUE.equals(
            transaction(
                () -> {
                  members.lockEmail(email);
                  var member = locks.lockByEmail(email);
                  var challenge = members.challengeForUpdate(request.challengeId());
                  Instant now = members.databaseNow();
                  if (challenge == null) return false;
                  // A challenge for another purpose must never lose attempts to password-reset
                  // requests.
                  if (!PURPOSE.equals(challenge.purpose())) return false;
                  members.attempt(challenge.id());
                  if (!keys.versions(now).contains(challenge.keyVersion())) return false;
                  byte[] emailKey =
                      keys.digest(challenge.keyVersion(), "challenge-email:" + PURPOSE, email);
                  byte[] code =
                      keys.digest(
                          challenge.keyVersion(),
                          "challenge-code",
                          challenge.id() + "\0" + PURPOSE + "\0" + email + "\0" + request.code());
                  if (!challenge.acceptsPasswordReset(member, emailKey, code, now)) return false;
                  replace(member.id(), hash, now);
                  return true;
                }));
    if (!accepted) throw new BusinessException(ErrorCode.INVALID_AUTH_CHALLENGE);
  }

  private void replace(long memberId, String hash, Instant now) {
    members.replacePassword(memberId, hash, now);
    sessions.revokeAll(memberId, now);
    csrf.invalidateMember(memberId);
  }

  private <T> T transaction(Supplier<T> action) {
    try {
      return transactions.execute(status -> action.get());
    } catch (DataAccessException | TransactionException e) {
      throw new BusinessException(ErrorCode.AUTH_UNAVAILABLE);
    }
  }
}
