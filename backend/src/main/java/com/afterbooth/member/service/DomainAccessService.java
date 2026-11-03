package com.afterbooth.member.service;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.security.AuthenticatedMember;
import com.afterbooth.global.security.DomainAccess;
import com.afterbooth.member.domain.MemberState;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class DomainAccessService implements DomainAccess {
  private final JdbcTemplate jdbc;
  private final MemberLockService locks;
  private final Clock clock;
  private final JsonMapper json;
  private final com.afterbooth.global.security.JwtService jwt;

  public DomainAccessService(
      JdbcTemplate jdbc,
      MemberLockService locks,
      Clock clock,
      JsonMapper json,
      com.afterbooth.global.security.JwtService jwt) {
    this.jdbc = jdbc;
    this.locks = locks;
    this.clock = clock;
    this.json = json;
    this.jwt = jwt;
  }

  @Override
  public AuthenticatedMember actor() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !(authentication.getPrincipal() instanceof AuthenticatedMember actor))
      throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    validate(actor);
    return actor;
  }

  private void validate(AuthenticatedMember actor) {
    jwt.verifyPrincipal(actor, clock.instant());
  }

  @Override
  public long organizationId() {
    return only(
        jdbc.queryForList(
            "select id from organization where primary_member_id=?",
            Long.class,
            actor().memberId()));
  }

  @Override
  public boolean owner(long id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select exists(select 1 from organization where id=? and primary_member_id=?)",
            Boolean.class,
            id,
            actor().memberId()));
  }

  @Override
  public boolean activeOrganization(long id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select exists(select 1 from organization o join member m on m.id=o.primary_member_id where o.id=? and m.state='ACTIVE')",
            Boolean.class,
            id));
  }

  @Override
  public void organization(long id) {
    if (!owner(id)) throw new BusinessException(ErrorCode.NOT_FOUND);
  }

  private long owned(String query, long id) {
    long organization = only(jdbc.queryForList(query, Long.class, id));
    if (!owner(organization)) throw new BusinessException(ErrorCode.NOT_FOUND);
    return organization;
  }

  @Override
  public long event(long id) {
    return owned("select organization_id from event where id=?", id);
  }

  @Override
  public long draft(long id) {
    return owned(
        "select e.organization_id from intake_draft d join event e on e.id=d.event_id where d.id=?",
        id);
  }

  @Override
  public long draft(long id, AuthenticatedMember actor) {
    validate(actor);
    long org =
        only(
            jdbc.queryForList(
                "select e.organization_id from intake_draft d join event e on e.id=d.event_id join organization o on o.id=e.organization_id where d.id=? and o.primary_member_id=?",
                Long.class,
                id,
                actor.memberId()));
    return org;
  }

  @Override
  public long request(long id) {
    return owned("select organization_id from pickup_request where id=?", id);
  }

  @Override
  public List<Long> reservationOrganizations(long id) {
    return jdbc
        .query(
            "select i.organization_id,r.organization_id from reservation r join item i on i.id=r.item_id where r.id=? and (r.retention_until is null or r.retention_until>clock_timestamp())",
            (rs, n) -> List.of(rs.getLong(1), rs.getLong(2)),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  @Override
  public List<Long> reservation(long id, Role role) {
    List<Long> ids = reservationOrganizations(id);
    boolean giver = owner(ids.get(0)), receiver = owner(ids.get(1));
    if (!giver && !receiver) throw new BusinessException(ErrorCode.NOT_FOUND);
    if ((role == Role.GIVER && !giver) || (role == Role.RECEIVER && !receiver))
      throw new BusinessException(ErrorCode.FORBIDDEN);
    return ids;
  }

  @Override
  public void item(long id) {
    long organization =
        only(jdbc.queryForList("select organization_id from item where id=?", Long.class, id));
    actor();
    if (!activeOrganization(organization)) throw new BusinessException(ErrorCode.NOT_FOUND);
  }

  @Override
  @Transactional
  public void lockOrganizations(Collection<Long> ids) {
    lockOrganizations(ids, actor());
  }

  @Override
  @Transactional
  public void lockOrganizations(Collection<Long> ids, AuthenticatedMember actor) {
    List<Long> members = members(ids);
    members = new ArrayList<>(members);
    members.add(actor.memberId());
    var locked = locks.lockAll(members);
    validate(actor);
    for (var member : locked)
      if (member == null || member.state() != MemberState.ACTIVE)
        throw new BusinessException(ErrorCode.NOT_FOUND);
    for (Long id : ids)
      if (!activeOrganization(id)) throw new BusinessException(ErrorCode.NOT_FOUND);
  }

  @Override
  @Transactional
  public void lockSystemOrganizations(Collection<Long> ids) {
    locks.lockAll(members(ids));
  }

  private List<Long> members(Collection<Long> ids) {
    return ids.stream()
        .distinct()
        .flatMap(
            id ->
                jdbc
                    .queryForList(
                        "select primary_member_id from organization where id=? and primary_member_id is not null",
                        Long.class,
                        id)
                    .stream())
        .distinct()
        .sorted()
        .toList();
  }

  @Override
  public Map<String, Object> currentMember() {
    long id = actor().memberId();
    return jdbc
        .query(
            "select m.id,m.email_normalized,m.state,o.id from member m join organization o on o.primary_member_id=m.id where m.id=? and m.state='ACTIVE'",
            (rs, n) ->
                Map.<String, Object>of(
                    "id",
                    rs.getLong(1),
                    "email",
                    rs.getString(2),
                    "state",
                    rs.getString(3),
                    "organizationId",
                    rs.getLong(4)),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
  }

  @Override
  public Permission mutation(String path, byte[] body) {
    var principal = actor();
    String canonical = org.springframework.web.util.UriUtils.decode(path, StandardCharsets.UTF_8);
    Supplier<List<Long>> organizations;
    if (canonical.equals("/api/events")) organizations = () -> List.of(organizationId());
    else if (canonical.equals("/api/intake/drafts")) {
      String input = new String(body, StandardCharsets.UTF_8);
      long id;
      try {
        id = Long.parseLong(input.substring("eventId=".length(), input.indexOf('\n')));
      } catch (RuntimeException e) {
        throw new BusinessException(ErrorCode.INVALID_INPUT);
      }
      organizations = () -> List.of(event(id));
    } else if (canonical.matches("/api/intake/drafts/\\d+/confirm")) {
      long id = Long.parseLong(canonical.split("/")[4]);
      organizations = () -> List.of(draft(id));
    } else if (canonical.equals("/api/pickup-requests")) {
      long id = field(body, "organizationId");
      organizations =
          () -> {
            organization(id);
            return List.of(id);
          };
    } else if (canonical.matches("/api/pickup-requests/\\d+/allocation")) {
      long id = Long.parseLong(canonical.split("/")[3]);
      organizations =
          () -> {
            long org = request(id);
            List<Long> result = new ArrayList<>();
            result.add(org);
            result.addAll(
                jdbc.queryForList(
                    "select distinct i.organization_id from reservation r join item i on i.id=r.item_id where r.request_id=?",
                    Long.class,
                    id));
            return result.stream().distinct().sorted().toList();
          };
    } else if (canonical.equals("/api/reservations")) {
      long request = field(body, "requestId"), item = field(body, "itemId");
      organizations =
          () -> {
            long org = request(request);
            item(item);
            long giver =
                only(
                    jdbc.queryForList(
                        "select organization_id from item where id=?", Long.class, item));
            return List.of(org, giver);
          };
    } else if (canonical.matches("/api/reservations/\\d+/(confirm|cancel|handover(/resolve)?)")) {
      long id = Long.parseLong(canonical.split("/")[3]);
      Role role = canonical.contains("/handover") ? Role.GIVER : Role.RECEIVER;
      organizations = () -> reservation(id, role);
    } else throw new BusinessException(ErrorCode.FORBIDDEN);
    return new Permission() {
      public long actorId() {
        return principal.memberId();
      }

      public List<Long> organizations() {
        return organizations.get();
      }

      public void authorize() {
        validate(principal);
        organizations.get();
      }

      public void lock() {
        lockOrganizations(organizations.get(), principal);
        organizations.get();
      }
    };
  }

  private long field(byte[] body, String field) {
    try {
      var node = json.readTree(body).path(field);
      if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() <= 0)
        throw new BusinessException(ErrorCode.INVALID_INPUT);
      return node.longValue();
    } catch (BusinessException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new BusinessException(ErrorCode.INVALID_INPUT);
    }
  }

  @Override
  public List<Long> responseOrganizations(Object response) {
    List<Long> ids = new ArrayList<>();
    collectOrganizations(json.valueToTree(response), ids);
    return ids.stream().distinct().sorted().toList();
  }

  private void collectOrganizations(tools.jackson.databind.JsonNode node, List<Long> ids) {
    if (node == null) return;
    if (node.isObject()) {
      if (node.path("organizationId").isIntegralNumber())
        ids.add(node.path("organizationId").longValue());
      if (node.path("itemId").isIntegralNumber())
        ids.addAll(
            jdbc.queryForList(
                "select organization_id from item where id=?",
                Long.class,
                node.path("itemId").longValue()));
      for (var property : node.properties()) collectOrganizations(property.getValue(), ids);
    } else if (node.isArray()) for (var child : node) collectOrganizations(child, ids);
  }

  private long only(List<Long> ids) {
    if (ids.size() != 1 || Objects.isNull(ids.getFirst()))
      throw new BusinessException(ErrorCode.NOT_FOUND);
    return ids.getFirst();
  }
}
