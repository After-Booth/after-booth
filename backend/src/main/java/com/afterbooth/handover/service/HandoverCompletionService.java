package com.afterbooth.handover.service;

import com.afterbooth.allocation.domain.TransitionActor;
import com.afterbooth.allocation.dto.ReservationResponse;
import com.afterbooth.allocation.service.ReservationService;
import com.afterbooth.global.domain.BoundingBox;
import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.handover.domain.HandoverLog;
import com.afterbooth.handover.domain.HandoverStatus;
import com.afterbooth.handover.dto.HandoverResult;
import com.afterbooth.handover.repository.HandoverLogRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HandoverCompletionService {
  private final ReservationService reservations;
  private final HandoverLogRepository logs;
  private final Clock clock;

  public HandoverCompletionService(
      ReservationService reservations, HandoverLogRepository logs, Clock clock) {
    this.reservations = reservations;
    this.logs = logs;
    this.clock = clock;
  }

  @Transactional
  public ReservationResponse completeMatched(Long id) {
    ReservationResponse reservation = reservations.lockForHandover(id);
    HandoverLog log = newest(id);
    int accepted = log.requireMatched(reservation.quantity());
    return reservations.completeHandover(id, accepted, TransitionActor.HANDOVER);
  }

  @Transactional
  public ReservationResponse resolve(Long id, int accepted) {
    ReservationResponse reservation = reservations.lockForHandover(id);
    HandoverLog log = newest(id);
    log.resolve(accepted, reservation.quantity());
    logs.flush();
    return reservations.completeHandover(id, accepted, TransitionActor.USER);
  }

  @Transactional
  public HandoverResult record(Long id, int count, List<BoundingBox> boxes, boolean allSegmented) {
    ReservationResponse reservation = reservations.lockForPhotoHandover(id);
    HandoverLog log =
        logs.saveAndFlush(
            HandoverLog.recordPhoto(
                id, reservation.quantity(), count, allSegmented, boxes, clock.instant()));
    if (log.status() == HandoverStatus.COMPLETED) reservation = completeMatched(id);
    return new HandoverResult(
        reservation, log.status(), log.expectedQty(), log.countedQty(), log.boxes());
  }

  @Transactional
  public HandoverResult resolveResult(Long id, int accepted) {
    ReservationResponse reservation = resolve(id, accepted);
    HandoverLog log = newest(id);
    return new HandoverResult(
        reservation, HandoverStatus.COMPLETED, log.expectedQty(), log.countedQty(), log.boxes());
  }

  private HandoverLog newest(Long id) {
    return logs.findFirstByReservationIdOrderByIdDesc(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TRANSITION));
  }
}
