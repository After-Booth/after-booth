package com.afterbooth.inventory.domain;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "event")
public class Event {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long organizationId;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String venue;

  @Column(nullable = false)
  private Instant teardownDeadline;

  protected Event() {}

  private Event(Long organizationId, String name, String venue, Instant teardownDeadline) {
    if (organizationId == null
        || name == null
        || name.isBlank()
        || venue == null
        || venue.isBlank()
        || teardownDeadline == null) invalid();
    this.organizationId = organizationId;
    this.name = name;
    this.venue = venue;
    this.teardownDeadline = teardownDeadline;
  }

  public static Event schedule(
      Long organizationId, String name, String venue, Instant teardownDeadline) {
    return new Event(organizationId, name, venue, teardownDeadline);
  }

  public void requireFutureDeadline(Instant now) {
    if (!teardownDeadline.isAfter(now)) invalid();
  }

  private static void invalid() {
    throw new BusinessException(ErrorCode.INVALID_INPUT);
  }

  public Instant intakePickupDeadline(Instant requested) {
    if (requested != null && requested.isAfter(teardownDeadline)) invalid();
    return requested == null ? teardownDeadline : requested;
  }

  public Long id() {
    return id;
  }

  public Long organizationId() {
    return organizationId;
  }

  public String name() {
    return name;
  }

  public String venue() {
    return venue;
  }

  public Instant teardownDeadline() {
    return teardownDeadline;
  }
}
