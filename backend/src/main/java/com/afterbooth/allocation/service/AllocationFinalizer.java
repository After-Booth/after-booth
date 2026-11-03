package com.afterbooth.allocation.service;

import com.afterbooth.allocation.domain.AllocationCandidate;
import com.afterbooth.allocation.domain.AllocationOutcome;
import com.afterbooth.allocation.dto.AllocationResult;
import com.afterbooth.allocation.dto.AllocationResult.*;
import com.afterbooth.allocation.dto.HoldItemRequest;
import com.afterbooth.allocation.dto.ReservationResponse;
import com.afterbooth.allocation.repository.ReservationRepository;
import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.security.DomainAccess;
import com.afterbooth.inventory.service.*;
import com.afterbooth.request.service.PickupRequestService;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AllocationFinalizer {
  private final DomainAccess access;
  private final ReservationService holds;
  private final ReservationRepository reservations;
  private final PickupRequestService requests;
  private final ItemStockService stock;
  private final ItemService inventory;
  private final VehicleFitPolicy vehicles;
  private final Clock clock;

  public AllocationFinalizer(
      DomainAccess access,
      ReservationService holds,
      ReservationRepository reservations,
      PickupRequestService requests,
      ItemStockService stock,
      ItemService inventory,
      VehicleFitPolicy vehicles,
      Clock clock) {
    this.access = access;
    this.holds = holds;
    this.reservations = reservations;
    this.requests = requests;
    this.stock = stock;
    this.inventory = inventory;
    this.vehicles = vehicles;
    this.clock = clock;
  }

  @Transactional
  public AllocationResult complete(
      Long requestId,
      List<HoldItemRequest> proposals,
      Map<Long, Rejection> rejected,
      List<ToolTrace> trace,
      String explanation,
      List<Long> responseOrganizations) {
    List<Long> organizations = new ArrayList<>(responseOrganizations);
    organizations.add(access.request(requestId));
    for (var proposal : proposals) organizations.add(stock.get(proposal.itemId()).organizationId());
    access.lockOrganizations(organizations);
    var request = requests.lock(requestId);
    if (reservations.activeQuantity(requestId) > 0)
      throw new BusinessException(ErrorCode.REQUEST_ALREADY_HELD);
    List<ReservationResponse> accepted = new ArrayList<>();
    Map<Long, Rejection> failures = new LinkedHashMap<>(rejected);
    List<ToolTrace> finalTrace = new ArrayList<>(trace);
    List<Integer> proposedTraceIndices = new ArrayList<>();
    for (int i = 0; i < finalTrace.size(); i++)
      if (finalTrace.get(i).name().equals("holdQuantity")
          && finalTrace.get(i).outcome().equals("OK")) proposedTraceIndices.add(i);
    int proposalIndex = 0;
    for (var proposal : proposals) {
      int traceIndex = proposedTraceIndices.get(proposalIndex++);
      try {
        accepted.add(holds.holdForAgent(proposal));
      } catch (BusinessException failure) {
        if (!Set.of(
                ErrorCode.QTY_UNAVAILABLE,
                ErrorCode.QTY_EXCEEDS_REQUEST,
                ErrorCode.PICKUP_CLOSED,
                ErrorCode.VEHICLE_TOO_SMALL)
            .contains(failure.errorCode())) throw failure;
        var item = stock.get(proposal.itemId());
        String dimension =
            failure.errorCode() == ErrorCode.VEHICLE_TOO_SMALL
                ? vehicles.checkFit(item, request.vehicleType()).limitingDimension()
                : null;
        String message = AllocationCandidate.message(item, failure.errorCode().name(), dimension);
        failures.put(
            item.id(), new Rejection(item.id(), failure.errorCode().name(), message, dimension));
        finalTrace.set(
            traceIndex,
            new ToolTrace(
                "holdQuantity",
                Map.of("itemId", item.id(), "quantity", proposal.quantity()),
                "REJECTED",
                failure.errorCode().name(),
                message));
      }
    }
    return AllocationOutcome.assemble(
        request,
        accepted,
        failures,
        finalTrace,
        inventory.candidates().stream()
            .filter(item -> organizations.contains(item.organizationId()))
            .toList(),
        item -> vehicles.checkFit(item, request.vehicleType()),
        clock.instant(),
        accepted.size() == proposals.size() ? explanation : null);
  }
}
