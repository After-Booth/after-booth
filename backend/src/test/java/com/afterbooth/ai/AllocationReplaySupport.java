package com.afterbooth.ai;

import static com.afterbooth.ai.AiTestSupport.JSON;
import static com.afterbooth.ai.AiTestSupport.fixtureFiles;
import static com.afterbooth.ai.AiTestSupport.gateway;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Exercises allocation's real gateway serialization and fixture lookup against loopback only. */
public final class AllocationReplaySupport implements AutoCloseable {

  private final Path fixtures;
  private final StubMetaServer server = new StubMetaServer();
  private final AiGateway recording;
  private final List<ChatRequest> requests = new ArrayList<>();
  private final List<ChatResponse> responses = new ArrayList<>();
  private AiGateway replaying;
  private int replayed;
  private boolean serverClosed;

  public AllocationReplaySupport(Path fixtures) {
    this.fixtures = fixtures;
    recording = gateway(AiMode.RECORD, server.baseUrl(), fixtures, "local-test-key");
  }

  public ChatResponse record(ChatRequest request, ChatResponse response) {
    assertThat(replaying).isNull();
    server.respond(200, responseBody(response).toString());
    ChatResponse actual = recording.chat(request);
    requests.add(request);
    responses.add(actual);
    return actual;
  }

  public void startReplay() {
    assertThat(requests).isNotEmpty();
    assertThat(server.received()).hasSize(requests.size());
    assertThat(fixtureFiles(fixtures)).hasSize(requests.size());
    FixtureStore store = new FixtureStore(fixtures, JSON);
    for (StubMetaServer.Received received : server.received()) {
      String key =
          store.key(
              received.method(),
              received.path(),
              FixtureStore.redact(JSON.readTree(received.body())));
      assertThat(store.file(Capability.SPARK, key)).isRegularFile();
    }
    String closedUrl = server.baseUrl();
    close();
    replaying = gateway(AiMode.REPLAY, closedUrl, fixtures, "");
  }

  public ChatResponse replay(ChatRequest request) {
    assertThat(replaying).isNotNull();
    ChatResponse response = replaying.chat(request);
    assertThat(request).isEqualTo(requests.get(replayed));
    assertThat(response).isEqualTo(responses.get(replayed));
    replayed++;
    return response;
  }

  public void assertComplete(int turns) {
    assertThat(requests).hasSize(turns);
    assertThat(replayed).isEqualTo(turns);
    assertThat(fixtureFiles(fixtures)).hasSize(turns);
    assertThat(server.received()).hasSize(turns);
  }

  @Override
  public void close() {
    if (!serverClosed) {
      server.close();
      serverClosed = true;
    }
  }

  private static ObjectNode responseBody(ChatResponse response) {
    ObjectNode body = JSON.createObjectNode();
    ObjectNode choice = body.putArray("choices").addObject();
    choice.put("finish_reason", response.finishReason());
    ObjectNode message = choice.putObject("message");
    message.put("content", response.content());
    ArrayNode tools = message.putArray("tool_calls");
    for (ToolCall call : response.toolCalls()) {
      ObjectNode tool = tools.addObject();
      tool.put("id", call.id()).put("type", "function");
      tool.putObject("function").put("name", call.name()).put("arguments", call.argumentsJson());
    }
    return body;
  }
}
