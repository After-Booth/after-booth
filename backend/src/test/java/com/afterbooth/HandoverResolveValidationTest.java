package com.afterbooth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.afterbooth.ai.HandoverPhotoCounter;
import com.afterbooth.allocation.dto.HoldItemRequest;
import com.afterbooth.allocation.service.ReservationService;
import com.afterbooth.stats.service.ReuseStatsService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {"spring.docker.compose.enabled=false", "afterbooth.reservation-sweep-ms=3600000"})
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ReservationIntegrationTest.TimeConfiguration.class})
class HandoverResolveValidationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ReservationService reservations;
  @Autowired ReuseStatsService stats;
  @Autowired ReservationIntegrationTest.MutableClock clock;
  @MockitoBean HandoverPhotoCounter counter;
  static final Instant NOW = ReservationIntegrationTest.NOW;
  byte[] photo;

  @BeforeEach
  void reset() throws Exception {
    clock.set(NOW);
    jdbc.update("delete from idempotency_record");
    jdbc.update("delete from reservation_transition");
    jdbc.update("delete from handover_log");
    jdbc.update("delete from reservation");
    org.mockito.Mockito.reset(counter);
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
    photo = out.toByteArray();
  }

  Long confirmed(int quantity) {
    Long item =
        jdbc.queryForObject(
            "insert into item(event_id,organization_id,category,label,width_cm,depth_cm,height_cm,condition,total_qty,available_qty,pickup_deadline) values(1,1,'STORAGE_BIN','수납함',20,20,20,'GOOD',?,?,?) returning id",
            Long.class,
            quantity,
            quantity,
            java.sql.Timestamp.from(NOW.plusSeconds(3600)));
    Long request =
        jdbc.queryForObject(
            "insert into pickup_request(organization_id,category,quantity,vehicle_type,created_at) values(2,'STORAGE_BIN',?,'VAN',?) returning id",
            Long.class,
            quantity,
            java.sql.Timestamp.from(NOW));
    Long id = reservations.hold(new HoldItemRequest(request, item, quantity)).id();
    reservations.confirm(id);
    return id;
  }

  void mismatch(Long id) throws Exception {
    when(counter.count(any(), anyString(), any()))
        .thenReturn(new HandoverPhotoCounter.Result(0, java.util.List.of(), false));
    mvc.perform(
            multipart("/api/reservations/{id}/handover", id)
                .file(new MockMultipartFile("photo", "test.png", "image/png", photo))
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("MISMATCH"));
  }

  org.springframework.test.web.servlet.ResultActions resolveRaw(Long id, UUID key, String body)
      throws Exception {
    return mvc.perform(
        post("/api/reservations/{id}/handover/resolve", id)
            .contentType("application/json")
            .content(body)
            .header("Idempotency-Key", key));
  }

  record DomainState(
      String status,
      Integer handedOverQty,
      Integer newestAcceptedQty,
      int logCount,
      int availableQty,
      int transitionCount,
      long totalReused) {}

  DomainState state(Long id) {
    var reservation =
        jdbc.queryForMap("select status,handed_over_qty from reservation where id=?", id);
    return new DomainState(
        (String) reservation.get("status"),
        (Integer) reservation.get("handed_over_qty"),
        jdbc.queryForObject(
            "select accepted_qty from handover_log where reservation_id=? order by id desc limit 1",
            Integer.class,
            id),
        jdbc.queryForObject(
            "select count(*) from handover_log where reservation_id=?", Integer.class, id),
        jdbc.queryForObject(
            "select i.available_qty from item i join reservation r on r.item_id=i.id where r.id=?",
            Integer.class,
            id),
        jdbc.queryForObject(
            "select count(*) from reservation_transition where reservation_id=?",
            Integer.class,
            id),
        stats.get(null).totalReused());
  }

  void expectRejectionWithoutDomainChange(Long id, String body) throws Exception {
    DomainState before = state(id);
    assertThat(before.status()).isEqualTo("CONFIRMED");
    UUID key = UUID.randomUUID();
    var first =
        resolveRaw(id, key, body)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.message").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(state(id)).isEqualTo(before);
    assertThat(stats.get(null).byCategory()).isEmpty();
    assertThat(stats.get(null).byEvent()).isEmpty();
    var record =
        jdbc.queryForMap(
            "select state,response_status,response_body::text as body from idempotency_record where idempotency_key=?",
            key);
    assertThat(record.get("state")).isEqualTo("DONE");
    assertThat(record.get("response_status")).isEqualTo(400);
    assertThat((String) record.get("body")).contains("INVALID_INPUT");
    // Same-key replay of the identical rejected body returns the stored 400 response.
    resolveRaw(id, key, body)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        .andExpect(content().json(first));
    assertThat(state(id)).isEqualTo(before);
  }

  @Test
  void fractionalQuantityIsRejectedWithoutSideEffects() throws Exception {
    Long id = confirmed(2);
    mismatch(id);
    expectRejectionWithoutDomainChange(id, "{\"acceptedQty\":1.5}");
  }

  @Test
  void stringQuantityIsRejectedWithoutSideEffects() throws Exception {
    Long id = confirmed(2);
    mismatch(id);
    expectRejectionWithoutDomainChange(id, "{\"acceptedQty\":\"2\"}");
  }

  @Test
  void otherNonIntegerQuantitiesAreRejectedWithoutSideEffects() throws Exception {
    Long id = confirmed(2);
    mismatch(id);
    DomainState before = state(id);
    String[] bodies = {
      "{\"acceptedQty\":2.0}",
      "{\"acceptedQty\":null}",
      "{}",
      "{\"acceptedQty\":true}",
      "{\"acceptedQty\":[2]}",
      "{\"acceptedQty\":{\"v\":2}}",
      "{\"acceptedQty\":2147483648}",
      "{\"acceptedQty\":99999999999999999999999}"
    };
    for (String body : bodies)
      resolveRaw(id, UUID.randomUUID(), body)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    assertThat(state(id)).isEqualTo(before);
    assertThat(stats.get(null).totalReused()).isZero();
  }

  @Test
  void validIntegerResolutionStillCompletesFullyAndPartially() throws Exception {
    Long full = confirmed(2);
    mismatch(full);
    resolveRaw(full, UUID.randomUUID(), "{\"acceptedQty\":2}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.reservation.handedOverQty").value(2));
    assertThat(state(full).availableQty()).isZero();

    Long partial = confirmed(3);
    mismatch(partial);
    UUID key = UUID.randomUUID();
    var first =
        resolveRaw(partial, key, "{\"acceptedQty\":1}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    resolveRaw(partial, key, "{\"acceptedQty\":1}")
        .andExpect(status().isOk())
        .andExpect(content().json(first));
    resolveRaw(partial, UUID.randomUUID(), "{\"acceptedQty\":1}").andExpect(status().isConflict());
    assertThat(state(partial).availableQty()).isEqualTo(2);
    assertThat(state(partial).newestAcceptedQty()).isEqualTo(1);
    assertThat(stats.get(null).totalReused()).isEqualTo(3);
    assertThat(stats.get(null).byCategory()).hasSize(1);
    assertThat(stats.get(null).byEvent()).hasSize(1);
  }

  @Test
  void sameKeyRetryReplaysAndChangedBodyConflicts() throws Exception {
    Long id = confirmed(2);
    mismatch(id);
    UUID key = UUID.randomUUID();
    var first =
        resolveRaw(id, key, "{\"acceptedQty\":1}")
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    resolveRaw(id, key, "{\"acceptedQty\":1}")
        .andExpect(status().isOk())
        .andExpect(content().json(first));
    resolveRaw(id, key, "{\"acceptedQty\":2}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    assertThat(state(id).availableQty()).isEqualTo(1);
    assertThat(state(id).newestAcceptedQty()).isEqualTo(1);
    assertThat(stats.get(null).totalReused()).isEqualTo(1);
  }
}
