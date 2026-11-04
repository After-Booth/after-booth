package com.afterbooth.inventory.domain;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "item")
public class Item {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long eventId;

  @Column(nullable = false)
  private Long organizationId;

  @Column private Long intakeDraftId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ItemCategory category;

  @Column(nullable = false)
  private String label;

  @Embedded private Dimensions dimensions;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Condition condition;

  @Column(nullable = false)
  private int totalQty;

  @Column(nullable = false)
  private int availableQty;

  @Column(nullable = false)
  private Instant pickupDeadline;

  @Enumerated(EnumType.STRING)
  @Column
  private VehicleType vehicleRequirement;

  @Column private String conditionsTranscript;

  protected Item() {}

  private Item(
      Long eventId,
      Long organizationId,
      Long intakeDraftId,
      ItemCategory category,
      Dimensions dimensions,
      Condition condition,
      int totalQty,
      Instant pickupDeadline,
      VehicleType vehicleRequirement,
      String conditionsTranscript) {
    int availableQty = totalQty;
    Objects.requireNonNull(eventId);
    Objects.requireNonNull(organizationId);
    Objects.requireNonNull(category);

    Objects.requireNonNull(dimensions);
    Objects.requireNonNull(condition);
    Objects.requireNonNull(pickupDeadline);
    if (totalQty <= 0) invalid();
    this.eventId = eventId;
    this.organizationId = organizationId;
    this.intakeDraftId = intakeDraftId;
    this.category = category;
    this.label = category.label();
    this.dimensions = dimensions;
    this.condition = condition;
    this.totalQty = totalQty;
    this.availableQty = availableQty;
    this.pickupDeadline = pickupDeadline;
    this.vehicleRequirement = vehicleRequirement;
    this.conditionsTranscript = conditionsTranscript;
  }

  public static Item register(
      Long eventId,
      Long organizationId,
      Long intakeDraftId,
      ItemCategory category,
      Dimensions dimensions,
      Condition condition,
      int totalQty,
      Instant pickupDeadline,
      VehicleType vehicleRequirement,
      String conditionsTranscript) {
    return new Item(
        eventId,
        organizationId,
        intakeDraftId,
        category,
        dimensions,
        condition,
        totalQty,
        pickupDeadline,
        vehicleRequirement,
        conditionsTranscript);
  }

  private static void invalid() {
    throw new BusinessException(ErrorCode.INVALID_INPUT);
  }

  public Long id() {
    return id;
  }

  public Long eventId() {
    return eventId;
  }

  public Long organizationId() {
    return organizationId;
  }

  public Long intakeDraftId() {
    return intakeDraftId;
  }

  public ItemCategory category() {
    return category;
  }

  public String label() {
    return category.label();
  }

  public Dimensions dimensions() {
    return dimensions;
  }

  public Condition condition() {
    return condition;
  }

  public int totalQty() {
    return totalQty;
  }

  public int availableQty() {
    return availableQty;
  }

  public Instant pickupDeadline() {
    return pickupDeadline;
  }

  public VehicleType vehicleRequirement() {
    return vehicleRequirement;
  }

  public String conditionsTranscript() {
    return conditionsTranscript;
  }

  public VehicleFitResult vehicleFit(VehicleType vehicle, Dimensions cargo) {
    return VehicleFitResult.evaluate(this, vehicle, cargo);
  }

  public boolean isPickupClosed(Instant now) {
    return !now.isBefore(pickupDeadline);
  }
}
