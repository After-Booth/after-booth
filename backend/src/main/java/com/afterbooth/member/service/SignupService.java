package com.afterbooth.member.service;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.security.AuthRateLimiter;
import com.afterbooth.global.security.HmacKeyRing;
import com.afterbooth.global.security.MemberWorkRegistry;
import com.afterbooth.member.domain.Member;
import com.afterbooth.member.domain.SignupChallenge;
import com.afterbooth.member.domain.SignupCredentials;
import com.afterbooth.member.dto.AuthAccepted;
import com.afterbooth.member.dto.ChallengeIssued;
import com.afterbooth.member.dto.RegisterMemberRequest;
import com.afterbooth.member.dto.VerifyEmailRequest;
import com.afterbooth.member.mail.MailGateway;
import com.afterbooth.member.mail.MailMessage;
import com.afterbooth.member.repository.MemberRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SignupService {
  private static final String PURPOSE = "SIGNUP_VERIFY";
  private final MemberRepository members;
  private final PasswordEncoder passwords;
  private final AuthRateLimiter limits;
  private final HmacKeyRing keys;
  private final MailGateway mail;
  private final Executor mailExecutor;
  private final MemberWorkRegistry work;
  private final TransactionTemplate transactions;
  private final SecureRandom random = new SecureRandom();

  public SignupService(
      MemberRepository members,
      PasswordEncoder passwords,
      AuthRateLimiter limits,
      HmacKeyRing keys,
      MailGateway mail,
      @Qualifier("signupMailExecutor") Executor mailExecutor,
      PlatformTransactionManager transactionManager,
      MemberWorkRegistry work) {
    this.members = members;
    this.passwords = passwords;
    this.limits = limits;
    this.keys = keys;
    this.mail = mail;
    this.mailExecutor = mailExecutor;
    this.work = work;
    transactions = new TransactionTemplate(transactionManager);
  }

  public AuthAccepted register(RegisterMemberRequest request) {
    String email = SignupCredentials.normalizeEmail(request.email());
    String submitted = SignupCredentials.password(request.password());
    boolean allowed = limits.allowRegistration(email);
    String hash = passwords.encode(submitted);
    try {
      transactions.executeWithoutResult(
          status -> {
            members.lockEmail(email);
            Member existing = members.findForUpdate(email);
            Instant now = members.databaseNow();
            if (!keys.versions(now).containsAll(members.activeBlockVersions(now)))
              throw unavailable();
            boolean blocked = false;
            for (String version : keys.versions(now))
              blocked |=
                  members.blocked(version, keys.digest(version, "withdrawn-email", email), now);
            if (allowed && existing == null && !blocked) members.register(email, hash, now);
          });
      return AuthAccepted.accepted();
    } catch (DataAccessException | TransactionException e) {
      throw unavailable();
    }
  }

  public ChallengeIssued requestVerification(String inputEmail) {
    return requestChallenge(inputEmail, MailMessage.Purpose.SIGNUP_VERIFY);
  }

  public ChallengeIssued requestPasswordReset(String inputEmail) {
    return requestChallenge(inputEmail, MailMessage.Purpose.PASSWORD_RESET);
  }

  private ChallengeIssued requestChallenge(String inputEmail, MailMessage.Purpose purpose) {
    String email = SignupCredentials.normalizeEmail(inputEmail);
    boolean allowed =
        purpose == MailMessage.Purpose.SIGNUP_VERIFY
            ? limits.allowVerificationIssuance(email)
            : limits.allowPasswordResetIssuance(email);
    String id = identifier();
    String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    String version = keys.currentVersion();
    try {
      MemberWorkRegistry.Work<MailMessage> message =
          transactions.execute(
              status -> {
                members.lockEmail(email);
                Member member = members.findForUpdate(email);
                Instant now = members.databaseNow();
                boolean real =
                    allowed
                        && member != null
                        && (purpose == MailMessage.Purpose.SIGNUP_VERIFY
                            ? member.acceptsSignupVerification()
                            : member.state() == com.afterbooth.member.domain.MemberState.ACTIVE);
                if (allowed)
                  for (String old : keys.versions(now))
                    members.invalidateChallenges(
                        old, emailKey(old, email, purpose.name()), purpose.name(), now);
                members.insertChallenge(
                    id,
                    real ? member.id() : null,
                    emailKey(version, email, purpose.name()),
                    version,
                    purpose.name(),
                    real ? codeDigest(version, id, email, code, purpose.name()) : null,
                    now);
                return real
                    ? work.register(
                        member.id(),
                        new MailMessage(id, purpose, email, code, now.plusSeconds(600)))
                    : null;
              });
      if (message != null) dispatch(message);
      return new ChallengeIssued("요청을 처리했어요. 이메일의 인증 코드를 확인해 주세요.", id);
    } catch (DataAccessException | TransactionException e) {
      throw unavailable();
    }
  }

  public void verify(VerifyEmailRequest request) {
    String email = SignupCredentials.normalizeEmail(request.email());
    SignupCredentials.challenge(request.challengeId(), request.code());
    String hash = passwords.encode(SignupCredentials.password(request.password()));
    boolean accepted;
    try {
      accepted =
          Boolean.TRUE.equals(
              transactions.execute(
                  status -> {
                    members.lockEmail(email);
                    Member member = members.findForUpdate(email);
                    SignupChallenge challenge = members.challengeForUpdate(request.challengeId());
                    Instant now = members.databaseNow();
                    if (challenge == null) return false;
                    members.attempt(challenge.id());
                    if (!keys.versions(now).contains(challenge.keyVersion())) return false;
                    if (!challenge.accepts(
                        member,
                        emailKey(challenge.keyVersion(), email),
                        codeDigest(challenge.keyVersion(), challenge.id(), email, request.code()),
                        now)) return false;
                    return members.activate(member.id(), hash, challenge.id(), now);
                  }));
    } catch (DataAccessException | TransactionException e) {
      throw unavailable();
    }
    if (!accepted) throw new BusinessException(ErrorCode.INVALID_AUTH_CHALLENGE);
  }

  private byte[] emailKey(String version, String email) {
    return emailKey(version, email, PURPOSE);
  }

  private byte[] emailKey(String version, String email, String purpose) {
    return keys.digest(version, "challenge-email:" + purpose, email);
  }

  private byte[] codeDigest(String version, String id, String email, String code) {
    return codeDigest(version, id, email, code, PURPOSE);
  }

  private byte[] codeDigest(String version, String id, String email, String code, String purpose) {
    return keys.digest(version, "challenge-code", id + "\0" + purpose + "\0" + email + "\0" + code);
  }

  private String identifier() {
    byte[] value = new byte[32];
    random.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private void dispatch(MemberWorkRegistry.Work<MailMessage> message) {
    try {
      mailExecutor.execute(
          () -> {
            try {
              message.run(mail::send);
            } catch (RuntimeException ignored) {
              /* Delivery never changes the public response. */
            }
          });
    } catch (RejectedExecutionException ignored) {
      message.discard();
      /* A later request can issue a fresh challenge. */
    }
  }

  private static BusinessException unavailable() {
    return new BusinessException(ErrorCode.AUTH_UNAVAILABLE);
  }
}
