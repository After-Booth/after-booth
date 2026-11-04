package com.afterbooth.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.afterbooth.intake.pipeline.IntakePhoto;
import com.afterbooth.intake.pipeline.IntakePipeline;
import com.afterbooth.inventory.domain.ItemCategory;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Prepares first-turn requests only; no provider response, network, recording or member bypass. */
class SyntheticDemoRequestPreparationTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String BINS_DIGEST =
      "ca25fb92b51fb2525421d072a96121e0e6998edd9f2823d45c3d9ed8ba3a9986";
  private static final String RACK_DIGEST =
      "1ef80c842d03dbebb326b13fe0d4242af5334ffe3dceb862cee19179fdcabc16";

  @Test
  void preparesExactBinsIntakeFirstRequestWithoutAnAiResponse() throws Exception {
    byte[] photo = reviewedPhoto("bins-orange-stack.jpg", BINS_DIGEST);
    prepare(
        "bins-intake",
        "d65a99d1191a0ef88b8c7e5c859570facfc128fceb16a8e61ea7aea65c8a2805",
        BINS_DIGEST,
        gateway -> pipeline(gateway).estimate(new IntakePhoto(photo, "image/jpeg")));
  }

  @Test
  void preparesExactRackIntakeFirstRequestWithoutAnAiResponse() throws Exception {
    byte[] photo = reviewedPhoto("rack-gondola-single.jpg", RACK_DIGEST);
    prepare(
        "rack-intake",
        "97e4168ccb9cb37cd0082effe2effc27d42ac36e9bac55a1802322b1e54322ed",
        RACK_DIGEST,
        gateway -> pipeline(gateway).estimate(new IntakePhoto(photo, "image/jpeg")));
  }

  @Test
  void preparesExactRackHandoverFirstRequestWithoutAnAiResponse() throws Exception {
    byte[] photo = reviewedPhoto("rack-gondola-single.jpg", RACK_DIGEST);
    prepare(
        "rack-handover",
        "31186f2f5caf929e96f0f27e1499f19cc7e70e17bf09b4940ecd6e87b23cce1b",
        RACK_DIGEST,
        gateway ->
            new HandoverPhotoCounter(gateway, "minimal", 0.5)
                .count(photo, "image/jpeg", ItemCategory.DISPLAY_RACK));
  }

  private static IntakePipeline pipeline(AiGateway gateway) {
    return new IntakePipeline(gateway, JSON, "minimal", new BigDecimal("0.8"), 0.5);
  }

  private static byte[] reviewedPhoto(String name, String expectedDigest) throws Exception {
    byte[] photo = AiTestSupport.sample(name);
    assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(photo)))
        .isEqualTo(expectedDigest);
    return photo;
  }

  private static void prepare(
      String stage, String expectedKey, String imageDigest, Consumer<AiGateway> call) {
    MetaExchange exchange = mock(MetaExchange.class);
    AtomicReference<JsonNode> captured = new AtomicReference<>();
    doAnswer(
            invocation -> {
              captured.set(FixtureStore.redact(invocation.getArgument(1, ObjectNode.class)));
              throw new PreparedRequest();
            })
        .when(exchange)
        .postJson(eq(Capability.SPARK), any(ObjectNode.class));
    var properties =
        AiTestSupport.properties(AiMode.REPLAY, "https://api.meta.ai", Path.of("../fixtures"), "");
    assertThatThrownBy(() -> call.accept(new MetaAiGateway(properties, exchange)))
        .isExactlyInstanceOf(PreparedRequest.class);
    org.mockito.Mockito.verify(exchange).postJson(eq(Capability.SPARK), any(ObjectNode.class));
    verifyNoMoreInteractions(exchange);
    JsonNode request = captured.get();
    assertThat(request).isNotNull();
    assertThat(request.path("model").stringValue()).isEqualTo("muse-spark-1.3");
    assertThat(request.path("messages").size()).isEqualTo(2);
    String sanitized = JSON.writeValueAsString(request);
    assertThat(sanitized)
        .contains(imageDigest)
        .doesNotContain(
            "base64,", "Bearer ", "Authorization", "password", "@", "private-key", "refresh_token");
    String key =
        new FixtureStore(Path.of("../fixtures"), JSON).key("POST", "/v1/chat/completions", request);
    assertThat(key).isEqualTo(expectedKey);
    // Only stage names and hashes are emitted, never image data, headers or generated responses.
    System.out.println("Synthetic first-request preparation: " + stage + " spark/" + key);
  }

  private static final class PreparedRequest extends RuntimeException {}
}
