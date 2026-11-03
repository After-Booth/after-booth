package com.afterbooth.intake.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.afterbooth.global.domain.BoundingBox;
import com.afterbooth.intake.domain.DraftCategory;
import com.afterbooth.intake.domain.IntakeDraft;
import com.afterbooth.intake.domain.IntakeStage;
import com.afterbooth.intake.domain.IntakeStatus;
import com.afterbooth.intake.pipeline.EstimatedLine;
import com.afterbooth.intake.pipeline.IntakePhoto;
import com.afterbooth.intake.pipeline.IntakePipeline;
import com.afterbooth.inventory.domain.Condition;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class IntakeProcessorTest {
  private static final String SYNTHETIC_MARKER = "synthetic-intake-log-marker";
  private final IntakePipeline pipeline = mock(IntakePipeline.class);
  private final IntakeProcessingStore store = mock(IntakeProcessingStore.class);
  private final IntakePhoto photo = new IntakePhoto(new byte[] {1}, "image/png");
  private final com.afterbooth.global.security.AuthenticatedMember actor =
      new com.afterbooth.global.security.AuthenticatedMember(1, java.util.UUID.randomUUID(), 0);
  private final IntakeProcessor.Input event = new IntakeProcessor.Input(1L, photo, actor);
  private final EstimatedLine line =
      new EstimatedLine(
          DraftCategory.DISPLAY_RACK,
          1,
          List.of(new BoundingBox(0, 0, 0.2, 0.2)),
          90,
          40,
          180,
          Condition.GOOD,
          new BigDecimal("0.95"),
          false);
  private final IntakeProcessor processor =
      new IntakeProcessor(
          pipeline, store, Runnable::run, new com.afterbooth.global.security.MemberWorkRegistry());

  @Test
  void withdrawalErasesQueuedPhotoAndPreventsModelCalls() {
    var work = new com.afterbooth.global.security.MemberWorkRegistry();
    java.util.List<Runnable> queued = new java.util.ArrayList<>();
    var guarded = new IntakeProcessor(pipeline, store, queued::add, work);
    guarded.submitted(guarded.prepare(1L, photo, actor));
    try (var guard = work.erase(actor.memberId())) {
      guard.commit();
      assertThat(photo.bytes()).containsOnly((byte) 0);
    }
    queued.forEach(Runnable::run);
    verifyNoInteractions(pipeline, store);
  }

  @Test
  void commitsSegmentStageBeforeSamAndThenReady() {
    when(pipeline.estimate(photo)).thenReturn(List.of(line));
    when(pipeline.segment(photo, List.of(line))).thenReturn(List.of(line));
    processor.process(event);
    var order = inOrder(pipeline, store);
    order.verify(pipeline).estimate(photo);
    order.verify(store).segment(1L, actor);
    order.verify(pipeline).segment(photo, List.of(line));
    order.verify(store).ready(1L, List.of(line), actor);
  }

  @Test
  void revokedSubmittedActorCannotStartModelsOrSaveFailureInput() {
    var revoked =
        new com.afterbooth.global.error.BusinessException(
            com.afterbooth.global.error.ErrorCode.UNAUTHENTICATED);
    doThrow(revoked).when(store).check(1L, actor);
    doThrow(revoked).when(store).fail(eq(1L), anyString(), eq(actor));

    processor.process(event);

    verifyNoInteractions(pipeline);
    verify(store, never()).ready(any(), any(), any());
    verify(store, never()).segment(any(), any());
  }

  @Test
  void estimationFailureFailsWithKoreanMessageAndSkipsSam() {
    when(pipeline.estimate(photo)).thenThrow(new IllegalStateException());
    processor.process(event);
    var message = ArgumentCaptor.forClass(String.class);
    verify(store).fail(eq(1L), message.capture(), eq(actor));
    assertThat(message.getValue()).matches(".*[\uac00-\ud7a3].*");
    verify(pipeline, never()).segment(any(), any());
  }

  @Test
  void samFailureKeepsSparkBoxesAndMarksEveryLineForReview() {
    when(pipeline.estimate(photo)).thenReturn(List.of(line));
    when(pipeline.segment(photo, List.of(line))).thenThrow(new IllegalStateException());
    processor.process(event);
    verify(store)
        .ready(
            1L,
            List.of(
                new EstimatedLine(
                    line.category(),
                    line.count(),
                    line.boxes(),
                    90,
                    40,
                    180,
                    line.condition(),
                    line.confidence(),
                    true)),
            actor);
    verify(store, never()).fail(any(), any(), any());
  }

  @Test
  void zeroItemsSkipsSamAndBecomesReady() {
    when(pipeline.estimate(photo)).thenReturn(List.of());
    processor.process(event);
    verify(store).ready(1L, List.of(), actor);
    verify(store, never()).segment(any(), any());
    verify(pipeline, never()).segment(any(), any());
  }

  @Test
  void failedSegmentTransitionFailsDraftAtTheLastPersistedStage() {
    IntakeDraft draft = trackFailure();
    when(pipeline.estimate(photo)).thenReturn(List.of(line));
    doThrow(new IllegalStateException()).when(store).segment(1L, actor);

    processor.process(event);

    assertThat(draft.status()).isEqualTo(IntakeStatus.FAILED);
    assertThat(draft.stage()).isEqualTo(IntakeStage.ESTIMATE);
    assertThat(draft.failureMessage()).matches(".*[\uac00-\ud7a3].*");
    verify(pipeline, never()).segment(any(), any());
    verify(store, never()).ready(any(), any(), any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void failedReadyWriteFailsDraftAtSegmentEvenAfterSamFallback(boolean samFails) {
    IntakeDraft draft = trackFailure();
    when(pipeline.estimate(photo)).thenReturn(List.of(line));
    doAnswer(
            invocation -> {
              draft.beginSegmentation();
              return null;
            })
        .when(store)
        .segment(1L, actor);
    if (samFails) {
      when(pipeline.segment(photo, List.of(line))).thenThrow(new IllegalStateException());
    } else {
      when(pipeline.segment(photo, List.of(line))).thenReturn(List.of(line));
    }
    doThrow(new IllegalStateException()).when(store).ready(eq(1L), any(), eq(actor));

    processor.process(event);

    assertThat(draft.status()).isEqualTo(IntakeStatus.FAILED);
    assertThat(draft.stage()).isEqualTo(IntakeStage.SEGMENT);
    assertThat(draft.failureMessage()).matches(".*[\uac00-\ud7a3].*");
    verify(store).fail(eq(1L), anyString(), eq(actor));
  }

  @Test
  void failedEmptyReadyWriteFailsDraftAtEstimate() {
    IntakeDraft draft = trackFailure();
    when(pipeline.estimate(photo)).thenReturn(List.of());
    doThrow(new IllegalStateException()).when(store).ready(1L, List.of(), actor);

    processor.process(event);

    assertThat(draft.status()).isEqualTo(IntakeStatus.FAILED);
    assertThat(draft.stage()).isEqualTo(IntakeStage.ESTIMATE);
    assertThat(draft.failureMessage()).matches(".*[\uac00-\ud7a3].*");
    verify(store, never()).segment(any(), any());
  }

  @Test
  void rejectionFailsDraftRatherThanLeavingItProcessing() {
    new IntakeProcessor(
            pipeline,
            store,
            task -> {
              throw new RejectedExecutionException();
            },
            new com.afterbooth.global.security.MemberWorkRegistry())
        .submitted(processor.prepare(1L, photo, actor));
    verify(store).fail(eq(1L), anyString(), eq(actor));
    verifyNoInteractions(pipeline);
  }

  @Test
  void twoInvalidSparkAnswersFailTheActualDraftAtEstimate() {
    var gateway = mock(com.afterbooth.ai.AiGateway.class);
    when(gateway.chat(any()))
        .thenReturn(new com.afterbooth.ai.ChatResponse("{}", List.of(), "stop", null));
    var realPipeline =
        new IntakePipeline(
            gateway,
            tools.jackson.databind.json.JsonMapper.builder().build(),
            "minimal",
            new BigDecimal("0.8"),
            0.5);
    var draft = IntakeDraft.start(1L, Instant.EPOCH);
    doAnswer(
            invocation -> {
              draft.fail(invocation.getArgument(1));
              return null;
            })
        .when(store)
        .fail(eq(1L), anyString(), eq(actor));
    new IntakeProcessor(
            realPipeline,
            store,
            Runnable::run,
            new com.afterbooth.global.security.MemberWorkRegistry())
        .process(event);
    assertThat(draft.status()).isEqualTo(IntakeStatus.FAILED);
    assertThat(draft.stage()).isEqualTo(IntakeStage.ESTIMATE);
    assertThat(draft.failureMessage()).matches(".*[\uac00-\ud7a3].*");
    verify(gateway, times(2)).chat(any());
    verify(gateway, never()).segment(any());
    verify(store, never()).ready(any(), any(), any());
  }

  @Test
  void intakeFailuresLogOnlyErrorTypeWithoutSyntheticMessageOrThrowable() {
    var logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(IntakeProcessor.class);
    var captured =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    captured.start();
    logger.addAppender(captured);
    try {
      new IntakeProcessor(
              pipeline,
              store,
              task -> {
                throw new RejectedExecutionException(SYNTHETIC_MARKER);
              },
              new com.afterbooth.global.security.MemberWorkRegistry())
          .submitted(processor.prepare(1L, photo, actor));
      assertOnlyErrorTypeLogged(
          captured, "Cannot schedule intake 1 errorType=RejectedExecutionException");
      captured.list.clear();

      when(pipeline.estimate(photo)).thenReturn(List.of(line));
      when(pipeline.segment(photo, List.of(line)))
          .thenThrow(new IllegalStateException(SYNTHETIC_MARKER));
      processor.process(event);
      assertOnlyErrorTypeLogged(
          captured, "Intake segmentation failed for 1 errorType=IllegalStateException");
      captured.list.clear();

      when(pipeline.estimate(photo)).thenThrow(new IllegalStateException(SYNTHETIC_MARKER));
      processor.process(event);
      assertOnlyErrorTypeLogged(
          captured, "Intake processing failed for 1 errorType=IllegalStateException");
      captured.list.clear();

      doThrow(
              new com.afterbooth.global.error.BusinessException(
                  com.afterbooth.global.error.ErrorCode.INTERNAL_ERROR, SYNTHETIC_MARKER))
          .when(store)
          .fail(eq(1L), anyString(), eq(actor));
      processor.process(event);
      assertOnlyErrorTypeLogged(
          captured,
          "Intake processing failed for 1 errorType=IllegalStateException",
          "Cannot store intake failure for 1 errorType=BusinessException");
      captured.list.clear();

      doThrow(new IllegalArgumentException(SYNTHETIC_MARKER))
          .when(store)
          .fail(eq(1L), anyString(), eq(actor));
      processor.process(event);
      assertOnlyErrorTypeLogged(
          captured,
          "Intake processing failed for 1 errorType=IllegalStateException",
          "Cannot store intake failure for 1 errorType=IllegalArgumentException");
    } finally {
      logger.detachAppender(captured);
      captured.stop();
    }
  }

  private static void assertOnlyErrorTypeLogged(
      ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured,
      String... expected) {
    assertThat(captured.list.stream().map(event -> event.getFormattedMessage()).toList())
        .containsExactly(expected);
    assertThat(
            captured.list.stream()
                .noneMatch(
                    event ->
                        event.getFormattedMessage().contains(SYNTHETIC_MARKER)
                            || event.getThrowableProxy() != null))
        .isTrue();
  }

  private IntakeDraft trackFailure() {
    IntakeDraft draft = IntakeDraft.start(1L, Instant.EPOCH);
    doAnswer(
            invocation -> {
              draft.fail(invocation.getArgument(1));
              return null;
            })
        .when(store)
        .fail(eq(1L), anyString(), eq(actor));
    return draft;
  }
}
