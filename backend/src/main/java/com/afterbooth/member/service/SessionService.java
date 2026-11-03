package com.afterbooth.member.service;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.security.AuthRateLimiter;
import com.afterbooth.global.security.AuthenticatedMember;
import com.afterbooth.global.security.CsrfSessionService;
import com.afterbooth.global.security.JwtService;
import com.afterbooth.member.domain.Member;
import com.afterbooth.member.domain.MemberState;
import com.afterbooth.member.domain.SignupCredentials;
import com.afterbooth.member.dto.AccessTokenResponse;
import com.afterbooth.member.dto.LoginMemberRequest;
import com.afterbooth.member.repository.MemberRepository;
import com.afterbooth.member.repository.SessionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SessionService {
  private final MemberRepository members;
  private final MemberLockService locks;
  private final SessionRepository sessions;
  private final PasswordEncoder passwords;
  private final AuthRateLimiter limits;
  private final CsrfSessionService csrf;
  private final JwtService jwt;
  private final TransactionTemplate transactions;
  private final TransactionTemplate loginTransactions;
  private final SecureRandom random = new SecureRandom();
  private final String dummy;

  public SessionService(
      MemberRepository members,
      MemberLockService locks,
      SessionRepository sessions,
      PasswordEncoder passwords,
      AuthRateLimiter limits,
      CsrfSessionService csrf,
      JwtService jwt,
      PlatformTransactionManager manager) {
    this.members = members;
    this.locks = locks;
    this.sessions = sessions;
    this.passwords = passwords;
    this.limits = limits;
    this.csrf = csrf;
    this.jwt = jwt;
    this.transactions = new TransactionTemplate(manager);
    this.loginTransactions = new TransactionTemplate(manager);
    this.loginTransactions.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    dummy = passwords.encode(randomToken());
  }

  public Issued login(LoginMemberRequest request, String previousCsrf) {
    String email = SignupCredentials.normalizeEmail(request.email());
    String submitted = SignupCredentials.password(request.password());
    LoginResult result =
        transaction(
            loginTransactions,
            () -> {
              members.lockEmail(email);
              Member member = locks.lockByEmail(email);
              limits.checkLoginWithinTransaction(email);
              boolean matches =
                  passwords.matches(
                      submitted,
                      member == null || member.passwordHash() == null
                          ? dummy
                          : member.passwordHash());
              if (!matches || member == null || member.state() == MemberState.WITHDRAWN) {
                limits.loginFailedWithinTransaction(email);
                return new LoginResult(null, ErrorCode.UNAUTHENTICATED);
              }
              if (member.state() != MemberState.ACTIVE) {
                limits.loginFailedWithinTransaction(email);
                return new LoginResult(null, ErrorCode.EMAIL_NOT_VERIFIED);
              }
              Instant now = members.databaseNow();
              UUID id = UUID.randomUUID();
              sessions.create(id, member.id(), member.authVersion(), now);
              return new LoginResult(
                  issue(member, id, now.plusSeconds(604800), now, previousCsrf), null);
            });
    if (result.error() != null) {
      throw new BusinessException(result.error());
    }
    return result.issued();
  }

  public Issued refresh(String token, String previousCsrf) {
    if (!valid(token)) return null;
    return transaction(
        () -> {
          byte[] hash = hash(token);
          var reference = sessions.find(hash);
          if (reference == null) return null;
          Member member = locks.lock(reference.memberId());
          var family = sessions.lock(hash);
          Instant now = members.databaseNow();
          if (family == null) return null;
          if (family.used()) {
            sessions.revoke(family.id(), now);
            return null;
          }
          if (member == null
              || member.state() != MemberState.ACTIVE
              || member.authVersion() != family.authVersion()
              || family.revoked()
              || !family.expiresAt().isAfter(now)) return null;
          sessions.used(hash, now);
          return issue(member, family.id(), family.expiresAt(), now, previousCsrf);
        });
  }

  public void logout(String token) {
    if (!valid(token)) return;
    transaction(
        () -> {
          byte[] hash = hash(token);
          var reference = sessions.find(hash);
          if (reference == null) return null;
          locks.lock(reference.memberId());
          var family = sessions.lock(hash);
          if (family != null) sessions.revoke(family.id(), members.databaseNow());
          return null;
        });
  }

  public void logoutAll(AuthenticatedMember principal) {
    transaction(
        () -> {
          Member member = locks.lock(principal.memberId());
          Instant now = members.databaseNow();
          if (member == null
              || member.state() != MemberState.ACTIVE
              || member.authVersion() != principal.authVersion())
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
          // Authentication is rechecked under the member lock to serialize a concurrent logout.
          jwt.verifyPrincipal(principal, now);
          sessions.revokeAll(member.id(), now);
          csrf.invalidateMember(member.id());
          return null;
        });
  }

  private Issued issue(Member member, UUID id, Instant expires, Instant now, String previousCsrf) {
    String refresh = randomToken();
    sessions.token(hash(refresh), id, now);
    String access = jwt.issue(member.id(), id, member.authVersion(), now);
    CsrfSessionService.Issued issued = csrf.issue(member.id(), previousCsrf);
    long remaining =
        Math.max(0, java.time.Duration.between(members.databaseNow(), expires).getSeconds());
    return new Issued(new AccessTokenResponse(access, "Bearer", 600), refresh, remaining, issued);
  }

  public int cleanupExpired() {
    return transaction(sessions::cleanup);
  }

  private <T> T transaction(Supplier<T> action) {
    return transaction(transactions, action);
  }

  private <T> T transaction(TransactionTemplate template, Supplier<T> action) {
    try {
      return template.execute(status -> action.get());
    } catch (DataAccessException | TransactionException e) {
      throw new BusinessException(ErrorCode.AUTH_UNAVAILABLE);
    }
  }

  private String randomToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static boolean valid(String token) {
    return token != null && token.matches("[A-Za-z0-9_-]{43}");
  }

  private static byte[] hash(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("Required digest unavailable");
    }
  }

  private record LoginResult(Issued issued, ErrorCode error) {}

  public record Issued(
      AccessTokenResponse response, String refresh, long maxAge, CsrfSessionService.Issued csrf) {
    @Override
    public String toString() {
      return "Issued[redacted]";
    }
  }
}
