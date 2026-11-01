package com.afterbooth.ai;

import com.afterbooth.global.domain.BoundingBox;
import com.afterbooth.inventory.domain.ItemCategory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class HandoverPhotoCounter {
  private static final String SCHEMA =
      """
      {"type":"object","additionalProperties":false,"required":["count","boxes"],"properties":{
        "count":{"type":"integer","minimum":0,"maximum":2147483647},
        "boxes":{"type":"array","items":{"type":"object","additionalProperties":false,
          "required":["x","y","width","height"],"properties":{
            "x":{"type":"number","minimum":0,"maximum":1},
            "y":{"type":"number","minimum":0,"maximum":1},
            "width":{"type":"number","minimum":0,"maximum":1},
            "height":{"type":"number","minimum":0,"maximum":1}}}}}}
      """;

  private final AiGateway gateway;
  private final JsonMapper json;
  private final String reasoningEffort;
  private final double samMinScore;

  public HandoverPhotoCounter(
      AiGateway gateway,
      JsonMapper json,
      @Value("${afterbooth.ai.handover.reasoning-effort:minimal}") String reasoningEffort,
      @Value("${afterbooth.ai.sam.min-score}") double samMinScore) {
    if (!Double.isFinite(samMinScore) || samMinScore < 0 || samMinScore > 1)
      throw new IllegalArgumentException("SAM minimum score must be between zero and one");
    this.gateway = gateway;
    this.json = json;
    this.reasoningEffort = reasoningEffort;
    this.samMinScore = samMinScore;
  }

  public Result count(byte[] bytes, String mediaType, ItemCategory category) {
    Image image = new Image(bytes, mediaType);
    List<ChatRequest.Message> messages = new ArrayList<>();
    messages.add(
        new ChatRequest.SystemMessage(
            """
        Count only visible reusable equipment of the requested category. Return exactly one
        normalized x/y/width/height box per unit, with origin at the top-left. Ignore all other
        categories. Every box must have positive dimensions and fit inside the photo. Return
        count zero and an empty boxes array when no requested items are visible. Return only JSON.
        """));
    messages.add(
        new ChatRequest.UserMessage("Count category " + category.name() + ".", List.of(image)));
    List<BoundingBox> boxes = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      ChatResponse response =
          gateway.chat(
              new ChatRequest(
                  messages,
                  new ChatRequest.ResponseSchema("handover_count", SCHEMA),
                  List.of(),
                  reasoningEffort));
      try {
        boxes = parse(response.content());
        break;
      } catch (IllegalArgumentException | tools.jackson.core.JacksonException invalid) {
        if (attempt == 1)
          throw new AiCallException(
              Capability.SPARK, null, "Invalid handover count after one re-ask", invalid);
        messages.add(
            new ChatRequest.AssistantMessage(
                response.content() == null ? "" : response.content(), List.of()));
        messages.add(
            new ChatRequest.UserMessage(
                "Correct the JSON schema, count and boxes. Return exactly count valid boxes and no extra fields.",
                List.of()));
      }
    }
    if (boxes == null) throw new IllegalStateException("Unreachable count attempt");
    Map<Integer, BoundingBox> segmented =
        SamBoxSegmenter.segment(gateway, image, boxes, samMinScore);
    List<BoundingBox> refined = new ArrayList<>();
    for (int index = 0; index < boxes.size(); index++)
      refined.add(segmented.getOrDefault(index, boxes.get(index)));
    return new Result(boxes.size(), refined, !boxes.isEmpty() && segmented.size() == boxes.size());
  }

  private List<BoundingBox> parse(String content) {
    if (content == null) throw new IllegalArgumentException("Missing Spark count");
    JsonNode root =
        json.reader()
            .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .readTree(content);
    if (root == null
        || !root.isObject()
        || root.size() != 2
        || !root.path("count").isIntegralNumber()
        || !root.path("count").canConvertToInt()
        || root.path("count").asInt() < 0
        || !root.path("boxes").isArray()
        || root.path("count").asInt() != root.path("boxes").size())
      throw new IllegalArgumentException("Invalid Spark count schema");
    List<BoundingBox> boxes = new ArrayList<>();
    for (JsonNode box : root.path("boxes")) {
      if (!box.isObject() || box.size() != 4)
        throw new IllegalArgumentException("Invalid Spark box schema");
      for (String key : List.of("x", "y", "width", "height")) {
        JsonNode value = box.path(key);
        if (!value.isNumber()
            || !Double.isFinite(value.asDouble())
            || value.asDouble() < 0
            || value.asDouble() > 1)
          throw new IllegalArgumentException("Invalid Spark box coordinate");
      }
      double x = box.path("x").asDouble(), y = box.path("y").asDouble();
      double width = box.path("width").asDouble(), height = box.path("height").asDouble();
      if (width <= 0 || height <= 0 || x + width > 1.000000001 || y + height > 1.000000001)
        throw new IllegalArgumentException("Spark box must fit inside photo");
      boxes.add(new BoundingBox(x, y, width, height));
    }
    return List.copyOf(boxes);
  }

  public record Result(int count, List<BoundingBox> boxes, boolean allSegmented) {
    public Result {
      boxes = List.copyOf(boxes);
    }
  }
}
