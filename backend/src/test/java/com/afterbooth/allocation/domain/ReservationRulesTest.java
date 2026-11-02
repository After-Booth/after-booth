package com.afterbooth.allocation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.afterbooth.global.error.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReservationRulesTest {
  private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

  private Reservation hold() {
    return Reservation.hold(1L, 1L, 2L, 1, NOW.plusSeconds(900), NOW.plusSeconds(3600), NOW);
  }

  @Test
  void expiryStartsExactlyAtDeadlineAndHeldCanBeCancelled() {
    assertThat(hold().dueTransition(NOW.plusSeconds(899))).isNull();
    assertThat(hold().dueTransition(NOW.plusSeconds(900))).isEqualTo(ReservationStatus.EXPIRED);
    assertThat(hold().cancel()).isEqualTo(ReservationStatus.RELEASED);
  }

  @Test
  void holdExpiryUsesTheEarlierOfTtlAndPickupDeadline() {
    var ttl = java.time.Duration.ofMinutes(15);
    assertThat(Reservation.hold(1L, 1L, 2L, 1, ttl, NOW.plusSeconds(60), NOW).holdExpiresAt())
        .isEqualTo(NOW.plusSeconds(60));
    assertThat(Reservation.hold(1L, 1L, 2L, 1, ttl, NOW.plusSeconds(3600), NOW).holdExpiresAt())
        .isEqualTo(NOW.plusSeconds(900));
  }

  @Test
  void confirmationDecisionConfirmsStrictlyBeforeExpiryAndExpiresAtTheBoundary() {
    var before = hold().confirmationAt(NOW.plusSeconds(899));
    assertThat(before.next()).isEqualTo(ReservationStatus.CONFIRMED);
    assertThat(before.actor()).isEqualTo(TransitionActor.USER);
    assertThat(before.error()).isNull();
    var at = hold().confirmationAt(NOW.plusSeconds(900));
    assertThat(at.next()).isEqualTo(ReservationStatus.EXPIRED);
    assertThat(at.actor()).isEqualTo(TransitionActor.SCHEDULER);
    assertThat(at.error()).isEqualTo(ErrorCode.HOLD_EXPIRED);
  }
}
