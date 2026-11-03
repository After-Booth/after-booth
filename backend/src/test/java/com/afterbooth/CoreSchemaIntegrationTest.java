package com.afterbooth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.afterbooth.allocation.domain.Reservation;
import com.afterbooth.allocation.domain.ReservationStatus;
import com.afterbooth.allocation.domain.ReservationTransition;
import com.afterbooth.allocation.domain.TransitionActor;
import com.afterbooth.global.domain.BoundingBox;
import com.afterbooth.global.idempotency.IdempotencyRecord;
import com.afterbooth.handover.domain.HandoverLog;
import com.afterbooth.handover.domain.HandoverStatus;
import com.afterbooth.intake.domain.DraftCategory;
import com.afterbooth.intake.domain.IntakeDraft;
import com.afterbooth.intake.domain.IntakeDraftLine;
import com.afterbooth.inventory.domain.Condition;
import com.afterbooth.inventory.domain.Dimensions;
import com.afterbooth.inventory.domain.Event;
import com.afterbooth.inventory.domain.Item;
import com.afterbooth.inventory.domain.ItemCategory;
import com.afterbooth.inventory.domain.Organization;
import com.afterbooth.inventory.domain.VehicleType;
import com.afterbooth.request.domain.PickupRequest;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class CoreSchemaIntegrationTest {
  @Autowired EntityManager entityManager;
  @Autowired JdbcTemplate jdbc;

  @Test
  void migrationSeedsOrganizationsAndAllThreeDemoCategories() {
    assertThat(jdbc.queryForObject("SELECT count(*) FROM organization", Long.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM event", Long.class)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT total_qty FROM item WHERE category = 'DISPLAY_RACK'", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT total_qty FROM item WHERE category = 'STORAGE_BIN'", Integer.class))
        .isEqualTo(12);
    assertThat(
            jdbc.queryForObject(
                "SELECT total_qty FROM item WHERE category = 'FOLDING_TABLE'", Integer.class))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT vehicle_requirement FROM item WHERE category = 'FOLDING_TABLE'",
                String.class))
        .isEqualTo("SEDAN");
    assertThat(
            jdbc.queryForObject(
                "SELECT bool_and(pickup_deadline > now()) FROM item", Boolean.class))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT bool_and(pickup_deadline = e.teardown_deadline) FROM item i JOIN event e ON i.event_id = e.id",
                Boolean.class))
        .isTrue();
    assertThat(jdbc.queryForList("SELECT category FROM item", String.class))
        .containsExactlyInAnyOrder("DISPLAY_RACK", "STORAGE_BIN", "FOLDING_TABLE");
    assertThat(
            jdbc.queryForObject(
                "SELECT bool_and(available_qty = total_qty) FROM item", Boolean.class))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Long.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success", String.class))
        .containsExactlyInAnyOrder("202610311200", "202611031200");
  }

  @Test
  void everyAggregateAndJsonColumnRoundTripsAgainstValidatedSchema() {
    Instant now = Instant.parse("2026-11-01T00:00:00Z");
    Organization giver = Organization.create("Integration giver");
    entityManager.persist(giver);
    Event event = Event.schedule(giver.id(), "Integration event", "Seoul", now.plusSeconds(3600));
    entityManager.persist(event);
    IntakeDraft draft = IntakeDraft.start(event.id(), now);
    entityManager.persist(draft);
    List<BoundingBox> boxes = List.of(new BoundingBox(0.1, 0.2, 0.3, 0.4));
    IntakeDraftLine line =
        IntakeDraftLine.identify(
            draft.id(),
            DraftCategory.DISPLAY_RACK,
            1,
            boxes,
            90,
            40,
            180,
            Condition.GOOD,
            new BigDecimal("0.90"),
            false);
    entityManager.persist(line);
    Item item =
        Item.register(
            event.id(),
            giver.id(),
            draft.id(),
            ItemCategory.DISPLAY_RACK,
            new Dimensions(90, 40, 180),
            Condition.GOOD,
            1,
            event.teardownDeadline(),
            VehicleType.VAN,
            null);
    entityManager.persist(item);
    PickupRequest request =
        PickupRequest.create(
            giver.id(), ItemCategory.DISPLAY_RACK, 1, VehicleType.VAN, null, null, null, now);
    entityManager.persist(request);
    Reservation reservation =
        Reservation.hold(
            request.id(),
            item.id(),
            giver.id(),
            1,
            now.plusSeconds(900),
            event.teardownDeadline(),
            now);
    entityManager.persist(reservation);
    ReservationTransition transition =
        ReservationTransition.initialHold(reservation.id(), TransitionActor.USER, now);
    entityManager.persist(transition);
    HandoverLog log =
        HandoverLog.recordRecount(
            reservation.id(), 1, 0, null, HandoverStatus.MISMATCH, List.of(), now);
    entityManager.persist(log);
    HandoverLog segmentedMismatch =
        HandoverLog.recordRecount(
            reservation.id(), 1, 1, null, HandoverStatus.MISMATCH, boxes, now);
    entityManager.persist(segmentedMismatch);
    UUID key = UUID.randomUUID();
    entityManager.persist(IdempotencyRecord.begin(key, "POST /test sha256", now));
    entityManager.flush();
    // DONE records are produced by #7; verify the nullable JSON mapping without adding that use
    // case here.
    jdbc.update(
        "UPDATE idempotency_record SET state = 'DONE', response_status = 200, response_body = CAST(? AS jsonb) WHERE idempotency_key = ?",
        "{\"quantity\":1,\"nested\":{\"status\":\"HELD\"}}",
        key);
    jdbc.update("UPDATE handover_log SET accepted_qty = 1 WHERE id = ?", log.id());
    entityManager.clear();

    assertThat(entityManager.find(IntakeDraftLine.class, line.id()).boxes()).isEqualTo(boxes);
    assertThat(entityManager.find(Item.class, item.id()).dimensions())
        .isEqualTo(new Dimensions(90, 40, 180));
    assertThat(entityManager.find(Reservation.class, reservation.id()).status())
        .isEqualTo(ReservationStatus.HELD);
    assertThat(entityManager.find(ReservationTransition.class, transition.id()).fromStatus())
        .isNull();
    HandoverLog resolved = entityManager.find(HandoverLog.class, log.id());
    assertThat(resolved.boxes()).isEmpty();
    assertThat(resolved.status()).isEqualTo(HandoverStatus.MISMATCH);
    assertThat(resolved.acceptedQty()).isEqualTo(1);
    assertThat(entityManager.find(HandoverLog.class, segmentedMismatch.id()).boxes())
        .isEqualTo(boxes);
    assertThat(entityManager.find(IdempotencyRecord.class, key).responseBody())
        .isEqualTo(
            jdbc.queryForObject(
                "SELECT response_body::text FROM idempotency_record WHERE idempotency_key = ?",
                String.class,
                key))
        .startsWith("{")
        .contains("HELD");
  }

  @Test
  void idempotencyResponsePreservesTopLevelArrays() {
    UUID key = UUID.randomUUID();
    entityManager.persist(IdempotencyRecord.begin(key, "POST /intake/confirm hash", Instant.EPOCH));
    entityManager.flush();
    jdbc.update(
        "UPDATE idempotency_record SET state = 'DONE', response_status = 200, response_body = CAST(? AS jsonb) WHERE idempotency_key = ?",
        "[{\"id\":1,\"category\":\"DISPLAY_RACK\"},{\"id\":2}]",
        key);
    entityManager.clear();
    assertThat(entityManager.find(IdempotencyRecord.class, key).responseBody())
        .isEqualTo(
            jdbc.queryForObject(
                "SELECT response_body::text FROM idempotency_record WHERE idempotency_key = ?",
                String.class,
                key))
        .startsWith("[")
        .contains("DISPLAY_RACK");
  }

  @Test
  void databaseRejectsCompletedHandoverWithoutAcceptedQuantity() {
    Instant now = Instant.parse("2026-11-01T00:00:00Z");
    Long itemId = jdbc.queryForObject("SELECT id FROM item LIMIT 1", Long.class);
    PickupRequest request =
        PickupRequest.create(
            2L, ItemCategory.DISPLAY_RACK, 1, VehicleType.VAN, null, null, null, now);
    entityManager.persist(request);
    Reservation reservation =
        Reservation.hold(
            request.id(), itemId, 2L, 1, now.plusSeconds(900), now.plusSeconds(3600), now);
    entityManager.persist(reservation);
    entityManager.flush();
    String constraint =
        jdbc.queryForObject(
            """
            SELECT conname FROM pg_constraint
            WHERE conrelid = 'handover_log'::regclass AND contype = 'c'
              AND pg_get_constraintdef(oid) LIKE '%MISMATCH%accepted_qty IS NOT NULL%'
            """,
            String.class);
    assertThat(constraint).isNotBlank();

    jdbc.execute(
        (ConnectionCallback<Void>)
            connection -> {
              var savepoint = connection.setSavepoint();
              try {
                assertThatThrownBy(
                        () ->
                            jdbc.update(
                                """
                                INSERT INTO handover_log
                                  (reservation_id, expected_qty, counted_qty, accepted_qty,
                                   status, boxes, created_at)
                                VALUES (?, 1, 1, NULL, 'COMPLETED', '[]'::jsonb, ?)
                                """,
                                reservation.id(),
                                java.sql.Timestamp.from(now)))
                    .isInstanceOfSatisfying(
                        DataIntegrityViolationException.class,
                        failure ->
                            assertThat(failure.getMostSpecificCause())
                                .isInstanceOfSatisfying(
                                    SQLException.class,
                                    sql -> {
                                      assertThat(sql.getSQLState()).isEqualTo("23514");
                                      assertThat(sql.getMessage()).contains(constraint);
                                    }));
              } finally {
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
              }
              return null;
            });
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM handover_log WHERE reservation_id = ?",
                Long.class,
                reservation.id()))
        .isZero();
  }

  @Test
  void databaseRejectsPartialDraftDimensions() {
    Long eventId = jdbc.queryForObject("SELECT id FROM event LIMIT 1", Long.class);
    IntakeDraft draft = IntakeDraft.start(eventId, Instant.EPOCH);
    entityManager.persist(draft);
    entityManager.persist(
        IntakeDraftLine.identify(
            draft.id(),
            DraftCategory.UNKNOWN,
            0,
            List.of(),
            null,
            null,
            null,
            Condition.FAIR,
            BigDecimal.ZERO,
            true));
    entityManager.flush();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE intake_draft_line SET width_cm = 90 WHERE draft_id = ?", draft.id()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void databaseRejectsInventoryOutsideItsTotalQuantity() {
    assertThatThrownBy(() -> jdbc.update("UPDATE item SET available_qty = total_qty + 1"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void databaseRejectsNegativeAvailability() {
    assertThatThrownBy(() -> jdbc.update("UPDATE item SET available_qty = -1"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
