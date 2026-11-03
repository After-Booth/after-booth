package com.afterbooth.inventory.service;

import com.afterbooth.global.security.DomainAccess;
import com.afterbooth.inventory.dto.OrganizationResponse;
import com.afterbooth.inventory.repository.OrganizationRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {
  private final OrganizationRepository organizations;
  private final DomainAccess access;

  OrganizationService(OrganizationRepository organizations, DomainAccess access) {
    this.organizations = organizations;
    this.access = access;
  }

  @Transactional(readOnly = true)
  public List<OrganizationResponse> list() {
    access.actor();
    return organizations.findAllByOrderByIdAsc().stream()
        .filter(o -> access.owner(o.id()))
        .map(OrganizationResponse::from)
        .toList();
  }
}
