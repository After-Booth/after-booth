package com.afterbooth.ai;

import static com.afterbooth.ai.AiTestSupport.fixtureFiles;
import static com.afterbooth.ai.AiTestSupport.gateway;
import static com.afterbooth.ai.AiTestSupport.read;
import static com.afterbooth.ai.AiTestSupport.sample;
import static com.afterbooth.ai.AiTestSupport.spikeResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** live, record and replay against a local stub server; nothing here touches the network. */
class AiGatewayModesTest {

  private static final String KEY = "test-key-0123456789abcdef";
  private static final ChatRequest TEXT =
      new ChatRequest(
          List.of(new ChatRequest.UserMessage("진열대를 한 문장으로 설명해 주세요.", List.of())),
          null,
          List.of(),
          "minimal");

  @TempDir Path fixtures;

  @Test
  void replayAnswersARecordedSparkCallWithNoServerAndNoKey() {
    String closedServer;
    ChatResponse recorded;
    try (StubMetaServer meta = new StubMetaServer()) {
      meta.respond(200, spikeResponse("spark-text/rack-sedan-1.json"));
      recorded = gateway(AiMode.RECORD, meta.baseUrl(), fixtures, KEY).chat(TEXT);
      closedServer = meta.baseUrl();
      assertThat(meta.received()).hasSize(1);
    }

    ChatResponse replayed = gateway(AiMode.REPLAY, closedServer, fixtures, "").chat(TEXT);

    assertThat(replayed).isEqualTo(recorded);
    assertThat(replayed.content()).contains("180cm");
    assertThat(fixtureFiles(fixtures))
        .singleElement()
        .satisfies(file -> assertThat(file.getParent().getFileName()).hasToString("spark"));
  }

  @Test
  void recordWritesASamFixtureThatReplayReadsBackWithoutAnyRequest() {
    SamRequest request = orangeBinsBoxes();
    try (StubMetaServer meta = new StubMetaServer()) {
      meta.respond(200, spikeResponse("sam-boxes/bins-orange-stack-1.json"));
      List<SamSegment> recorded =
          gateway(AiMode.RECORD, meta.baseUrl(), fixtures, KEY).segment(request);

      List<SamSegment> replayed =
          gateway(AiMode.REPLAY, meta.baseUrl(), fixtures, "").segment(request);

      assertThat(replayed).hasSize(4).isEqualTo(recorded);
      assertThat(meta.received()).hasSize(1);
    }
  }

  @Test
  void recordWritesATranscribeFixtureThatReplayReadsBackWithoutAnyRequest() {
    byte[] wav = sample("audio-giver-van-tonight.wav");
    try (StubMetaServer meta = new StubMetaServer()) {
      meta.respond(200, spikeResponse("transcribe/giver-van-tonight-1.json"));
      Transcription recorded =
          gateway(AiMode.RECORD, meta.baseUrl(), fixtures, KEY).transcribe(wav);

      Transcription replayed = gateway(AiMode.REPLAY, meta.baseUrl(), fixtures, "").transcribe(wav);

      assertThat(replayed).isEqualTo(recorded);
      assertThat(replayed.audioDurationMs()).isEqualTo(4800);
      assertThat(meta.received()).hasSize(1);
    }
  }

