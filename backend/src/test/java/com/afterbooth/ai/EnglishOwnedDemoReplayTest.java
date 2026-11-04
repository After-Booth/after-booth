package com.afterbooth.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Replays every newly recorded exact demo request through the real gateway with no key. */
class EnglishOwnedDemoReplayTest {
  @Test
  void replaysActualMetaRecordingsWithoutTransportOrMemberRecording() throws Exception {
    var index =
        EnglishReplayManifest.JSON.readTree(
            Files.readString(Path.of("../fixtures/english-owned-demo.json")));
    var properties =
        AiTestSupport.properties(AiMode.REPLAY, "https://api.meta.ai", Path.of("../fixtures"), "");
    var store = new FixtureStore(Path.of("../fixtures"), EnglishReplayManifest.JSON);
    assertThat(index.path("requests").size()).isEqualTo(12);
    for (var entry : index.path("requests")) {
      Capability capability = Capability.valueOf(entry.path("capability").asText());
      String key = entry.path("key").asText();
      var fixture =
          EnglishReplayManifest.JSON.readTree(Files.readString(store.file(capability, key)));
      var request = fixture.path("request");
      var body = request.path("body");
      assertThat(store.key(request.path("method").asText(), request.path("path").asText(), body))
          .isEqualTo(key);
      EnglishReplayManifest.validate(capability, body);
      assertThat(
              EnglishReplayManifest.invokeGateway(
                  properties,
                  capability,
                  (tools.jackson.databind.node.ObjectNode) EnglishReplayManifest.hydrate(body),
                  body))
          .isEqualTo(fixture.at("/response/body"));
    }
  }
}
