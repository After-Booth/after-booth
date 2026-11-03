package com.afterbooth.handover.service;

import com.afterbooth.ai.AiCallException;
import com.afterbooth.ai.HandoverPhotoCounter;
import com.afterbooth.ai.MissingFixtureException;
import com.afterbooth.allocation.service.ReservationService;
import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.handover.dto.HandoverResult;
import com.afterbooth.intake.domain.IntakeUpload;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class HandoverService {
  private final ReservationService reservations;
  private final HandoverPhotoCounter counter;
  private final HandoverCompletionService completion;
  private final com.afterbooth.global.security.DomainAccess access;
  private final com.afterbooth.global.security.MemberWorkRegistry work;

  public HandoverService(
      ReservationService reservations,
      HandoverPhotoCounter counter,
      HandoverCompletionService completion,
      com.afterbooth.global.security.DomainAccess access,
      com.afterbooth.global.security.MemberWorkRegistry work) {
    this.reservations = reservations;
    this.access = access;
    this.work = work;
    this.counter = counter;
    this.completion = completion;
  }

  public Supplier<HandoverResult> prepare(Long id, byte[] bytes, String mediaType) {
    return work.call(access.actor().memberId(), () -> prepareOwned(id, bytes, mediaType));
  }

  private Supplier<HandoverResult> prepareOwned(Long id, byte[] bytes, String mediaType) {
    IntakeUpload upload = new IntakeUpload(bytes, mediaType);
    var category = reservations.preparePhotoHandover(id);
    HandoverPhotoCounter.Result result;
    try {
      result = counter.count(upload.bytes(), upload.mediaType(), category);
    } catch (AiCallException | MissingFixtureException failure) {
      throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
    }
    return () -> completion.record(id, result.count(), result.boxes(), result.allSegmented());
  }

  public HandoverResult resolve(Long id, int accepted) {
    return completion.resolveResult(id, accepted);
  }
}
