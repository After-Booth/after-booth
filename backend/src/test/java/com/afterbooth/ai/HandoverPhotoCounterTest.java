package com.afterbooth.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.afterbooth.inventory.domain.ItemCategory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class HandoverPhotoCounterTest {
  private final AiGateway gateway = mock(AiGateway.class);
  private final HandoverPhotoCounter counter = new HandoverPhotoCounter(gateway, "medium", 0.5);

  @Test
  void countsReservedCategoryAndMapsBatchLocalIds() throws Exception {
    when(gateway.chat(any())).thenReturn(response(17));
    AtomicInteger calls = new AtomicInteger();
    when(gateway.segment(any()))
        .thenAnswer(
            invocation -> {
              SamRequest request = invocation.getArgument(0);
              int size = ((SamRequest.Boxes) request.prompt()).boxes().size();
              calls.incrementAndGet();
              return IntStream.rangeClosed(1, size).mapToObj(id -> segment(id, 0.5)).toList();
            });
    var result = counter.count(photo(), "image/png", ItemCategory.STORAGE_BIN);
    assertThat(result.count()).isEqualTo(17);
    assertThat(result.boxes()).hasSize(17);
    assertThat(result.allSegmented()).isTrue();
    assertThat(calls.get()).isEqualTo(2);
    ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
    verify(gateway).chat(request.capture());
    assertThat(((ChatRequest.UserMessage) request.getValue().messages().get(1)).text())
        .isEqualTo("Count storage bins.");
    assertThat(request.getValue().reasoningEffort()).isEqualTo("medium");
  }

  @Test
  void zeroCountSkipsSamAndRemainsResolvableMismatch() throws Exception {
    when(gateway.chat(any())).thenReturn(response(0));
    var result = counter.count(photo(), "image/png", ItemCategory.DISPLAY_RACK);
    assertThat(result.count()).isZero();
    assertThat(result.boxes()).isEmpty();
    assertThat(result.allSegmented()).isFalse();
    verify(gateway, never()).segment(any());
  }

  @ParameterizedTest
  @CsvSource({
    "DISPLAY_RACK,display racks",
    "STORAGE_BIN,storage bins",
    "FOLDING_TABLE,folding tables"
  })
  void describesEveryCategoryWithTheIntakeWording(ItemCategory category, String description)
      throws Exception {
    when(gateway.chat(any())).thenReturn(response(0));
    counter.count(photo(), "image/png", category);
    ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
    verify(gateway).chat(request.capture());
    assertThat(((ChatRequest.UserMessage) request.getValue().messages().get(1)).text())
        .isEqualTo("Count " + description + ".");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "empty",
        "missing",
        "duplicate",
        "outOfRange",
        "lowScore",
        "nanScore",
        "blankMask",
        "invalidBox"
      })
  void completedButIncompleteSegmentationIsMismatch(String mode) throws Exception {
    when(gateway.chat(any())).thenReturn(response(2));
    List<SamSegment> output =
        switch (mode) {
          case "empty" -> List.of();
          case "missing" -> List.of(segment(1, 0.9));
          case "duplicate" -> List.of(segment(1, 0.9), segment(1, 0.9), segment(2, 0.9));
          case "outOfRange" -> List.of(segment(0, 0.9), segment(2, 0.9));
          case "lowScore" -> List.of(segment(1, 0.49), segment(2, 0.9));
          case "nanScore" -> List.of(segment(1, Double.NaN), segment(2, 0.9));
          case "blankMask" ->
              List.of(
                  new SamSegment(1, new PixelBox(0, 0, 5, 5), 0.9, new SamSegment.Mask(0, 0, "")),
                  segment(2, 0.9));
          default ->
              List.of(
                  new SamSegment(
                      1, new PixelBox(-1, 0, 5, 5), 0.9, new SamSegment.Mask(0, 0, "mask")),
                  segment(2, 0.9));
        };
    when(gateway.segment(any())).thenReturn(output);
    var result = counter.count(photo(), "image/png", ItemCategory.FOLDING_TABLE);
    assertThat(result.count()).isEqualTo(2);
    assertThat(result.boxes()).hasSize(2);
    assertThat(result.allSegmented()).isFalse();
  }

  @Test
  void sparkFailurePropagatesWithoutSam() throws Exception {
    when(gateway.chat(any())).thenThrow(new AiCallException(Capability.SPARK, "unavailable"));
    assertThatThrownBy(() -> counter.count(photo(), "image/png", ItemCategory.DISPLAY_RACK))
        .isInstanceOf(AiCallException.class);
    verify(gateway, never()).segment(any());
  }

  @Test
  void samFailurePropagatesRatherThanReturningMismatch() throws Exception {
    when(gateway.chat(any())).thenReturn(response(1));
    when(gateway.segment(any())).thenThrow(new AiCallException(Capability.SAM, "unavailable"));
    assertThatThrownBy(() -> counter.count(photo(), "image/png", ItemCategory.DISPLAY_RACK))
        .isInstanceOf(AiCallException.class);
  }

  @Test
  void reasksOnceForInvalidSchemaAndReturnsCorrectedCount() throws Exception {
    when(gateway.chat(any()))
        .thenReturn(
            new ChatResponse("{\"count\":1,\"boxes\":[]}", List.of(), "stop", null), response(0));
    assertThat(counter.count(photo(), "image/png", ItemCategory.DISPLAY_RACK).count()).isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "{}",
        "{\"count\":0,\"boxes\":[],\"extra\":1}",
        "{\"count\":0,\"boxes\":[]} trailing",
        "{\"count\":1,\"boxes\":[{\"x\":0.9,\"y\":0,\"width\":0.2,\"height\":0.2}]}",
        "{\"count\":1,\"boxes\":[{\"x\":0,\"y\":0,\"width\":0,\"height\":0.2}]}"
      })
  void rejectsInvalidSparkOutputAfterOneReask(String content) throws Exception {
    when(gateway.chat(any())).thenReturn(new ChatResponse(content, List.of(), "stop", null));
    assertThatThrownBy(() -> counter.count(photo(), "image/png", ItemCategory.DISPLAY_RACK))
        .isInstanceOf(AiCallException.class);
    verify(gateway, never()).segment(any());
  }

  @Test
  void realGatewayRecordsBothCallsAndReplaysWithServerClosed(@TempDir Path fixtures)
      throws Exception {
    String closedUrl;
    HandoverPhotoCounter.Result recorded;
    byte[] image = photo();
    try (StubMetaServer server = new StubMetaServer()) {
      AiGateway real =
          AiTestSupport.gateway(AiMode.RECORD, server.baseUrl(), fixtures, "local-test-key");
      AiGateway local =
          new AiGateway() {
            @Override
            public ChatResponse chat(ChatRequest request) {
              var body = AiTestSupport.JSON.createObjectNode();
              var choice = body.putArray("choices").addObject();
              choice
                  .put("finish_reason", "stop")
                  .putObject("message")
                  .put("content", response(1).content());
              server.respond(200, body.toString());
              return real.chat(request);
            }

            @Override
            public List<SamSegment> segment(SamRequest request) {
              var body = AiTestSupport.JSON.createObjectNode().put("status", "completed");
              body.putArray("output")
                  .addObject()
                  .putArray("content")
                  .addObject()
                  .put(
                      "text",
                      "<0f>1<|box;x1=1;y1=1;x2=5;y2=5;w=5;h=5;c=0.9|><|mask;x=0;y=0;c=0.9;data=mask|>");
              server.respond(200, body.toString());
              return real.segment(request);
            }

            @Override
            public Transcription transcribe(byte[] wav) {
              throw new AssertionError("Unexpected transcription");
            }
          };
      recorded =
          new HandoverPhotoCounter(local, "medium", 0.5)
              .count(image, "image/png", ItemCategory.STORAGE_BIN);
      assertThat(recorded.allSegmented()).isTrue();
      assertThat(server.received()).hasSize(2);
      closedUrl = server.baseUrl();
    }
    AiGateway replay = AiTestSupport.gateway(AiMode.REPLAY, closedUrl, fixtures, "");
    assertThat(
            new HandoverPhotoCounter(replay, "medium", 0.5)
                .count(image, "image/png", ItemCategory.STORAGE_BIN))
        .isEqualTo(recorded);
    assertThat(AiTestSupport.fixtureFiles(fixtures)).hasSize(2);
  }

  private static ChatResponse response(int count) {
    List<String> boxes = new ArrayList<>();
    for (int i = 0; i < count; i++) boxes.add("{\"x\":0,\"y\":0,\"width\":0.5,\"height\":0.5}");
    return new ChatResponse(
        "{\"count\":" + count + ",\"boxes\":[" + String.join(",", boxes) + "]}",
        List.of(),
        "stop",
        null);
  }

  private static SamSegment segment(int id, double score) {
    return new SamSegment(id, new PixelBox(1, 1, 5, 5), score, new SamSegment.Mask(0, 0, "mask"));
  }

  private static byte[] photo() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "png", out);
    return out.toByteArray();
  }
}
