package com.afterbooth.global.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface DomainAccess {
  AuthenticatedMember actor();

  long organizationId();

  boolean owner(long organizationId);

  boolean activeOrganization(long organizationId);

  void organization(long organizationId);

  long event(long id);

  long draft(long id);

  long draft(long id, AuthenticatedMember actor);

  long request(long id);

  List<Long> reservation(long id, Role role);

  List<Long> reservationOrganizations(long id);

  void item(long id);

  void lockOrganizations(Collection<Long> ids);

  void lockOrganizations(Collection<Long> ids, AuthenticatedMember actor);

  void lockSystemOrganizations(Collection<Long> ids);

  Map<String, Object> currentMember();

  Permission mutation(String path, byte[] body);

  List<Long> responseOrganizations(Object response);

  enum Role {
    PARTY,
    RECEIVER,
    GIVER
  }

  interface Permission {
    long actorId();

    List<Long> organizations();

    void authorize();

    void lock();
  }
}
