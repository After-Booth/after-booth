package com.afterbooth.ai;

import com.afterbooth.ai.ChatRequest.AssistantMessage;
import com.afterbooth.ai.ChatRequest.SystemMessage;
import com.afterbooth.ai.ChatRequest.ToolMessage;
import com.afterbooth.ai.ChatRequest.UserMessage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Builds the Meta request shapes confirmed in spike/REPORT.md §2 and reads their responses. */
@Component
class MetaAiGateway implements AiGateway {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final AiProperties properties;
  private final MetaExchange exchange;

  MetaAiGateway(AiProperties properties, MetaExchange exchange) {
    this.properties = properties;
    this.exchange = exchange;
  }

  @Override
  public ChatResponse chat(ChatRequest request) {
    ObjectNode body = NODES.objectNode();
    body.put("model", properties.models().spark());
    ArrayNode messages = body.putArray("messages");
    request.messages().forEach(message -> messages.add(message(message)));
    if (!request.tools().isEmpty()) {
      ArrayNode tools = body.putArray("tools");
      for (ChatRequest.Tool tool : request.tools()) {
        ObjectNode function = tools.addObject().put("type", "function").putObject("function");
        function.put("name", tool.name());
        function.put("description", tool.description());
        function.set("parameters", jsonObject(tool.parametersJson()));
      }
    }
    if (request.responseSchema() != null) {
      ObjectNode schema =
          body.putObject("response_format").put("type", "json_schema").putObject("json_schema");
      schema.put("name", request.responseSchema().name());
      schema.set("schema", jsonObject(request.responseSchema().schemaJson()));
    }
    if (request.reasoningEffort() != null) {
      body.put("reasoning_effort", request.reasoningEffort());
    }
    return chatResponse(exchange.postJson(Capability.SPARK, body));
  }

  @Override
  public List<SamSegment> segment(SamRequest request) {
    ObjectNode body = NODES.objectNode();
    body.put("model", properties.models().sam());
    ArrayNode content =
        body.putArray("input")
            .addObject()
            .put("type", "message")
            .put("role", "user")
            .putArray("content");
    content.addObject().put("type", "input_text").put("text", prompt(request.prompt()));
    content.addObject().put("type", "input_image").put("image_url", request.image().dataUrl());
    body.putObject("metadata").put("include_confidence", "true").put("mask_encoding", "one_bit");

    JsonNode response = exchange.postJson(Capability.SAM, body);
    String status = text(response.path("status"));
    if (!"completed".equals(status)) {
      throw new AiCallException(Capability.SAM, "response status is " + status);
    }
    JsonNode output = response.path("output").path(0).path("content").path(0).path("text");
    if (!output.isString()) {
      throw new AiCallException(Capability.SAM, "response has no output text");
    }
    return SamOutput.parse(output.stringValue());
  }

  @Override
  public Transcription transcribe(byte[] wav) {
    ObjectNode request = NODES.objectNode();
    request.put("model", properties.models().transcribe());
    request.put("audioEncoding", "WAV");
    request.put("mode", "PUSH_TO_TALK");
    ArrayNode languageBias = request.putArray("languageBias");
    properties.transcribeLanguageBias().forEach(languageBias::add);

    JsonNode response = exchange.postAudio(Capability.TRANSCRIBE, request, wav);
    JsonNode transcript = response.path("transcript");
    if (!transcript.isString()) {
      throw new AiCallException(Capability.TRANSCRIBE, "response has no transcript");
    }
    return new Transcription(transcript.stringValue(), response.path("audioDurationMs").asLong(0));
  }