  @Test
  void recordedFixtureHoldsTheRedactedRequestAndNoKeyOrImageBytes() {
    SamRequest request = orangeBinsBoxes();
    try (StubMetaServer meta = new StubMetaServer()) {
      meta.respond(200, spikeResponse("sam-boxes/bins-orange-stack-1.json"));
      gateway(AiMode.RECORD, meta.baseUrl(), fixtures, KEY).segment(request);
    }

    String fixture = new String(read(fixtureFiles(fixtures).getFirst()), StandardCharsets.UTF_8);
    JsonNode saved = AiTestSupport.JSON.readTree(fixture);
    JsonNode image = saved.at("/request/body/input/0/content/1/image_url");

    assertThat(saved.at("/request/method").stringValue()).isEqualTo("POST");
    assertThat(saved.at("/request/path").stringValue()).isEqualTo("/v1/responses");
    assertThat(image.path("redactedDataUrl").stringValue()).isEqualTo("data:image/jpeg;base64");
    // The same photo the spike sent, so the same SHA-256 as fixtures/spike
    assertThat(image.path("sha256").stringValue())
        .isEqualTo("ca25fb92b51fb2525421d072a96121e0e6998edd9f2823d45c3d9ed8ba3a9986");
    assertThat(saved.at("/response/status").asInt()).isEqualTo(200);
    assertThat(saved.at("/response/body/status").stringValue()).isEqualTo("completed");
    assertThat(fixture)
        .doesNotContain(KEY)
        .doesNotContain("Bearer")
        .doesNotContain(
            Base64.getEncoder().encodeToString(request.image().bytes()).substring(0, 100))
        .doesNotContain("\r\n");
  }

  @Test
  void replayFailsLoudlyWhenNoFixtureWasRecorded() {
    try (StubMetaServer meta = new StubMetaServer()) {
      AiGateway replay = gateway(AiMode.REPLAY, meta.baseUrl(), fixtures, "");

      assertThatThrownBy(() -> replay.chat(TEXT))
          .isInstanceOfSatisfying(
              MissingFixtureException.class,
              e -> {
                assertThat(e.capability()).isEqualTo(Capability.SPARK);
                assertThat(e.key()).matches("[0-9a-f]{64}");
                assertThat(e.expectedPath())
                    .isEqualTo(
                        fixtures
                            .resolve("replay/spark/" + e.key() + ".json")
                            .toAbsolutePath()
                            .normalize());
                assertThat(e.getMessage())
                    .contains("SPARK", e.key(), e.expectedPath().toString(), "AI_MODE=record");
              });
      assertThat(meta.received()).isEmpty();
    }
  }

  @Test
  void liveSendsTheKeyAndWritesNoFixture() {
    try (StubMetaServer meta = new StubMetaServer()) {
      meta.respond(200, spikeResponse("spark-text/rack-sedan-1.json"));

      gateway(AiMode.LIVE, meta.baseUrl(), fixtures, KEY).chat(TEXT);

      assertThat(meta.received())
          .singleElement()
          .satisfies(
              request -> {
                assertThat(request.method()).isEqualTo("POST");
                assertThat(request.path()).isEqualTo("/v1/chat/completions");
                assertThat(request.header("Authorization")).isEqualTo("Bearer " + KEY);
                assertThat(request.header("Transfer-Encoding")).isNull();
                assertThat(request.header("Content-Length"))
                    .isEqualTo(String.valueOf(request.body().length));
              });
      assertThat(fixtureFiles(fixtures)).isEmpty();
    }
  }

