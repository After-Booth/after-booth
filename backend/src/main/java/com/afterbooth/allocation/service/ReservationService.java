package com.afterbooth.allocation.service;

import com.afterbooth.allocation.domain.AgentHoldRejection;
import com.afterbooth.allocation.domain.Reservation;
import com.afterbooth.allocation.domain.ReservationStatus;
import com.afterbooth.allocation.domain.ReservationTransition;
import com.afterbooth.allocation.domain.TransitionActor;
import com.afterbooth.allocation.dto.HoldItemRequest;
import com.afterbooth.allocation.dto.ReservationResponse;
import com.afterbooth.allocation.repository.ReservationRepository;
import com.afterbooth.allocation.repository.TransitionRepository;
import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.inventory.domain.Item;
import com.afterbooth.inventory.service.ItemStockService;
import com.afterbooth.inventory.service.VehicleFitPolicy;
import com.afterbooth.request.domain.PickupRequest;
import com.afterbooth.request.service.PickupRequestService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationService {
  private final ReservationRepository reservations;
  private final TransitionRepository transitions;
  private final ItemStockService items;
  private final PickupRequestService requests;
  private final VehicleFitPolicy vehicles;
  private final Clock clock;
  private final Duration ttl;

  public ReservationService(
      ReservationRepository reservations,
      TransitionRepository transitions,
      ItemStockService items,
      PickupRequestService requests,
      VehicleFitPolicy vehicles,
      Clock clock,
      @Value("${afterbooth.hold-ttl:PT15M}") Duration ttl) {
    this.reservations = reservations;
    this.transitions = transitions;
    this.items = items;
    this.requests = requests;
    this.vehicles = vehicles;
    this.clock = clock;
    this.ttl = ttl;
  }

  @Transactional
  public ReservationResponse hold(HoldItemRequest input) {
    return hold(input, TransitionActor.USER);
  }

  @Transactional(
      noRollbackFor = {
        BusinessException.class,
        com.afterbooth.allocation.domain.AgentHoldRejectedException.class
      })
  public ReservationResponse holdForAgent(HoldItemRequest input) {
    return hold(input, TransitionActor.AGENT);
  }

  private ReservationResponse hold(HoldItemRequest input, TransitionActor actor) {
    if (input.requestId() == null || input.itemId() == null || input.quantity() <= 0)
      throw new BusinessException(ErrorCode.INVALID_INPUT);
    PickupRequest request = requests.lock(input.requestId());
    Item item = items.get(input.itemId());
    Instant now = clock.instant();
    if (item.isPickupClosed(now)) throw new BusinessException(ErrorCode.PICKUP_CLOSED);
    vehicles.requireFit(item, request.vehicleType());
    if (actor == TransitionActor.AGENT)
      AgentHoldRejection.requireCategory(request.category(), item.category());
    request.requireCapacity(reservations.activeQuantity(request.id()), input.quantity());
    items.decrease(item.id(), input.quantity());
    Reservation reservation =
        reservations.saveAndFlush(
            Reservation.hold(
                request.id(),
                item.id(),
                request.organizationId(),
                input.quantity(),
                ttl,
                item.pickupDeadline(),
                now));
    transitions.save(ReservationTransition.initialHold(reservation.id(), actor, now));
    return response(reservation, false);
  }

  @Transactional(readOnly = true)
  public ReservationResponse get(Long id) {
    return response(find(id), true);
  }

  @Transactional(readOnly = true)
  public List<ReservationResponse> list(Long requestId, Long eventId) {
    return reservations.listWithLabel(requestId, eventId).stream()
        .map(row -> ReservationResponse.from(row.reservation(), row.itemLabel(), null))
        .toList();
  }

  @Transactional
  public Confirmation confirm(Long id) {
    Reservation reservation = find(id);
    Instant now = clock.instant();
    Reservation.ConfirmationDecision decision = reservation.confirmationAt(now);
    if (decision.next() == null) return new Confirmation(null, decision.error());
    if (!apply(reservation, decision.next(), decision.actor(), now))
      return new Confirmation(null, find(id).confirmationConflict());
    if (decision.error() != null) return new Confirmation(null, decision.error());
    return new Confirmation(response(find(id), false), null);
  }

  @Transactional
  public ReservationResponse cancel(Long id) {
    Reservation r =
        reservations.lock(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    if (!apply(r, r.cancel(), TransitionActor.USER, clock.instant()))
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    return response(find(id), false);
  }

  @Transactional
  public boolean expire(Long id) {
    Reservation r = find(id);
    Instant now = clock.instant();
    ReservationStatus next = r.dueTransition(now);
    return next != null && apply(r, next, TransitionActor.SCHEDULER, now);
  }

  @Transactional(readOnly = true)
  public List<Long> due() {
    return reservations.due(clock.instant());
  }

  private boolean apply(Reservation r, ReservationStatus next, TransitionActor actor, Instant now) {
    if (reservations.transition(r.id(), r.status(), next) != 1) return false;
    if (next == ReservationStatus.RELEASED || next == ReservationStatus.EXPIRED)
      items.restore(r.itemId(), r.quantity());
    transitions.save(ReservationTransition.record(r.id(), r.status(), next, actor, now));
    return true;
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public ReservationResponse lockForHandover(Long id) {
    return response(
        reservations.lock(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)), false);
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public ReservationResponse completeHandover(Long id, int accepted, TransitionActor actor) {
    Reservation reservation = find(id);
    Instant now = clock.instant();
    int remainder = reservation.requireHandover(accepted, actor, now);
    if (reservations.handover(id, accepted) != 1)
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    if (remainder > 0) items.restore(reservation.itemId(), remainder);
    transitions.save(
        ReservationTransition.record(
            id, ReservationStatus.CONFIRMED, ReservationStatus.HANDED_OVER, actor, now));
    return response(find(id), false);
  }

  private Reservation find(Long id) {
    return reservations.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
  }

  private ReservationResponse response(Reservation r, boolean history) {
    return ReservationResponse.from(
        r,
        items.get(r.itemId()).label(),
        history
            ? transitions.findByReservationIdOrderByIdAsc(r.id()).stream()
                .map(
                    t ->
                        new ReservationResponse.Transition(
                            t.fromStatus(),
                            t.toStatus(),
                            ReservationResponse.time(t.at()),
                            t.actor()))
                .toList()
            : null);
  }

  public record Confirmation(ReservationResponse response, ErrorCode error) {}
}
