package com.afterbooth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.afterbooth.global.error.ErrorCode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(
    properties = {"spring.docker.compose.enabled=false", "afterbooth.reservation-sweep-ms=3600000"})
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Import({
  com.afterbooth.AuthenticatedMockMvcConfiguration.class,
  TestcontainersConfiguration.class,
  ReservationIntegrationTest.TimeConfiguration.class
})
class HoldItemValidationTest {
  static final Instant NOW = ReservationIntegrationTest.NOW;
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ReservationIntegrationTest.MutableClock clock;

  @BeforeEach
  void reset() {
    clock.set(NOW);
    jdbc.update("delete from idempotency_record");
    jdbc.update("delete from reservation_transition");
    jdbc.update("delete from handover_log");
    jdbc.update("delete from reservation");
  }

  Long item(int quantity) {
    return jdbc.queryForObject(
        "insert into item(event_id,organization_id,category,label,width_cm,depth_cm,height_cm,condition,total_qty,available_qty,pickup_deadline) values(1,1,'STORAGE_BIN','storage-bin',20,20,20,'GOOD',?,?,?) returning id",
        Long.class,
        quantity,
        quantity,
        java.sql.Timestamp.from(NOW.plusSeconds(3600)));
  }

  Long request(int quantity) {
    return jdbc.queryForObject(
        "insert into pickup_request(organization_id,category,quantity,vehicle_type,created_at) values(2,'STORAGE_BIN',?,'VAN',?) returning id",
        Long.class,
        quantity,
        java.sql.Timestamp.from(NOW));
  }

  ResultActions holdRaw(UUID key, String body) throws Exception {
    return mvc.perform(
        post("/api/reservations")
            .contentType("application/json")
            .header("Idempotency-Key", key)
            .content(body));
  }

  record DomainState(
      int reservationCount, int transitionCount, int availableQty, int activeQuantity) {}

  DomainState state(Long request, Long item) {
    return new DomainState(
        jdbc.queryForObject("select count(*) from reservation", Integer.class),
        jdbc.queryForObject("select count(*) from reservation_transition", Integer.class),
        jdbc.queryForObject("select available_qty from item where id=?", Integer.class, item),
        jdbc.queryForObject(
            "select coalesce(sum(quantity),0) from reservation where request_id=? and status in ('HELD','CONFIRMED')",
            Integer.class,
            request));
  }

  void expectRejectionWithoutDomainChange(Long request, Long item, String body) throws Exception {
    DomainState before = state(request, item);
    UUID key = UUID.randomUUID();
    String first =
        holdRaw(key, body)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_INPUT.message()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(state(request, item)).isEqualTo(before);
    var record =
        jdbc.queryForMap(
            "select state,response_status,response_body::text as body from idempotency_record where idempotency_key=?",
            key);
    assertThat(record.get("state")).isEqualTo("DONE");
    assertThat(record.get("response_status")).isEqualTo(400);
    assertThat((String) record.get("body")).contains("INVALID_INPUT");
    // Same-key replay of the identical rejected body returns the stored 400 response.
    holdRaw(key, body)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        .andExpect(content().json(first));
    assertThat(state(request, item)).isEqualTo(before);
  }

  @Test
  void fractionalQuantityIsRejectedWithoutSideEffects() throws Exception {
    Long request = request(5);
    Long item = item(5);
    expectRejectionWithoutDomainChange(
        request, item, "{\"requestId\":" + request + ",\"itemId\":" + item + ",\"quantity\":1.5}");
  }

  @Test
  void stringQuantityIsRejectedWithoutSideEffects() throws Exception {
    Long request = request(5);
    Long item = item(5);
    expectRejectionWithoutDomainChange(
        request,
        item,
        "{\"requestId\":" + request + ",\"itemId\":" + item + ",\"quantity\":\"2\"}");
  }

