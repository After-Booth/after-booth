package com.afterbooth.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.afterbooth.allocation.domain.Reservation;
import com.afterbooth.global.domain.BoundingBox;
import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.global.idempotency.IdempotencyRecord;
import com.afterbooth.handover.domain.HandoverLog;
import com.afterbooth.handover.domain.HandoverStatus;
import com.afterbooth.intake.domain.DraftCategory;
import com.afterbooth.intake.domain.IntakeDraftLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreDomainTest {
  private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
  private static final Instant DEADLINE = NOW.plusSeconds(3600);

  @Test
  void newInventoryStartsFullyAvailableAndClosesAtItsDeadline() {
    Item item =
        Item.register(
            1L,
            1L,
            null,
            ItemCategory.DISPLAY_RACK,
            new Dimensions(90, 40, 180),
            Condition.GOOD,
            4,
            DEADLINE,
            VehicleType.VAN,
            null);
    assertThat(item.availableQty()).isEqualTo(item.totalQty()).isEqualTo(4);
    assertThat(item.isPickupClosed(DEADLINE.minusSeconds(1))).isFalse();
    assertThat(item.isPickupClosed(DEADLINE)).isTrue();
  }

  @Test
  void inventoryRejectsNonPositiveQuantityAndDimensions() {
    assertThatThrownBy(
            () ->
                Item.register(
                    1L,
                    1L,
                    null,
                    ItemCategory.DISPLAY_RACK,
                    new Dimensions(90, 40, 180),
                    Condition.GOOD,
                    0,
                    DEADLINE,
                    null,
                    null))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> new Dimensions(0, 40, 180)).isInstanceOf(BusinessException.class);
  }

  @Test
  void holdMustExpireAfterCreationAndNoLaterThanPickup() {
    assertThatThrownBy(() -> Reservation.hold(1L, 1L, 2L, 1, NOW, DEADLINE, NOW))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () -> Reservation.hold(1L, 1L, 2L, 1, DEADLINE.plusSeconds(1), DEADLINE, NOW))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void zeroRecountRemainsAnUnacceptedMismatch() {
    HandoverLog recount =
        HandoverLog.recordRecount(1L, 2, 0, null, HandoverStatus.MISMATCH, List.of(), NOW);
    assertThat(recount.countedQty()).isZero();
    assertThat(recount.acceptedQty()).isNull();
    assertThatThrownBy(
            () -> HandoverLog.recordRecount(1L, 2, 0, 0, HandoverStatus.COMPLETED, List.of(), NOW))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void equalCountCanStillNeedReviewWhenSegmentationWasIncomplete() {
    HandoverLog recount =
        HandoverLog.recordRecount(
            1L, 1, 1, null, HandoverStatus.MISMATCH, List.of(new BoundingBox(0, 0, 1, 1)), NOW);
    assertThat(recount.status()).isEqualTo(HandoverStatus.MISMATCH);
    assertThat(recount.acceptedQty()).isNull();
  }

  @Test
  void draftAllowsZeroCountBeforeHumanConfirmation() {
    IntakeDraftLine line =
        IntakeDraftLine.identify(
            1L,
            DraftCategory.UNKNOWN,
            0,
            List.of(),
            null,
            null,
            null,
            Condition.FAIR,
            BigDecimal.ZERO,
            true);
    assertThat(line.count()).isZero();
    assertThat(line.boxes()).isEmpty();
    assertThat(line.label()).isEqualTo("Unclassified");
  }

  @Test
  void draftRejectsCountThatDoesNotMatchItsBoxes() {
    assertThatThrownBy(
            () ->
                IntakeDraftLine.identify(
                    1L,
                    DraftCategory.DISPLAY_RACK,
                    2,
                    List.of(new BoundingBox(0, 0, 1, 1)),
                    90,
                    40,
                    180,
                    Condition.GOOD,
                    BigDecimal.ONE,
                    true))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void draftRejectsPartiallySpecifiedDimensions() {
    assertThatThrownBy(
            () ->
                IntakeDraftLine.identify(
                    1L,
                    DraftCategory.UNKNOWN,
                    0,
                    List.of(),
                    90,
                    null,
                    null,
                    Condition.FAIR,
                    BigDecimal.ZERO,
                    true))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () ->
                IntakeDraftLine.identify(
                    1L,
                    DraftCategory.UNKNOWN,
                    0,
                    List.of(),
                    null,
                    40,
                    180,
                    Condition.FAIR,
                    BigDecimal.ZERO,
                    true))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void nullCreationInputsProduceTypedInvalidInputErrors() {
    assertInvalidInput(() -> Organization.create(null));
    assertInvalidInput(() -> Event.schedule(null, "Event", "Seoul", DEADLINE));
    assertInvalidInput(() -> Event.schedule(1L, null, "Seoul", DEADLINE));
    assertInvalidInput(() -> Event.schedule(1L, "Event", null, DEADLINE));
    assertInvalidInput(() -> Event.schedule(1L, "Event", "Seoul", null));
    assertInvalidInput(() -> IdempotencyRecord.begin(null, "fingerprint", NOW));
    assertInvalidInput(() -> IdempotencyRecord.begin(UUID.randomUUID(), null, NOW));
    assertInvalidInput(() -> IdempotencyRecord.begin(UUID.randomUUID(), "fingerprint", null));
  }

  private static void assertInvalidInput(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
  }

  @Test
  void boundingBoxesRejectNonFiniteOrUnnormalizedCoordinates() {
    assertThatThrownBy(() -> new BoundingBox(Double.NaN, 0, 1, 1))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> new BoundingBox(0, 0, 1.01, 1)).isInstanceOf(BusinessException.class);
  }
}
