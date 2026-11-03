package com.afterbooth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class AuthSchemaUpgradeTest {
  @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17-alpine");

  @Test
  void upgradePreservesArbitraryLegacyRowsAndAddsOnlyNullableOwnershipAndSafeDefaults() {
    Flyway core =
        Flyway.configure()
            .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
            .target(MigrationVersion.fromVersion("202610311200"))
            .load();
    assertThat(core.migrate().migrationsExecuted).isEqualTo(1);
    JdbcTemplate jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
    Long organization =
        jdbc.queryForObject(
            "INSERT INTO organization(name) VALUES ('Synthetic legacy organization') RETURNING id",
            Long.class);
    Long event =
        jdbc.queryForObject(
            "INSERT INTO event(organization_id,name,venue,teardown_deadline) VALUES (?,'Synthetic legacy event','Synthetic venue',now()+interval '2 days') RETURNING id",
            Long.class,
            organization);
    Long item =
        jdbc.queryForObject(
            "INSERT INTO item(event_id,organization_id,category,label,width_cm,depth_cm,height_cm,condition,total_qty,available_qty,pickup_deadline) VALUES (?,?,'DISPLAY_RACK','Synthetic label',10,20,30,'GOOD',4,4,now()+interval '2 days') RETURNING id",
            Long.class,
            event,
            organization);
    Long request =
        jdbc.queryForObject(
            "INSERT INTO pickup_request(organization_id,category,quantity,vehicle_type,created_at) VALUES (?,'DISPLAY_RACK',1,'VAN',now()) RETURNING id",
            Long.class,
            organization);
    Long reservation =
        jdbc.queryForObject(
            "INSERT INTO reservation(request_id,item_id,organization_id,quantity,status,hold_expires_at,pickup_deadline,created_at) VALUES (?,?,?,1,'RELEASED',now()+interval '1 minute',now()+interval '2 days',now()) RETURNING id",
            Long.class,
            request,
            item,
            organization);
    UUID idempotency = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO idempotency_record(idempotency_key,fingerprint,state,response_status,response_body,created_at) VALUES (?,'synthetic-fingerprint','DONE',200,'{\"synthetic\":true}',now())",
        idempotency);
    String orgQuery = "SELECT id,name FROM organization WHERE id=?";
    String itemQuery =
        "SELECT id,event_id,organization_id,category,label,width_cm,depth_cm,height_cm,condition,total_qty,available_qty,pickup_deadline,vehicle_requirement,conditions_transcript FROM item WHERE id=?";
    String reservationQuery =
        "SELECT id,request_id,item_id,organization_id,quantity,handed_over_qty,status,hold_expires_at,pickup_deadline,created_at FROM reservation WHERE id=?";
    String idempotencyQuery =
        "SELECT idempotency_key,fingerprint,state,response_status,response_body::text,created_at FROM idempotency_record WHERE idempotency_key=?";
    var beforeOrg = jdbc.queryForMap(orgQuery, organization);
    var beforeItem = jdbc.queryForMap(itemQuery, item);
    var beforeReservation = jdbc.queryForMap(reservationQuery, reservation);
    var beforeIdempotency = jdbc.queryForMap(idempotencyQuery, idempotency);
    assertThat(
            Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .load()
                .migrate()
                .migrationsExecuted)
        .isEqualTo(1);
    assertThat(jdbc.queryForMap(orgQuery, organization).equals(beforeOrg)).isTrue();
    assertThat(jdbc.queryForMap(itemQuery, item).equals(beforeItem)).isTrue();
    assertThat(jdbc.queryForMap(reservationQuery, reservation).equals(beforeReservation)).isTrue();
    assertThat(jdbc.queryForMap(idempotencyQuery, idempotency).equals(beforeIdempotency)).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT primary_member_id IS NULL FROM organization WHERE id=?",
                Boolean.class,
                organization))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT retention_until IS NULL FROM reservation WHERE id=?",
                Boolean.class,
                reservation))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT archived_handed_over_qty FROM item WHERE id=?", Integer.class, item))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT actor_member_id IS NULL AND cardinality(related_organization_ids)=0 FROM idempotency_record WHERE idempotency_key=?",
                Boolean.class,
                idempotency))
        .isTrue();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE organization SET primary_member_id=9223372036854775807 WHERE id=?",
                    organization))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE idempotency_record SET actor_member_id=9223372036854775807 WHERE idempotency_key=?",
                    idempotency))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> jdbc.update("UPDATE item SET archived_handed_over_qty=-1 WHERE id=?", item))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM member", Long.class)).isZero();
  }
}
