package com.afterbooth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.afterbooth.inventory.config.VehicleCargoProperties;
import com.afterbooth.inventory.domain.Item;
import com.afterbooth.inventory.domain.VehicleType;
import com.afterbooth.inventory.repository.ItemRepository;
import com.afterbooth.inventory.service.VehicleFitPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {"spring.docker.compose.enabled=false", "afterbooth.reservation-sweep-ms=3600000"})
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Import({
  com.afterbooth.AuthenticatedMockMvcConfiguration.class,
  TestcontainersConfiguration.class,
  DefaultVehicleHoldIntegrationTest.TimeConfiguration.class
})
class DefaultVehicleHoldIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ItemRepository items;
  @Autowired VehicleFitPolicy vehicleFit;
  @Autowired VehicleCargoProperties cargo;
  @Autowired tools.jackson.databind.json.JsonMapper json;

  @Autowired Clock clock;

  @org.junit.jupiter.api.BeforeEach
  void authorize() {
    DomainAuthTestSupport.seedOwner(jdbc, clock, 1);
    DomainAuthTestSupport.seedOwner(jdbc, clock, 2);
  }

  Long request(String vehicle) {
    return jdbc.queryForObject(
        "insert into pickup_request(organization_id,category,quantity,vehicle_type,created_at) values(2,'DISPLAY_RACK',1,?,?) returning id",
        Long.class,
        vehicle,
        java.sql.Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")));
  }

  Long seed(String category) {
    return jdbc.queryForObject(
        "select id from item where category=? order by id limit 1", Long.class, category);
  }

  String body(Long request, Long item) {
    return "{\"requestId\":" + request + ",\"itemId\":" + item + ",\"quantity\":1}";
  }

  @Test
  void defaultCargoPolicyHoldsSeedAndReplaysWithoutAnotherDecrement() throws Exception {
    Long item = seed("STORAGE_BIN");
    int before = stock(item);
    String body = body(request("SEDAN"), item);
    String key = UUID.randomUUID().toString();
    String first =
        mvc.perform(
                post("/api/reservations")
                    .contentType("application/json")
                    .header("Idempotency-Key", key)
                    .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("HELD"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String replay =
        mvc.perform(
                post("/api/reservations")
                    .contentType("application/json")
                    .header("Idempotency-Key", key)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(replay)).isEqualTo(json.readTree(first));
    assertThat(stock(item)).isEqualTo(before - 1);
  }

  @Test
  void sedanRejectsSeedRackHeightAndHoldsBoundaryTableWhileVanHoldsRack() throws Exception {
    Long rack = seed("DISPLAY_RACK");
    Long sedan = request("SEDAN");
    Item rackItem = items.findById(rack).orElseThrow();
    assertThat(vehicleFit.checkFit(rackItem, VehicleType.SEDAN).limitingDimension())
        .isEqualTo("height");
    Item table = items.findById(seed("FOLDING_TABLE")).orElseThrow();
    assertThat(table.dimensions().widthCm()).isEqualTo(120);
    assertThat(table.dimensions().widthCm())
        .isEqualTo(cargo.dimensionsFor(VehicleType.SEDAN).widthCm());
    int before = stock(rack);
    mvc.perform(
            post("/api/reservations")
                .contentType("application/json")
                .header("Idempotency-Key", UUID.randomUUID())
                .content(body(sedan, rack)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VEHICLE_TOO_SMALL"));
    assertThat(stock(rack)).isEqualTo(before);
    mvc.perform(
            post("/api/reservations")
                .contentType("application/json")
                .header("Idempotency-Key", UUID.randomUUID())
                .content(body(sedan, table.id())))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/api/reservations")
                .contentType("application/json")
                .header("Idempotency-Key", UUID.randomUUID())
                .content(body(request("VAN"), rack)))
        .andExpect(status().isCreated());
    assertThat(stock(rack)).isEqualTo(before - 1);
  }

  int stock(Long item) {
    return jdbc.queryForObject("select available_qty from item where id=?", Integer.class, item);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TimeConfiguration {
    @Bean
    @Primary
    Clock defaultVehicleTestClock() {
      return Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
    }
  }
}
