package com.afterbooth.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.afterbooth.inventory.domain.ItemCategory;
import com.afterbooth.inventory.domain.VehicleType;
import com.afterbooth.request.domain.ConditionOutput;
import com.afterbooth.request.domain.ConditionPrompt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Exact-request preparation; the paid method requires separate explicit human approval. */
class EnglishConditionRecordingTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String KEY =
      "4c5fb1f9cfcbd4263b79d4c0f71865b7b42eb1e67e0eb7d9351e63dc92ccb711";
  private static final String ORIGINAL =
      "0890f3ea8678a3cf3e08248a08b4e7299843bb93cf909058c3e9600cbc5a3dc0";

  @Test
  void identifiesExactEnglishRequestWithoutNetworkOrInventedResponses() throws Exception {
    JsonNode historical = historicalBody();
    AiConfigurationTest.applicationContext()
        .withPropertyValues("afterbooth.ai.mode=replay", "afterbooth.ai.api-key=")
        .run(
            context -> {
              AiProperties properties = context.getBean(AiProperties.class);
              JsonNode current = serializedRequest(properties);
              FixtureStore fixtures = new FixtureStore(Path.of("../fixtures"), JSON);
              assertThat(fixtures.key("POST", properties.paths().spark(), current)).isEqualTo(KEY);
              assertThat(fixtures.key("POST", properties.paths().spark(), historical))
                  .isEqualTo(ORIGINAL);
              assertThat(historical.isObject()).isTrue();
              ObjectNode expected = (ObjectNode) historical.deepCopy();
              ((ObjectNode) expected.path("messages").path(0))
                  .put("content", ConditionPrompt.SYSTEM);
              assertThat(current).isEqualTo(expected);
            });
  }

  @Test
  @EnabledIfSystemProperty(
      named = "afterbooth.english-condition-recording-approved",
      matches = "true")
  void recordsExactlyOneApprovedSparkRequest() {
    AiConfigurationTest.applicationContext()
        .withPropertyValues("afterbooth.ai.mode=record")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              AiProperties properties = context.getBean(AiProperties.class);
              assertThat(properties.baseUrl()).isEqualTo("https://api.meta.ai");
              assertThat(properties.models().spark()).isEqualTo("muse-spark-1.3");
              assertThat(properties.paths().spark()).isEqualTo("/v1/chat/completions");
              assertThat(Path.of(properties.fixturesDir()).toAbsolutePath().normalize())
                  .isEqualTo(Path.of("../fixtures").toAbsolutePath().normalize());
              JsonNode body = serializedRequest(properties);
              FixtureStore fixtures = new FixtureStore(Path.of(properties.fixturesDir()), JSON);
              assertThat(fixtures.key("POST", properties.paths().spark(), body)).isEqualTo(KEY);
              // One call site, no retry or validation re-ask: at most one new paid HTTP attempt.
              ChatResponse response = context.getBean(AiGateway.class).chat(request());
              assertThat(response.finishReason()).isEqualTo("stop");
              assertThat(response.toolCalls()).isEmpty();
              var conditions = ConditionOutput.parse(response.content());
              assertThat(conditions.category()).isEqualTo(ItemCategory.DISPLAY_RACK);
              assertThat(conditions.quantity()).isEqualTo(3);
              assertThat(conditions.vehicleType()).isEqualTo(VehicleType.SEDAN);
              assertThat(conditions.purpose())
                  .isNotBlank()
                  .containsPattern("[A-Za-z]")
                  .doesNotContainPattern("[가-힣]");
            });
  }

  private static ChatRequest request() {
    return new ChatRequest(
        List.of(
            new ChatRequest.SystemMessage(ConditionPrompt.SYSTEM),
            new ChatRequest.UserMessage(
                historicalBody().path("messages").path(1).path("content").stringValue(),
                List.of())),
        new ChatRequest.ResponseSchema("pickup_conditions", ConditionPrompt.SCHEMA),
        List.of(),
        "minimal");
  }

  private static JsonNode serializedRequest(AiProperties properties) {
    AtomicReference<JsonNode> captured = new AtomicReference<>();
    MetaExchange exchange = mock(MetaExchange.class);
    doAnswer(
            invocation -> {
              captured.set(invocation.getArgument(1, ObjectNode.class).deepCopy());
              throw new CapturedRequest();
            })
        .when(exchange)
        .postJson(eq(Capability.SPARK), any(ObjectNode.class));
    try {
      new MetaAiGateway(properties, exchange).chat(request());
      throw new AssertionError("Expected request capture before transport");
    } catch (CapturedRequest expected) {
      return captured.get();
    }
  }

  private static JsonNode historicalBody() {
    try {
      return JSON.readTree(
              Files.readAllBytes(Path.of("../fixtures/replay/spark/" + ORIGINAL + ".json")))
          .path("request")
          .path("body");
    } catch (java.io.IOException failure) {
      throw new java.io.UncheckedIOException(failure);
    }
  }

  private static final class CapturedRequest extends RuntimeException {}
}
