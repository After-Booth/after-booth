package com.afterbooth.request.service;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.security.DomainAccess;
import com.afterbooth.request.domain.PickupRequest;
import com.afterbooth.request.dto.CreatePickupRequest;
import com.afterbooth.request.dto.PickupRequestResponse;
import com.afterbooth.request.repository.PickupRequestRepository;
import com.afterbooth.request.repository.RequestReferences;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PickupRequestService {
  private final PickupRequestRepository repository;
  private final RequestReferences references;
  private final Clock clock;
  private final JsonMapper json;
  private final DomainAccess access;

  public PickupRequestService(
      PickupRequestRepository repository,
      RequestReferences references,
      Clock clock,
      JsonMapper json,
      DomainAccess access) {
    this.repository = repository;
    this.references = references;
    this.clock = clock;
    this.json = json;
    this.access = access;
  }

  @Transactional
  public PickupRequestResponse create(String body) {
    CreatePickupRequest input = CreatePickupRequest.parse(json, body);
    access.organization(input.organizationId());
    if (!references.organizationExists(input.organizationId()))
      throw new BusinessException(ErrorCode.INVALID_INPUT);
    access.lockOrganizations(List.of(input.organizationId()));
    PickupRequest entity =
        PickupRequest.create(
            input.organizationId(),
            input.category(),
            input.quantity(),
            input.vehicleType(),
            input.pickupDeadline() == null
                ? null
                : input.pickupDeadline().toInstant().truncatedTo(ChronoUnit.MICROS),
            input.purpose(),
            input.transcript(),
            clock.instant().truncatedTo(ChronoUnit.MICROS));
    PickupRequestResponse response = PickupRequestResponse.from(repository.saveAndFlush(entity));
    return response;
  }

  @Transactional(readOnly = true)
  public PickupRequestResponse get(Long id) {
    access.request(id);
    return PickupRequestResponse.from(
        repository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public PickupRequest lock(Long id) {
    access.request(id);
    return repository.lock(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  @Transactional(readOnly = true)
  public PickupRequest findForAllocation(Long id) {
    access.request(id);
    return repository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }
}
