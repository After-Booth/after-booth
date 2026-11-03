package com.afterbooth.allocation.domain;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "reservation")
public class Reservation {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long requestId;

  @Column(nullable = false)
  private Long itemId;

  @Column(nullable = false)
  private Long organizationId;

  @Column(nullable = false)
  private int quantity;

  @Column private Integer handedOverQty;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ReservationStatus status;

  @Column(nullable = false)
  private Instant holdExpiresAt;

  @Column(nullable = false)
  private Instant pickupDeadline;

  @Column(nullable = false)
  private Instant createdAt;

  @Column private Instant retentionUntil;

  public boolean retentionEnded(Instant now) {
    return retentionUntil != null && !now.isBefore(retentionUntil);
  }

  protected Reservation() {}

  private Reservation(
      Long requestId,
      Long itemId,
      Long organizationId,
      int quantity,
      Instant holdExpiresAt,
      Instant pickupDeadline,
      Instant createdAt) {
    ReservationStatus status = ReservationStatus.HELD;
    Integer handedOverQty = null;
    Objects.requireNonNull(requestId);
    Objects.requireNonNull(itemId);
    Objects.requireNonNull(organizationId);
    Objects.requireNonNull(holdExpiresAt);
    Objects.requireNonNull(pickupDeadline);
    Objects.requireNonNull(createdAt);
    if (quantity <= 0
        || !createdAt.isBefore(holdExpiresAt)
        || holdExpiresAt.isAfter(pickupDeadline)) invalid();
    this.requestId = requestId;
    this.itemId = itemId;
    this.organizationId = organizationId;
    this.quantity = quantity;
    this.handedOverQty = handedOverQty;
    this.status = status;
    this.holdExpiresAt = holdExpiresAt;
    this.pickupDeadline = pickupDeadline;
    this.createdAt = createdAt;
  }

  public static Reservation hold(
      Long requestId,
      Long itemId,
      Long organizationId,
      int quantity,
      Instant holdExpiresAt,
      Instant pickupDeadline,
      Instant createdAt) {
    return new Reservation(
        requestId, itemId, organizationId, quantity, holdExpiresAt, pickupDeadline, createdAt);
  }

  public static Reservation hold(
      Long requestId,
      Long itemId,
      Long organizationId,
      int quantity,
      Duration ttl,
      Instant pickupDeadline,
      Instant createdAt) {
    if (ttl.isZero() || ttl.isNegative())
      throw new IllegalArgumentException("Hold TTL must be positive");
    Instant expires = createdAt.plus(ttl);
    if (expires.isAfter(pickupDeadline)) expires = pickupDeadline;
    return hold(requestId, itemId, organizationId, quantity, expires, pickupDeadline, createdAt);
  }

  public ConfirmationDecision confirmationAt(Instant now) {
    if (status == ReservationStatus.EXPIRED)
      return new ConfirmationDecision(null, null, ErrorCode.HOLD_EXPIRED);
    require(status == ReservationStatus.HELD);
    if (!now.isBefore(holdExpiresAt))
      return new ConfirmationDecision(
          ReservationStatus.EXPIRED, TransitionActor.SCHEDULER, ErrorCode.HOLD_EXPIRED);
    return new ConfirmationDecision(ReservationStatus.CONFIRMED, TransitionActor.USER, null);
  }

  public ErrorCode confirmationConflict() {
    return status == ReservationStatus.EXPIRED
        ? ErrorCode.HOLD_EXPIRED
        : ErrorCode.INVALID_TRANSITION;
  }

  public record ConfirmationDecision(
      ReservationStatus next, TransitionActor actor, ErrorCode error) {}

  public ReservationStatus cancel() {
    require(status == ReservationStatus.HELD || status == ReservationStatus.CONFIRMED);
    return ReservationStatus.RELEASED;
  }

  public ReservationStatus dueTransition(Instant now) {
    if (status == ReservationStatus.HELD && !now.isBefore(holdExpiresAt))
      return ReservationStatus.EXPIRED;
    if (status == ReservationStatus.CONFIRMED && !now.isBefore(pickupDeadline))
      return ReservationStatus.RELEASED;
    return null;
  }

  public void requirePhotoHandover(Instant now) {
    require(status == ReservationStatus.CONFIRMED);
    if (!now.isBefore(pickupDeadline)) throw new BusinessException(ErrorCode.PICKUP_CLOSED);
  }

  public int requireHandover(int acceptedQuantity, TransitionActor actor, Instant now) {
    require(status == ReservationStatus.CONFIRMED);
    require(actor == TransitionActor.USER || actor == TransitionActor.HANDOVER);
    if (acceptedQuantity <= 0 || acceptedQuantity > quantity) invalid();
    if (actor == TransitionActor.HANDOVER && !now.isBefore(pickupDeadline))
      throw new BusinessException(ErrorCode.PICKUP_CLOSED);
    return quantity - acceptedQuantity;
  }

  private static void require(boolean valid) {
    if (!valid) throw new BusinessException(ErrorCode.INVALID_TRANSITION);
  }

  private static void invalid() {
    throw new BusinessException(ErrorCode.INVALID_INPUT);
  }

  public Long id() {
    return id;
  }

  public Long requestId() {
    return requestId;
  }

  public Long itemId() {
    return itemId;
  }

  public Long organizationId() {
    return organizationId;
  }

  public int quantity() {
    return quantity;
  }

  public Integer handedOverQty() {
    return handedOverQty;
  }

  public ReservationStatus status() {
    return status;
  }

  public Instant holdExpiresAt() {
    return holdExpiresAt;
  }

  public Instant pickupDeadline() {
    return pickupDeadline;
  }

  public Instant createdAt() {
    return createdAt;
  }
}