  @Test
  void anErrorStatusFailsWithoutLeakingTheKeyAndIsNotRecorded() {
    try (StubMetaServer meta = new StubMetaServer()) {
      meta.respond(401, "{\"error\":{\"message\":\"invalid key " + KEY + "\"}}");
      AiGateway record = gateway(AiMode.RECORD, meta.baseUrl(), fixtures, KEY);

      assertThatThrownBy(() -> record.chat(TEXT))
          .isInstanceOfSatisfying(
              AiCallException.class,
              e -> {
                assertThat(e.capability()).isEqualTo(Capability.SPARK);
                assertThat(e.httpStatus()).hasValue(401);
                assertThat(e.getMessage()).contains("HTTP 401").doesNotContain(KEY);
              });
      assertThat(fixtureFiles(fixtures)).isEmpty();
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = AiMode.class,
      names = {"LIVE", "RECORD"})
  void userTextThatLooksLikeADataUrlIsSentAndRecordedUnchanged(AiMode mode) {
    String text = "data:image/png;base64,not-valid!";
    ChatRequest request =
        new ChatRequest(
            List.of(new ChatRequest.UserMessage(text, List.of())), null, List.of(), null);
    try (StubMetaServer meta = new StubMetaServer()) {
      meta.respond(200, spikeResponse("spark-text/rack-sedan-1.json"));

      gateway(mode, meta.baseUrl(), fixtures, KEY).chat(request);

      JsonNode sent = AiTestSupport.JSON.readTree(meta.received().getFirst().body());
      assertThat(sent.at("/messages/0/content").stringValue()).isEqualTo(text);
    }
    for (Path file : fixtureFiles(fixtures)) {
      assertThat(
              AiTestSupport.JSON
                  .readTree(read(file))
                  .at("/request/body/messages/0/content")
                  .stringValue())
          .isEqualTo(text);
    }
    assertThat(fixtureFiles(fixtures)).hasSize(mode == AiMode.RECORD ? 1 : 0);
  }

  @Test
  void anUnreachableServerIsACallFailureWithoutStatus() {
    String closedServer;
    try (StubMetaServer meta = new StubMetaServer()) {
      closedServer = meta.baseUrl();
    }
    AiGateway live = gateway(AiMode.LIVE, closedServer, fixtures, KEY);

    assertThatThrownBy(() -> live.chat(TEXT))
        .isInstanceOfSatisfying(AiCallException.class, e -> assertThat(e.httpStatus()).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"http-error", "non-json", "timeout", "refused"})
  void liveReturnsRecordedBodyWhenTheLiveCallFails(String failure) throws Exception {
    ChatResponse recorded;
    String closedUrl;
    try (StubMetaServer meta = new StubMetaServer()) {
      meta.respond(200, spikeResponse("spark-text/rack-sedan-1.json"));
      recorded = gateway(AiMode.RECORD, meta.baseUrl(), fixtures, KEY).chat(TEXT);
      closedUrl = meta.baseUrl();
    }
    byte[] before = read(fixtureFiles(fixtures).getFirst());
    try (StubMetaServer meta = new StubMetaServer()) {
      switch (failure) {
        case "http-error" -> meta.respond(500, "{}");
        case "non-json" -> meta.respond(200, "not JSON");
        case "timeout" -> meta.delay(Duration.ofSeconds(2));
        default -> {}
      }
      String url = failure.equals("refused") ? closedUrl : meta.baseUrl();
      long started = System.nanoTime();
      assertThat(gateway(AiMode.LIVE, url, fixtures, KEY).chat(TEXT)).isEqualTo(recorded);
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      assertThat(elapsed).isLessThan(1500);
      if (failure.equals("timeout")) assertThat(elapsed).isGreaterThanOrEqualTo(150);
      assertThat(read(fixtureFiles(fixtures).getFirst())).isEqualTo(before);
    }
  }

  @Test
  void liveFallbackCancelsAStalledResponseBody() throws Exception {
    ChatResponse recorded;
    try (StubMetaServer meta = new StubMetaServer()) {
      meta.respond(200, spikeResponse("spark-text/rack-sedan-1.json"));
      recorded = gateway(AiMode.RECORD, meta.baseUrl(), fixtures, KEY).chat(TEXT);
    }
    try (StubMetaServer meta = new StubMetaServer().stallBody()) {
      long started = System.nanoTime();
      assertThat(gateway(AiMode.LIVE, meta.baseUrl(), fixtures, KEY).chat(TEXT))
          .isEqualTo(recorded);
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1500);
      assertThat(meta.awaitDisconnect()).isTrue();
    }
  }

