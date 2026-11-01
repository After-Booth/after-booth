package com.afterbooth.handover.domain;

import com.afterbooth.global.domain.BoundingBox;
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
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "handover_log")
public class HandoverLog {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long reservationId;

  @Column(nullable = false)
  private int expectedQty;

  @Column(nullable = false)
  private int countedQty;

  @Column private Integer acceptedQty;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private HandoverStatus status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private List<BoundingBox> boxes;

  @Column(nullable = false)
  private Instant createdAt;

  protected HandoverLog() {}

  private HandoverLog(
      Long reservationId,
      int expectedQty,
      int countedQty,
      Integer acceptedQty,
      HandoverStatus status,
      List<BoundingBox> boxes,
      Instant createdAt) {
    Objects.requireNonNull(reservationId);
    Objects.requireNonNull(status);
    Objects.requireNonNull(boxes);
    Objects.requireNonNull(createdAt);
    if (expectedQty <= 0
        || countedQty < 0
        || boxes.size() != countedQty
        || (status == HandoverStatus.COMPLETED
            && (countedQty != expectedQty || acceptedQty == null || acceptedQty != countedQty))
        || (status == HandoverStatus.MISMATCH && acceptedQty != null)) invalid();
    this.reservationId = reservationId;
    this.expectedQty = expectedQty;
    this.countedQty = countedQty;
    this.acceptedQty = acceptedQty;
    this.status = status;
    this.boxes = List.copyOf(boxes);
    this.createdAt = createdAt;
  }

  public static HandoverLog recordRecount(
      Long reservationId,
      int expectedQty,
      int countedQty,
      Integer acceptedQty,
      HandoverStatus status,
      List<BoundingBox> boxes,
      Instant createdAt) {
    return new HandoverLog(
        reservationId, expectedQty, countedQty, acceptedQty, status, boxes, createdAt);
  }

  public static HandoverLog recordPhoto(
      Long reservationId,
      int expectedQty,
      int countedQty,
      boolean allSegmented,
      List<BoundingBox> boxes,
      Instant createdAt) {
    boolean matched = countedQty > 0 && countedQty == expectedQty && allSegmented;
    return recordRecount(
        reservationId,
        expectedQty,
        countedQty,
        matched ? countedQty : null,
        matched ? HandoverStatus.COMPLETED : HandoverStatus.MISMATCH,
        boxes,
        createdAt);
  }

  public int requireMatched(int reservedQuantity) {
    if (status != HandoverStatus.COMPLETED
        || expectedQty != reservedQuantity
        || countedQty != reservedQuantity
        || acceptedQty == null
        || acceptedQty != reservedQuantity)
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    return acceptedQty;
  }

  public void resolve(int acceptedQuantity, int reservedQuantity) {
    if (status != HandoverStatus.MISMATCH || acceptedQty != null || expectedQty != reservedQuantity)
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    if (acceptedQuantity <= 0 || acceptedQuantity > reservedQuantity) invalid();
    acceptedQty = acceptedQuantity;
  }

  private static void invalid() {
    throw new BusinessException(ErrorCode.INVALID_INPUT);
  }

  public Long id() {
    return id;
  }

  public Long reservationId() {
    return reservationId;
  }

  public int expectedQty() {
    return expectedQty;
  }

  public int countedQty() {
    return countedQty;
  }

  public Integer acceptedQty() {
    return acceptedQty;
  }

  public HandoverStatus status() {
    return status;
  }

  public List<BoundingBox> boxes() {
    return boxes;
  }

  public Instant createdAt() {
    return createdAt;
  }
}
