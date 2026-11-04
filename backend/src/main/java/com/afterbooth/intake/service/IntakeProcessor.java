package com.afterbooth.intake.service;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.security.AuthenticatedMember;
import com.afterbooth.global.security.MemberWorkRegistry;
import com.afterbooth.intake.pipeline.EstimatedLine;
import com.afterbooth.intake.pipeline.IntakePhoto;
import com.afterbooth.intake.pipeline.IntakePipeline;
import java.util.List;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class IntakeProcessor {
  private static final Logger log = LoggerFactory.getLogger(IntakeProcessor.class);
  private final IntakePipeline pipeline;
  private final IntakeProcessingStore store;
  private final Executor executor;
  private final MemberWorkRegistry work;

  public IntakeProcessor(
      IntakePipeline pipeline,
      IntakeProcessingStore store,
      @Qualifier("intakeExecutor") Executor executor,
      MemberWorkRegistry work) {
    this.pipeline = pipeline;
    this.store = store;
    this.executor = executor;
    this.work = work;
  }

  public record Submitted(Long draftId, MemberWorkRegistry.Work<Input> work) {}

  public record Input(Long draftId, IntakePhoto photo, AuthenticatedMember actor) {
    @Override
    public String toString() {
      return "IntakeInput[redacted]";
    }
  }

  public Submitted prepare(Long draftId, IntakePhoto photo, AuthenticatedMember actor) {
    return new Submitted(
        draftId,
        work.register(
            actor.memberId(), new Input(draftId, photo, actor), input -> input.photo().erase()));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void submitted(Submitted event) {
    try {
      executor.execute(() -> event.work().run(this::process));
    } catch (RuntimeException e) {
      log.error(
          "Cannot schedule intake {} errorType={}", event.draftId(), e.getClass().getSimpleName());
      event
          .work()
          .run(input -> fail(input, "Photo processing could not start. Please upload it again."));
    }
  }

  public void process(Input event) {
    try {
      store.check(event.draftId(), event.actor());
      List<EstimatedLine> estimates = pipeline.estimate(event.photo());
      if (estimates.isEmpty()) {
        store.ready(event.draftId(), estimates, event.actor());
        return;
      }
      store.segment(event.draftId(), event.actor());
      List<EstimatedLine> segmented;
      try {
        segmented = pipeline.segment(event.photo(), estimates);
      } catch (RuntimeException e) {
        log.warn(
            "Intake segmentation failed for {} errorType={}",
            event.draftId(),
            e.getClass().getSimpleName());
        segmented = estimates.stream().map(EstimatedLine::samFailed).toList();
      }
      store.ready(event.draftId(), segmented, event.actor());
    } catch (RuntimeException e) {
      log.warn(
          "Intake processing failed for {} errorType={}",
          event.draftId(),
          e.getClass().getSimpleName());
      fail(event, "The items in the photo could not be processed. Please upload it again.");
    }
  }

  private void fail(Input event, String message) {
    try {
      store.fail(event.draftId(), message, event.actor());
    } catch (BusinessException error) {
      if (error.errorCode() != ErrorCode.UNAUTHENTICATED
          && error.errorCode() != ErrorCode.NOT_FOUND)
        log.warn(
            "Cannot store intake failure for {} errorType={}",
            event.draftId(),
            error.getClass().getSimpleName());
    } catch (RuntimeException error) {
      log.warn(
          "Cannot store intake failure for {} errorType={}",
          event.draftId(),
          error.getClass().getSimpleName());
    }
  }
}
