package com.afterbooth.intake.pipeline;

import com.afterbooth.ai.AiGateway;
import com.afterbooth.ai.ChatRequest;
import com.afterbooth.ai.ChatResponse;
import com.afterbooth.ai.Image;
import com.afterbooth.ai.SamBoxSegmenter;
import com.afterbooth.global.domain.BoundingBox;
import com.afterbooth.intake.domain.DraftCategory;
import com.afterbooth.inventory.domain.Condition;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class IntakePipeline {
  private final AiGateway gateway;
  private final JsonMapper json;
  private final String reasoningEffort;
  private final BigDecimal confidenceThreshold;
  private final double samMinScore;

  public IntakePipeline(
      AiGateway gateway,
      JsonMapper json,
      @Value("${afterbooth.ai.intake.reasoning-effort}") String reasoningEffort,
      @Value("${afterbooth.ai.intake.confidence-threshold}") BigDecimal confidenceThreshold,
      @Value("${afterbooth.ai.sam.min-score}") double samMinScore) {
    if (confidenceThreshold.compareTo(BigDecimal.ZERO) < 0
        || confidenceThreshold.compareTo(BigDecimal.ONE) > 0
        || !Double.isFinite(samMinScore)
        || samMinScore < 0
        || samMinScore > 1) {
      throw new IllegalArgumentException(
          "Intake confidence thresholds must be between zero and one");
    }
    this.gateway = gateway;
    this.json = json;
    this.reasoningEffort = reasoningEffort;
    this.confidenceThreshold = confidenceThreshold;
    this.samMinScore = samMinScore;
  }

  public List<EstimatedLine> estimate(IntakePhoto photo) {
    List<ChatRequest.Message> messages = new ArrayList<>();
    messages.add(
        new ChatRequest.SystemMessage(
            """
        Identify reusable booth equipment: display racks, storage bins and folding tables.
        Return one line per category, counting visible units with one normalized x/y/width/height
        box per unit (origin top-left). Ignore unrelated objects. Use UNKNOWN for equipment whose
        category is uncertain. Estimate per-unit size in centimeters (null if indeterminate),
        condition and confidence. Return only the JSON required by the response schema.
        """));
    messages.add(
        new ChatRequest.UserMessage(
            "Create an inventory draft from this photo.",
            List.of(new Image(photo.bytes(), photo.mediaType()))));
    for (int attempt = 0; attempt < 2; attempt++) {
      ChatResponse response =
          gateway.chat(
              new ChatRequest(
                  messages,
                  new ChatRequest.ResponseSchema("intake_estimate", EstimateSchema.JSON),
                  List.of(),
                  reasoningEffort));
      try {
        return lines(EstimateSchema.parse(json, response.content()));
      } catch (IllegalArgumentException | tools.jackson.core.JacksonException invalid) {
        if (attempt == 1)
          throw new IllegalArgumentException("Spark output failed validation twice", invalid);
        messages.add(
            new ChatRequest.AssistantMessage(
                response.content() == null ? "" : response.content(), List.of()));
        messages.add(
            new ChatRequest.UserMessage(
                "The answer failed schema validation. Correct types, enums, bounds and required fields. "
                    + "Use one line per category and exactly count boxes, all inside the image. Return corrected JSON only.",
                List.of()));
      }
    }
    throw new IllegalStateException("Unreachable estimate attempt");
  }

  private List<EstimatedLine> lines(JsonNode result) {
    List<EstimatedLine> lines = new ArrayList<>();
    for (JsonNode line : result.path("lines")) {
      if (line.path("count").asInt() == 0) continue;
      DraftCategory category = DraftCategory.valueOf(line.path("category").asText());
      BigDecimal confidence = new BigDecimal(line.path("confidence").asText());
      List<BoundingBox> boxes = new ArrayList<>();
      for (JsonNode box : line.path("boxes"))
        boxes.add(
            new BoundingBox(
                box.path("x").asDouble(),
                box.path("y").asDouble(),
                box.path("width").asDouble(),
                box.path("height").asDouble()));
      JsonNode size = line.path("sizeCm");
      lines.add(
          new EstimatedLine(
              category,
              line.path("count").asInt(),
              boxes,
              size.isNull() ? null : size.path("width").asInt(),
              size.isNull() ? null : size.path("depth").asInt(),
              size.isNull() ? null : size.path("height").asInt(),
              Condition.valueOf(line.path("condition").asText()),
              confidence,
              category == DraftCategory.UNKNOWN || confidence.compareTo(confidenceThreshold) < 0));
    }
    return List.copyOf(lines);
  }

  public List<EstimatedLine> segment(IntakePhoto photo, List<EstimatedLine> lines) {
    List<BoundingBox> boxes = lines.stream().flatMap(line -> line.boxes().stream()).toList();
    if (boxes.isEmpty()) return lines;
    Map<Integer, BoundingBox> segmented =
        SamBoxSegmenter.segment(
            gateway, new Image(photo.bytes(), photo.mediaType()), boxes, samMinScore);
    List<EstimatedLine> result = new ArrayList<>();
    int index = 0;
    for (EstimatedLine line : lines) {
      List<BoundingBox> refined = new ArrayList<>();
      boolean incomplete = false;
      for (BoundingBox original : line.boxes()) {
        BoundingBox tight = segmented.get(index++);
        incomplete |= tight == null;
        refined.add(tight == null ? original : tight);
      }
      result.add(line.segmented(refined, incomplete));
    }
    return List.copyOf(result);
  }
}