  private static ObjectNode message(ChatRequest.Message message) {
    ObjectNode node = NODES.objectNode();
    switch (message) {
      case SystemMessage system -> node.put("role", "system").put("content", system.text());
      case UserMessage user -> {
        node.put("role", "user");
        // Text alone goes as a plain string, the shape the spike confirmed for text-only calls
        if (user.images().isEmpty()) {
          node.put("content", user.text());
        } else {
          ArrayNode content = node.putArray("content");
          content.addObject().put("type", "text").put("text", user.text());
          for (Image image : user.images()) {
            content
                .addObject()
                .put("type", "image_url")
                .putObject("image_url")
                .put("url", image.dataUrl());
          }
        }
      }
      case AssistantMessage assistant -> {
        node.put("role", "assistant").put("content", assistant.text());
        if (!assistant.toolCalls().isEmpty()) {
          ArrayNode calls = node.putArray("tool_calls");
          for (ToolCall call : assistant.toolCalls()) {
            calls
                .addObject()
                .put("id", call.id())
                .put("type", "function")
                .putObject("function")
                .put("name", call.name())
                .put("arguments", call.argumentsJson());
          }
        }
      }
      case ToolMessage tool ->
          node.put("role", "tool")
              .put("tool_call_id", tool.toolCallId())
              .put("content", tool.content());
    }
    return node;
  }

  private static ChatResponse chatResponse(JsonNode body) {
    JsonNode choice = body.path("choices").path(0);
    if (!choice.isObject()) {
      throw new AiCallException(Capability.SPARK, "response has no choices");
    }
    JsonNode message = choice.path("message");
    if (!message.isObject()) {
      throw new AiCallException(Capability.SPARK, "response has no assistant message");
    }
    List<ToolCall> toolCalls = new ArrayList<>();
    JsonNode calls = message.path("tool_calls");
    if (!calls.isMissingNode() && !calls.isNull() && !calls.isArray()) {
      throw new AiCallException(Capability.SPARK, "response tool_calls is not an array");
    }
    Set<String> callIds = new HashSet<>();
    for (JsonNode call : calls) {
      JsonNode function = call.path("function");
      String id = requiredText(call.path("id"));
      String name = requiredText(function.path("name"));
      String arguments = requiredText(function.path("arguments"));
      if (!"function".equals(text(call.path("type"))) || !callIds.add(id)) {
        throw new AiCallException(Capability.SPARK, "response has an invalid tool call");
      }
      toolCalls.add(new ToolCall(id, name, arguments));
    }
    JsonNode usage = body.path("usage");
    return new ChatResponse(
        text(message.path("content")),
        toolCalls,
        text(choice.path("finish_reason")),
        new ChatResponse.Usage(
            usage.path("prompt_tokens").asInt(0),
            usage.path("prompt_tokens_details").path("cached_tokens").asInt(0),
            usage.path("completion_tokens").asInt(0),
            usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0)));
  }

  /** SAM box-prompt grammar: {@code <0f>} then {@code n<|box;...|>} per box, comma separated. */
  private static String prompt(SamRequest.Prompt prompt) {
    return switch (prompt) {
      case SamRequest.Concept concept -> concept.phrase();
      case SamRequest.Boxes boxes ->
          "<0f>"
              + IntStream.range(0, boxes.boxes().size())
                  .mapToObj(
                      i -> {
                        PixelBox box = boxes.boxes().get(i);
                        return "%d<|box;x1=%d;y1=%d;x2=%d;y2=%d;w=%d;h=%d|>"
                            .formatted(
                                i + 1,
                                box.x1(),
                                box.y1(),
                                box.x2(),
                                box.y2(),
                                boxes.imageWidth(),
                                boxes.imageHeight());
                      })
                  .collect(Collectors.joining(","));
    };
  }

  private static JsonNode jsonObject(String json) {
    try {
      JsonNode node = JSON.readTree(json);
      if (node.isObject()) {
        return node;
      }
    } catch (JacksonException e) {
      throw new IllegalArgumentException("Not a JSON object: " + json, e);
    }
    throw new IllegalArgumentException("Not a JSON object: " + json);
  }

  private static String text(JsonNode node) {
    return node.isString() ? node.stringValue() : null;
  }

  private static String requiredText(JsonNode node) {
    String value = text(node);
    if (value == null || value.isBlank()) {
      throw new AiCallException(Capability.SPARK, "response has a malformed tool call");
    }
    return value;
  }
}
