package com.afterbooth.intake.domain;

import com.afterbooth.global.domain.BoundingBox;
import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.inventory.domain.Condition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "intake_draft_line")
public class IntakeDraftLine {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long draftId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private DraftCategory category;

  @Column(nullable = false)
  private String label;

  @Column(nullable = false)
  private int count;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private List<BoundingBox> boxes;

  @Column private Integer widthCm;

  @Column private Integer depthCm;

  @Column private Integer heightCm;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Condition condition;

  @Column(nullable = false)
  private BigDecimal confidence;

  @Column(nullable = false)
  private boolean needsReview;

  protected IntakeDraftLine() {}

  private IntakeDraftLine(
      Long draftId,
      DraftCategory category,
      int count,
      List<BoundingBox> boxes,
      Integer widthCm,
      Integer depthCm,
      Integer heightCm,
      Condition condition,
      BigDecimal confidence,
      boolean needsReview) {
    Objects.requireNonNull(draftId);
    Objects.requireNonNull(category);
    Objects.requireNonNull(boxes);
    Objects.requireNonNull(condition);
    Objects.requireNonNull(confidence);
    if (count < 0
        || boxes.size() != count
        || confidence.compareTo(BigDecimal.ZERO) < 0
        || confidence.compareTo(BigDecimal.ONE) > 0
        || !((widthCm == null && depthCm == null && heightCm == null)
            || (widthCm != null && depthCm != null && heightCm != null))
        || (widthCm != null && widthCm <= 0)
        || (depthCm != null && depthCm <= 0)
        || (heightCm != null && heightCm <= 0)) invalid();
    this.draftId = draftId;
    this.category = category;
    this.label = category.label();
    this.count = count;
    this.boxes = List.copyOf(boxes);
    this.widthCm = widthCm;
    this.depthCm = depthCm;
    this.heightCm = heightCm;
    this.condition = condition;
    this.confidence = confidence;
    this.needsReview = needsReview;
  }

  public static IntakeDraftLine identify(
      Long draftId,
      DraftCategory category,
      int count,
      List<BoundingBox> boxes,
      Integer widthCm,
      Integer depthCm,
      Integer heightCm,
      Condition condition,
      BigDecimal confidence,
      boolean needsReview) {
    return new IntakeDraftLine(
        draftId,
        category,
        count,
        boxes,
        widthCm,
        depthCm,
        heightCm,
        condition,
        confidence,
        needsReview);
  }

  private static void invalid() {
    throw new BusinessException(ErrorCode.INVALID_INPUT);
  }

  public Long id() {
    return id;
  }

  public Long draftId() {
    return draftId;
  }

  public DraftCategory category() {
    return category;
  }

  public String label() {
    return category.label();
  }

  public int count() {
    return count;
  }

  public List<BoundingBox> boxes() {
    return boxes;
  }

  public Integer widthCm() {
    return widthCm;
  }

  public Integer depthCm() {
    return depthCm;
  }

  public Integer heightCm() {
    return heightCm;
  }

  public Condition condition() {
    return condition;
  }

  public BigDecimal confidence() {
    return confidence;
  }

  public boolean needsReview() {
    return needsReview;
  }
}
