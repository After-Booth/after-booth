package com.afterbooth;

import com.afterbooth.global.security.AuthenticatedMember;
import com.afterbooth.global.security.JwtService;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

public final class DomainAuthTestSupport {
  private DomainAuthTestSupport() {}

  public static java.util.concurrent.ExecutorService executor(
      java.util.concurrent.ExecutorService delegate) {
    return new org.springframework.security.concurrent.DelegatingSecurityContextExecutorService(
        delegate);
  }

  public static AuthenticatedMember seedOwner(JdbcTemplate jdbc, Clock clock, long organizationId) {
    var ids =
        jdbc.queryForList(
            "select primary_member_id from organization where id=? and primary_member_id is not null",
            Long.class,
            organizationId);
    long member;
    if (ids.isEmpty()) {
      member =
          jdbc.queryForObject(
              "insert into member(email_normalized,password_hash,state,created_at,verified_at) values(?,'synthetic-nonlogin-hash','ACTIVE',?,?) returning id",
              Long.class,
              UUID.randomUUID() + "@example.invalid",
              Timestamp.from(clock.instant()),
              Timestamp.from(clock.instant()));
      jdbc.update("update organization set primary_member_id=? where id=?", member, organizationId);
    } else member = ids.getFirst();
    UUID session = UUID.randomUUID();
    long version =
        jdbc.queryForObject("select auth_version from member where id=?", Long.class, member);
    jdbc.update(
        "insert into refresh_session(id,member_id,auth_version,created_at,expires_at) values(?,?,?,statement_timestamp(),statement_timestamp()+interval '168 hours')",
        session,
        member,
        version);
    var actor = new AuthenticatedMember(member, session, version);
    use(actor);
    return actor;
  }

  public static void use(AuthenticatedMember actor) {
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(actor, null, java.util.List.of()));
    SecurityContextHolder.setContext(context);
  }

  public static String bearer(JwtService jwt, Clock clock) {
    var actor =
        (AuthenticatedMember) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return "Bearer "
        + jwt.issue(actor.memberId(), actor.sessionId(), actor.authVersion(), clock.instant());
  }
}
