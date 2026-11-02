package com.afterbooth.intake.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.afterbooth.global.domain.BoundingBox;
import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.intake.domain.DraftCategory;
import com.afterbooth.intake.domain.IntakeStatus;
import com.afterbooth.intake.dto.ConfirmIntakeRequest;
import com.afterbooth.intake.dto.IntakeDraftResponse;
import com.afterbooth.intake.dto.SizeCm;
import com.afterbooth.intake.pipeline.EstimatedLine;
import com.afterbooth.intake.pipeline.IntakePipeline;
import com.afterbooth.intake.repository.IntakeDraftLineRepository;
import com.afterbooth.inventory.domain.Condition;
import com.afterbooth.inventory.domain.ItemCategory;
import com.afterbooth.inventory.domain.VehicleType;
import com.afterbooth.inventory.dto.ItemResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "afterbooth.ai.mode=replay")
@Import(IntakeIntegrationTest.PostgresConfiguration.class)
@AutoConfigureMockMvc
class IntakeIntegrationTest {
  @TestConfiguration(proxyBeanMethods = false)
  static class PostgresConfiguration {
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
      return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    }
  }

  @org.springframework.beans.factory.annotation.Value("${local.server.port}")
  int port;

  @Autowired IntakeService service;
  @Autowired IntakeProcessingStore store;
  @Autowired IntakeDraftLineRepository lines;
  @Autowired MockMvc mvc;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;

  @MockitoBean(name = "intakeExecutor")
  ThreadPoolTaskExecutor executor;

  @MockitoBean IntakePipeline pipeline;
  private byte[] photo;
  private Long eventId;

  @BeforeEach
  void setup() throws Exception {
    var output = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
    photo = output.toByteArray();
    eventId = jdbc.queryForObject("SELECT id FROM event LIMIT 1", Long.class);
  }

  @Test
  void createCommitsBeforeDispatchAndRetryReturnsFirstProcessingSnapshot() {
    UUID key = UUID.randomUUID();
    doAnswer(
            invocation -> {
              try (var connection = jdbc.getDataSource().getConnection();
                  var statement =
                      connection.prepareStatement(
                          "SELECT state FROM idempotency_record WHERE idempotency_key=?")) {
                statement.setObject(1, key);
                try (var rows = statement.executeQuery()) {
                  assertThat(rows.next()).isTrue();
                  assertThat(rows.getString(1)).isEqualTo("DONE");
                }
              }
              return null;
            })
        .when(executor)
        .execute(any(Runnable.class));
    var first = create(key, eventId, photo, "image/png");
    assertThat(first.status()).isEqualTo(IntakeStatus.PROCESSING);
    ready(first.id());
    assertThat(service.get(first.id()).status()).isEqualTo(IntakeStatus.READY);
    assertThat(create(key, eventId, photo, "image/png")).isEqualTo(first);
    verify(executor, times(1)).execute(any(Runnable.class));
    assertThatThrownBy(() -> create(key, eventId + 10000, photo, "image/png"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
    assertThatThrownBy(() -> create(key, eventId, new byte[] {1}, "image/png"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
  }

  @Test
  void confirmCreatesEditedItemsAndReplaysStoredArrayWithoutDuplicates() {
    var draft = create(UUID.randomUUID(), eventId, photo, "image/png");
    ready(draft.id());
    Long lineId = lines.findByDraftIdOrderById(draft.id()).getFirst().id();
    UUID key = UUID.randomUUID();
    var request = request(lineId, 3);
    List<ItemResponse> first = confirm(key, draft.id(), request);
    assertThat(first).hasSize(1);
    var item = first.getFirst();
    assertThat(item.category()).isEqualTo(ItemCategory.STORAGE_BIN);
    assertThat(item.label()).isEqualTo(ItemCategory.STORAGE_BIN.label());
    assertThat(item.totalQty()).isEqualTo(3);
    assertThat(item.availableQty()).isEqualTo(3);
    assertThat(item.pickupDeadline().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    assertThat(
            jdbc.queryForObject(
                "SELECT pickup_deadline=e.teardown_deadline FROM item i JOIN event e ON"
                    + " i.event_id=e.id WHERE i.id=?",
                Boolean.class,
                item.id()))
        .isTrue();
    assertThat(confirm(key, draft.id(), request)).isEqualTo(first);
    assertThat(
            jdbc.queryForObject(
                "SELECT response_body::text FROM idempotency_record WHERE idempotency_key=?",
                String.class,
                key))
        .startsWith("[");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM item WHERE intake_draft_id=?", Long.class, draft.id()))
        .isEqualTo(1);
    assertThat(service.get(draft.id()).status()).isEqualTo(IntakeStatus.CONFIRMED);
    assertThatThrownBy(() -> confirm(UUID.randomUUID(), draft.id(), request))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.DRAFT_ALREADY_CONFIRMED);
    assertThatThrownBy(() -> confirm(key, draft.id(), request(lineId, 4)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
  }

  @Test
  void confirmRejectsSameKeyWithDifferentRawJsonBytes() throws Exception {
    var draft = create(UUID.randomUUID(), eventId, photo, "image/png");
    ready(draft.id());
    Long line = lines.findByDraftIdOrderById(draft.id()).getFirst().id();
    UUID key = UUID.randomUUID();
    var request = request(line, 2);
    confirm(key, draft.id(), request);
    mvc.perform(
            post("/api/intake/drafts/" + draft.id() + "/confirm")
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(json.writeValueAsString(request) + " "))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM item WHERE intake_draft_id=?", Long.class, draft.id()))
        .isEqualTo(1);
  }

  @Test
  void confirmRejectsCoercibleNonIntegerJsonWithoutCreatingItems() throws Exception {
    var draft = create(UUID.randomUUID(), eventId, photo, "image/png");
    ready(draft.id());
    Long line = lines.findByDraftIdOrderById(draft.id()).getFirst().id();
    String size = "\"sizeCm\":{\"width\":60,\"depth\":40,\"height\":35}";
    for (String body :
        List.of(
            "{\"lines\":[{\"lineId\":"
                + line
                + ".0,\"category\":\"STORAGE_BIN\",\"quantity\":1,"
                + size
                + ",\"condition\":\"FAIR\"}]}",
            "{\"lines\":[{\"lineId\":"
                + line
                + ",\"category\":\"STORAGE_BIN\",\"quantity\":1.5,"
                + size
                + ",\"condition\":\"FAIR\"}]}",
            "{\"lines\":[{\"lineId\":"
                + line
                + ",\"category\":\"STORAGE_BIN\",\"quantity\":1,\"sizeCm\":{\"width\":\"60\",\"depth\":40,\"height\":35},\"condition\":\"FAIR\"}]}")) {
      mvc.perform(
              post("/api/intake/drafts/" + draft.id() + "/confirm")
                  .header("Idempotency-Key", UUID.randomUUID())
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
    assertThat(service.get(draft.id()).status()).isEqualTo(IntakeStatus.READY);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM item WHERE intake_draft_id=?", Long.class, draft.id()))
        .isZero();
  }

  @Test
  void confirmationRejectsForeignDuplicateAndLateDeadlineAndRollsBackState() {
    var draft = create(UUID.randomUUID(), eventId, photo, "image/png");
    ready(draft.id());
    Long own = lines.findByDraftIdOrderById(draft.id()).getFirst().id();
    assertThatThrownBy(() -> confirm(UUID.randomUUID(), draft.id(), request(-1L, 1)))
        .isInstanceOf(BusinessException.class);
    var line = request(own, 1).lines().getFirst();
    assertThatThrownBy(
            () ->
                confirm(
                    UUID.randomUUID(),
                    draft.id(),
                    new ConfirmIntakeRequest(List.of(line, line), null, null, null)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () ->
                confirm(
                    UUID.randomUUID(),
                    draft.id(),
                    new ConfirmIntakeRequest(
                        List.of(line),
                        OffsetDateTime.parse("2099-01-01T00:00:00+09:00"),
                        null,
                        null)))
        .isInstanceOf(BusinessException.class);
    assertThat(service.get(draft.id()).status()).isEqualTo(IntakeStatus.READY);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM item WHERE intake_draft_id=?", Long.class, draft.id()))
        .isZero();
  }

  @Test
  void notReadyMissingEventAndInFlightIdempotencyAreRejected() {
    var draft = create(UUID.randomUUID(), eventId, photo, "image/png");
    assertThatThrownBy(() -> confirm(UUID.randomUUID(), draft.id(), request(1L, 1)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.DRAFT_NOT_READY);
    assertThatThrownBy(() -> create(UUID.randomUUID(), -1L, photo, "image/png"))
        .isInstanceOf(BusinessException.class);
    UUID key = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO idempotency_record(idempotency_key,fingerprint,state,created_at) VALUES (?, ?,"
            + " 'IN_PROGRESS', CURRENT_TIMESTAMP)",
        key,
        "busy");
    assertThatThrownBy(() -> create(key, eventId, photo, "image/png"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
  }

  @Test
  void simultaneousDifferentKeyConfirmationsCreateInventoryOnce() throws Exception {
    var draft = create(UUID.randomUUID(), eventId, photo, "image/png");
    ready(draft.id());
    Long line = lines.findByDraftIdOrderById(draft.id()).getFirst().id();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> confirm =
          () -> {
            start.await();
            try {
              confirm(UUID.randomUUID(), draft.id(), request(line, 2));
              return true;
            } catch (BusinessException e) {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.DRAFT_ALREADY_CONFIRMED);
              return false;
            }
          };
      var a = pool.submit(confirm);
      var b = pool.submit(confirm);
      start.countDown();
      assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM item WHERE intake_draft_id=?", Long.class, draft.id()))
        .isEqualTo(1);
  }

  @Test
  void sameKeyInFlightReturnsConflictBeforeFirstResponseAndDoesNotDuplicate() throws Exception {
    var draft = create(UUID.randomUUID(), eventId, photo, "image/png");
    ready(draft.id());
    Long line = lines.findByDraftIdOrderById(draft.id()).getFirst().id();
    UUID key = UUID.randomUUID();
    // Holding the draft lock blocks the first confirm after its idempotency claim is committed.
    try (var pool = Executors.newSingleThreadExecutor();
        var connection = jdbc.getDataSource().getConnection()) {
      connection.setAutoCommit(false);
      try (var statement =
          connection.prepareStatement("SELECT id FROM intake_draft WHERE id=? FOR UPDATE")) {
        statement.setLong(1, draft.id());
        statement.executeQuery().close();
      }
      var first = pool.submit(() -> confirm(key, draft.id(), request(line, 2)));
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (claimCount(key) == 0 && System.nanoTime() < until) Thread.onSpinWait();
      assertThat(claimCount(key)).isEqualTo(1);
      assertThatThrownBy(() -> confirm(key, draft.id(), request(line, 2)))
          .isInstanceOf(BusinessException.class)
          .extracting(e -> ((BusinessException) e).errorCode())
          .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
      connection.commit();
      assertThat(first.get(15, TimeUnit.SECONDS)).hasSize(1);
    }
  }

  @Test
  void embeddedServerRejectsOversizeWithContractErrorBeforeHandlerMapping() throws Exception {
    var response = upload(new byte[10 * 1024 * 1024 + 1]);
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("INVALID_INPUT");
  }

  @Test
  void embeddedServerAcceptsAValidPhotoAboveOneMegabyte() throws Exception {
    var image = new BufferedImage(700, 700, BufferedImage.TYPE_INT_RGB);
    var random = new Random(5);
    for (int y = 0; y < 700; y++)
      for (int x = 0; x < 700; x++) image.setRGB(x, y, random.nextInt());
    var output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    assertThat(output.size()).isGreaterThan(1024 * 1024);
    var response = upload(output.toByteArray());
    assertThat(response.statusCode()).isEqualTo(202);
    assertThat(response.body()).contains("PROCESSING");
  }

  private java.net.http.HttpResponse<String> upload(byte[] bytes) throws Exception {
    String boundary = "AfterBoothTestBoundary";
    String prefix =
        "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\"eventId\"\r\n\r\n"
            + eventId
            + "\r\n--"
            + boundary
            + "\r\n"
            + "Content-Disposition: form-data; name=\"photo\"; filename=\"photo.png\"\r\n"
            + "Content-Type: image/png\r\n\r\n";
    var body =
        java.net.http.HttpRequest.BodyPublishers.concat(
            java.net.http.HttpRequest.BodyPublishers.ofString(prefix),
            java.net.http.HttpRequest.BodyPublishers.ofByteArray(bytes),
            java.net.http.HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
    var request =
        java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://localhost:" + port + "/api/intake/drafts"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .POST(body)
            .build();
    try (var client = java.net.http.HttpClient.newHttpClient()) {
      return client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
    }
  }

  private IntakeDraftResponse create(UUID key, Long eventId, byte[] photo, String mediaType) {
    try {
      var response =
          mvc.perform(
                  multipart("/api/intake/drafts")
                      .file(new MockMultipartFile("photo", "photo.png", mediaType, photo))
                      .param("eventId", eventId.toString())
                      .header("Idempotency-Key", key))
              .andReturn()
              .getResponse();
      requireStatus(response, 202);
      return json.readValue(response.getContentAsString(), IntakeDraftResponse.class);
    } catch (BusinessException error) {
      throw error;
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private List<ItemResponse> confirm(UUID key, Long id, ConfirmIntakeRequest request) {
    try {
      var response =
          mvc.perform(
                  post("/api/intake/drafts/" + id + "/confirm")
                      .header("Idempotency-Key", key)
                      .contentType("application/json")
                      .content(json.writeValueAsBytes(request)))
              .andReturn()
              .getResponse();
      requireStatus(response, 201);
      assertThat(response.getContentAsString()).startsWith("[");
      return json.readValue(
          response.getContentAsString(), new TypeReference<List<ItemResponse>>() {});
    } catch (BusinessException error) {
      throw error;
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private void requireStatus(MockHttpServletResponse response, int successStatus) throws Exception {
    if (response.getStatus() != successStatus) {
      throw new BusinessException(
          ErrorCode.valueOf(json.readTree(response.getContentAsString()).get("code").asText()));
    }
  }

  private int claimCount(UUID key) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM idempotency_record WHERE idempotency_key=?", Integer.class, key);
  }

  private void ready(Long id) {
    store.ready(
        id,
        List.of(
            new EstimatedLine(
                DraftCategory.DISPLAY_RACK,
                1,
                List.of(new BoundingBox(0.1, 0.1, 0.2, 0.3)),
                90,
                40,
                180,
                Condition.GOOD,
                new BigDecimal("0.9"),
                false)));
  }

  private ConfirmIntakeRequest request(Long lineId, int qty) {
    return new ConfirmIntakeRequest(
        List.of(
            new ConfirmIntakeRequest.ConfirmLine(
                lineId, ItemCategory.STORAGE_BIN, qty, new SizeCm(60, 40, 35), Condition.FAIR)),
        null,
        VehicleType.SEDAN,
        "typed conditions");
  }
}
