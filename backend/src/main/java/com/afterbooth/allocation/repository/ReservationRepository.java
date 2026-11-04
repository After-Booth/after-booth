package com.afterbooth.allocation.repository;

import com.afterbooth.allocation.domain.Reservation;
import com.afterbooth.allocation.domain.ReservationStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from Reservation r where r.id=:id")
  java.util.Optional<Reservation> lock(@Param("id") Long id);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update Reservation r set r.status=com.afterbooth.allocation.domain.ReservationStatus.HANDED_OVER,r.handedOverQty=:accepted where r.id=:id and r.status=com.afterbooth.allocation.domain.ReservationStatus.CONFIRMED")
  int handover(@Param("id") Long id, @Param("accepted") int accepted);

  @Query(
      "select coalesce(sum(r.quantity),0) from Reservation r where r.requestId=:id and r.status in (com.afterbooth.allocation.domain.ReservationStatus.HELD, com.afterbooth.allocation.domain.ReservationStatus.CONFIRMED)")
  long activeQuantity(@Param("id") Long id);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("update Reservation r set r.status=:next where r.id=:id and r.status=:expected")
  int transition(
      @Param("id") Long id,
      @Param("expected") ReservationStatus expected,
      @Param("next") ReservationStatus next);

  @Query(
      "select new com.afterbooth.allocation.repository.ReservationListing(r, i.category) "
          + "from Reservation r join Item i on i.id=r.itemId "
          + "where (:requestId is null or r.requestId=:requestId) "
          + "and (:eventId is null or i.eventId=:eventId) order by r.id")
  List<ReservationListing> listWithLabel(
      @Param("requestId") Long requestId, @Param("eventId") Long eventId);

  @Query(
      "select r.id from Reservation r where (r.status=com.afterbooth.allocation.domain.ReservationStatus.HELD and r.holdExpiresAt<=:now) or (r.status=com.afterbooth.allocation.domain.ReservationStatus.CONFIRMED and r.pickupDeadline<=:now)")
  List<Long> due(@Param("now") Instant now);
}