  @Test
  void otherNonIntegerQuantitiesAreRejectedWithoutSideEffects() throws Exception {
    Long request = request(5);
    Long item = item(5);
    DomainState before = state(request, item);
    String prefix = "{\"requestId\":" + request + ",\"itemId\":" + item + ",\"quantity\":";
    String[] bodies = {
      prefix + "2.0}",
      prefix + "null}",
      "{\"requestId\":" + request + ",\"itemId\":" + item + "}",
      prefix + "true}",
      prefix + "[2]}",
      prefix + "{\"v\":2}}",
      prefix + "2147483648}",
      prefix + "99999999999999999999999}"
    };
    for (String body : bodies)
      holdRaw(UUID.randomUUID(), body)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
          .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_INPUT.message()));
    assertThat(state(request, item)).isEqualTo(before);
  }

  @Test
  void nonPositiveQuantitiesAreRejectedWithoutSideEffects() throws Exception {
    Long request = request(5);
    Long item = item(5);
    expectRejectionWithoutDomainChange(
        request, item, "{\"requestId\":" + request + ",\"itemId\":" + item + ",\"quantity\":0}");
    expectRejectionWithoutDomainChange(
        request, item, "{\"requestId\":" + request + ",\"itemId\":" + item + ",\"quantity\":-1}");
  }

  @Test
  void validIntegerHoldStillCreatesHeldAndReplaysWithoutDoubleDecrement() throws Exception {
    Long request = request(5);
    Long item = item(5);
    String body = "{\"requestId\":" + request + ",\"itemId\":" + item + ",\"quantity\":2}";
    UUID key = UUID.randomUUID();
    String first =
        holdRaw(key, body)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("HELD"))
            .andExpect(jsonPath("$.quantity").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(state(request, item)).isEqualTo(new DomainState(1, 1, 3, 2));
    holdRaw(key, body).andExpect(status().isCreated()).andExpect(content().json(first));
    assertThat(state(request, item)).isEqualTo(new DomainState(1, 1, 3, 2));
    holdRaw(key, "{\"requestId\":" + request + ",\"itemId\":" + item + ",\"quantity\":1}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    assertThat(state(request, item)).isEqualTo(new DomainState(1, 1, 3, 2));
  }

  @Test
  void nonIntegerRequestIdsAreRejectedWithoutSideEffects() throws Exception {
    Long request = request(5);
    Long item = item(5);
    DomainState before = state(request, item);
    String suffix = ",\"itemId\":" + item + ",\"quantity\":1}";
    String[] bodies = {
      "{\"requestId\":1.5" + suffix,
      "{\"requestId\":\"" + request + "\"" + suffix,
      "{\"requestId\":2.0" + suffix,
      "{\"requestId\":null" + suffix,
      "{\"itemId\":" + item + ",\"quantity\":1}",
      "{\"requestId\":true" + suffix,
      "{\"requestId\":[" + request + "]" + suffix,
      "{\"requestId\":{\"v\":" + request + "}" + suffix,
      "{\"requestId\":9223372036854775808" + suffix,
      "{\"requestId\":99999999999999999999999" + suffix
    };
    for (String body : bodies)
      holdRaw(UUID.randomUUID(), body)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
          .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_INPUT.message()));
    assertThat(state(request, item)).isEqualTo(before);
  }

  @Test
  void nonIntegerItemIdsAreRejectedWithoutSideEffects() throws Exception {
    Long request = request(5);
    Long item = item(5);
    DomainState before = state(request, item);
    String prefix = "{\"requestId\":" + request + ",\"itemId\":";
    String[] bodies = {
      prefix + "1.5,\"quantity\":1}",
      prefix + "\"" + item + "\",\"quantity\":1}",
      prefix + "2.0,\"quantity\":1}",
      prefix + "null,\"quantity\":1}",
      "{\"requestId\":" + request + ",\"quantity\":1}",
      prefix + "true,\"quantity\":1}",
      prefix + "[" + item + "],\"quantity\":1}",
      prefix + "{\"v\":" + item + "},\"quantity\":1}",
      prefix + "9223372036854775808,\"quantity\":1}",
      prefix + "99999999999999999999999,\"quantity\":1}"
    };
    for (String body : bodies)
      holdRaw(UUID.randomUUID(), body)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
          .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_INPUT.message()));
    assertThat(state(request, item)).isEqualTo(before);
  }

  @Test
  void unknownIntegerIdsKeepNotFoundBehavior() throws Exception {
    Long request = request(5);
    Long item = item(5);
    Long missingRequest =
        jdbc.queryForObject("select coalesce(max(id),0)+1000000 from pickup_request", Long.class);
    Long missingItem =
        jdbc.queryForObject("select coalesce(max(id),0)+1000000 from item", Long.class);
    DomainState before = state(request, item);
    holdRaw(
            UUID.randomUUID(),
            "{\"requestId\":" + missingRequest + ",\"itemId\":" + item + ",\"quantity\":1}")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    holdRaw(
            UUID.randomUUID(),
            "{\"requestId\":" + request + ",\"itemId\":" + missingItem + ",\"quantity\":1}")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    assertThat(state(request, item)).isEqualTo(before);
  }

  @Test
  void domainRejectionsStillSurfaceOverHttp() throws Exception {
    Long roomyRequest = request(5);
    Long singleItem = item(1);
    String firstBody =
        "{\"requestId\":" + roomyRequest + ",\"itemId\":" + singleItem + ",\"quantity\":1}";
    holdRaw(UUID.randomUUID(), firstBody).andExpect(status().isCreated());
    DomainState beforeUnavailable = state(roomyRequest, singleItem);
    holdRaw(UUID.randomUUID(), firstBody)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("QTY_UNAVAILABLE"));
    assertThat(state(roomyRequest, singleItem)).isEqualTo(beforeUnavailable);

    Long smallRequest = request(1);
    Long roomyItem = item(5);
    String cappedBody =
        "{\"requestId\":" + smallRequest + ",\"itemId\":" + roomyItem + ",\"quantity\":1}";
    holdRaw(UUID.randomUUID(), cappedBody).andExpect(status().isCreated());
    DomainState beforeExcess = state(smallRequest, roomyItem);
    holdRaw(UUID.randomUUID(), cappedBody)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("QTY_EXCEEDS_REQUEST"));
    assertThat(state(smallRequest, roomyItem)).isEqualTo(beforeExcess);

    Long lateItem = item(5);
    jdbc.update(
        "update item set pickup_deadline=? where id=?", java.sql.Timestamp.from(NOW), lateItem);
    Long lateRequest = request(5);
    DomainState beforeClosed = state(lateRequest, lateItem);
    holdRaw(
            UUID.randomUUID(),
            "{\"requestId\":" + lateRequest + ",\"itemId\":" + lateItem + ",\"quantity\":1}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PICKUP_CLOSED"));
    assertThat(state(lateRequest, lateItem)).isEqualTo(beforeClosed);

    Long wideItem = item(5);
    jdbc.update("update item set width_cm=241 where id=?", wideItem);
    Long vanRequest = request(5);
    DomainState beforeFit = state(vanRequest, wideItem);
    holdRaw(
            UUID.randomUUID(),
            "{\"requestId\":" + vanRequest + ",\"itemId\":" + wideItem + ",\"quantity\":1}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VEHICLE_TOO_SMALL"));
    assertThat(state(vanRequest, wideItem)).isEqualTo(beforeFit);
  }
}