  @Test
  void liveSuccessTakesPrecedenceOverTheFixtureWithoutOverwritingIt() {
    try (StubMetaServer meta = new StubMetaServer()) {
      meta.respond(200, spikeResponse("spark-text/rack-sedan-1.json"));
      ChatResponse recorded = gateway(AiMode.RECORD, meta.baseUrl(), fixtures, KEY).chat(TEXT);
      byte[] before = read(fixtureFiles(fixtures).getFirst());
      meta.respond(
          200,
          "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"live answer\"}}]}");
      assertThat(gateway(AiMode.LIVE, meta.baseUrl(), fixtures, KEY).chat(TEXT).content())
          .isEqualTo("live answer")
          .isNotEqualTo(recorded.content());
      assertThat(read(fixtureFiles(fixtures).getFirst())).isEqualTo(before);
      assertThat(meta.received()).hasSize(2);
    }
  }

  @Test
  void recordReusesAnExistingFixtureWithoutCallingHttp() {
    try (StubMetaServer meta = new StubMetaServer()) {
      meta.respond(200, spikeResponse("spark-text/rack-sedan-1.json"));
      ChatResponse first = gateway(AiMode.RECORD, meta.baseUrl(), fixtures, KEY).chat(TEXT);
      meta.respond(500, "{}");
      assertThat(gateway(AiMode.RECORD, meta.baseUrl(), fixtures, KEY).chat(TEXT)).isEqualTo(first);
      assertThat(meta.received()).hasSize(1);
      assertThat(fixtureFiles(fixtures)).hasSize(1);
    }
  }

  @Test
  void concurrentRecordingSharesOneRequestAndOneFixture() throws Exception {
    try (StubMetaServer meta = new StubMetaServer();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      meta.respond(200, spikeResponse("spark-text/rack-sedan-1.json"))
          .delay(Duration.ofMillis(300));
      AiGateway record = gateway(AiMode.RECORD, meta.baseUrl(), fixtures, KEY);
      CountDownLatch start = new CountDownLatch(1);
      var first =
          executor.submit(
              () -> {
                start.await();
                return record.chat(TEXT);
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return record.chat(TEXT);
              });
      start.countDown();
      assertThat(first.get(3, TimeUnit.SECONDS)).isEqualTo(second.get(3, TimeUnit.SECONDS));
      assertThat(meta.received()).hasSize(1);
      assertThat(fixtureFiles(fixtures)).hasSize(1);
    }
  }

  @Test
  void failedConcurrentRecordingIsNotCachedAndCanBeRecordedLater() throws Exception {
    try (StubMetaServer meta = new StubMetaServer();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      meta.respond(500, "{}").delay(Duration.ofMillis(300));
      AiGateway record = gateway(AiMode.RECORD, meta.baseUrl(), fixtures, KEY);
      CountDownLatch start = new CountDownLatch(1);
      var first =
          executor.submit(
              () -> {
                start.await();
                return record.chat(TEXT);
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return record.chat(TEXT);
              });
      start.countDown();
      assertThatThrownBy(() -> first.get(3, TimeUnit.SECONDS))
          .hasCauseInstanceOf(AiCallException.class);
      assertThatThrownBy(() -> second.get(3, TimeUnit.SECONDS))
          .hasCauseInstanceOf(AiCallException.class);
      assertThat(meta.received()).hasSize(1);
      assertThat(fixtureFiles(fixtures)).isEmpty();
      meta.respond(200, spikeResponse("spark-text/rack-sedan-1.json")).delay(Duration.ZERO);
      assertThat(record.chat(TEXT).content()).contains("180cm");
      assertThat(meta.received()).hasSize(2);
      assertThat(fixtureFiles(fixtures)).hasSize(1);
    }
  }

  private static SamRequest orangeBinsBoxes() {
    return new SamRequest(
        new Image(sample("bins-orange-stack.jpg"), "image/jpeg"),
        new SamRequest.Boxes(
            1280,
            960,
            List.of(
                new PixelBox(171, 237, 1172, 867),
                new PixelBox(230, 642, 1106, 959),
                new PixelBox(956, 473, 1279, 894),
                new PixelBox(959, 774, 1279, 959))));
  }
}
