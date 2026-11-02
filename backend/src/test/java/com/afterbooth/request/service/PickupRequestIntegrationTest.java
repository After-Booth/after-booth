package com.afterbooth.request.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.idempotency.IdempotencyService;
import com.afterbooth.inventory.dto.OrganizationResponse;
import com.afterbooth.request.dto.PickupRequestResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureMockMvc
@Import(PickupRequestIntegrationTest.Database.class)
class PickupRequestIntegrationTest {
  @Autowired PickupRequestService service;
  @Autowired IdempotencyService idempotency;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired MockMvc mvc;
  private static final String BODY =
      "{\"organizationId\":2,\"category\":\"DISPLAY_RACK\",\"quantity\":2,\"vehicleType\":\"SEDAN\",\"purpose\":\"주말"
          + " 행사\",\"transcript\":\"진열대 두 개\"}";

  @Test
  void createsPickupRequestWithAnOrganizationReturnedByTheCatalog() throws Exception {
    var catalog =
        mvc.perform(get("/api/organizations")).andExpect(status().isOk()).andReturn().getResponse();
    var organizations =
        json.readValue(
            catalog.getContentAsString(StandardCharsets.UTF_8), OrganizationResponse[].class);
    assertThat(organizations)
        .isNotEmpty()
        .containsExactlyElementsOf(
            jdbc.query(
                "select id, name from organization order by id",
                (row, index) ->
                    new OrganizationResponse(row.getLong("id"), row.getString("name"))));
    Long organizationId = organizations[0].id();
    var response =
        mvc.perform(
                post("/api/pickup-requests")
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType("application/json")
                    .content(
                        BODY.replace(
                            "\"organizationId\":2", "\"organizationId\":" + organizationId)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();
    var created =
        json.readValue(
            response.getContentAsString(StandardCharsets.UTF_8), PickupRequestResponse.class);
    assertThat(created.organizationId()).isEqualTo(organizationId);
    assertThat(service.get(created.id())).isEqualTo(created);
  }

  @Test
  void savesReadsAndReplaysTheSameRequest() {
    UUID key = UUID.randomUUID();
    long before = jdbc.queryForObject("select count(*) from pickup_request", Long.class);
    var response = create(key, BODY);
    assertThat(response.getStatusCode().value()).isEqualTo(201);
    var created = json.readValue(response.getBody(), PickupRequestResponse.class);
    assertThat(created.id()).isPositive();
    assertThat(service.get(created.id())).isEqualTo(created);
    var replay = create(key, BODY);
    assertThat(replay.getStatusCode().value()).isEqualTo(201);
    assertThat(json.readValue(replay.getBody(), PickupRequestResponse.class)).isEqualTo(created);
    assertThat(jdbc.queryForObject("select count(*) from pickup_request", Long.class))
        .isEqualTo(before + 1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from idempotency_record where idempotency_key = ? and state ="
                    + " 'DONE'",
                Integer.class,
                key))
        .isEqualTo(1);
    assertThatThrownBy(() -> create(key, BODY.replace("\"quantity\":2", "\"quantity\":3")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.errorCode().name()).isEqualTo("IDEMPOTENCY_CONFLICT"));
  }

  @Test
  void inFlightKeyConflictsWithoutWaiting() throws Exception {
    UUID key = UUID.randomUUID();
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              jdbc.queryForObject(
                  "select pg_advisory_xact_lock(?)",
                  Object.class,
                  key.getMostSignificantBits() ^ key.getLeastSignificantBits());
              var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
              try {
                var call =
                    executor.submit(
                        () -> {
                          assertThatThrownBy(() -> create(key, BODY))
                              .isInstanceOfSatisfying(
                                  BusinessException.class,
                                  error ->
                                      assertThat(error.errorCode().name())
                                          .isEqualTo("IDEMPOTENCY_CONFLICT"));
                        });
                try {
                  call.get(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                  throw new AssertionError(e);
                }
              } finally {
                executor.shutdownNow();
              }
            });
  }

  private ResponseEntity<String> create(UUID key, String body) {
    return idempotency.execute(
        key,
        "POST",
        "/api/pickup-requests",
        body.getBytes(StandardCharsets.UTF_8),
        201,
        () -> service.create(body));
  }

  @Test
  void committedOrphanRequestRecoversAndReplays() {
    UUID key = UUID.randomUUID();
    jdbc.update(
        "insert into idempotency_record(idempotency_key,fingerprint,state,created_at) values"
            + " (?,?,'IN_PROGRESS',current_timestamp)",
        key,
        IdempotencyService.fingerprint(
            "POST", "/api/pickup-requests", BODY.getBytes(StandardCharsets.UTF_8)));
    var first = create(key, BODY);
    assertThat(first.getStatusCode().value()).isEqualTo(201);
    var replay = create(key, BODY);
    assertThat(json.readTree(replay.getBody())).isEqualTo(json.readTree(first.getBody()));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Database {
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
      return new PostgreSQLContainer("postgres:17-alpine");
    }
  }
}
