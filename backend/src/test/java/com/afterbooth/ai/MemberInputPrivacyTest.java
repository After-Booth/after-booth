package com.afterbooth.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.afterbooth.global.security.MemberWorkRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemberInputPrivacyTest {
  @TempDir Path fixtures;

  @Test
  void memberOwnedRecordingFailsBeforeTransportOrFixtureCreation() throws Exception {
    var registry = new MemberWorkRegistry();
    var properties =
        AiTestSupport.properties(
            AiMode.RECORD, "http://127.0.0.1:1", fixtures, UUID.randomUUID().toString());
    var exchange = new MetaExchange(properties);
    assertThatThrownBy(
            () ->
                registry.call(
                    1,
                    () ->
                        exchange.postJson(Capability.SPARK, AiTestSupport.JSON.createObjectNode())))
        .isInstanceOf(AiCallException.class)
        .hasMessageContaining("recording is disabled");
    try (var files = Files.list(fixtures)) {
      assertThat(files.count()).isZero();
    }
  }

  @Test
  void providerErrorBodyAndJsonParserCauseAreNotRetained() {
    try (var server = new StubMetaServer()) {
      var properties =
          AiTestSupport.properties(
              AiMode.LIVE, server.baseUrl(), fixtures, UUID.randomUUID().toString());
      var exchange = new MetaExchange(properties);
      server.respond(400, UUID.randomUUID().toString());
      assertThatThrownBy(
              () -> exchange.postJson(Capability.SPARK, AiTestSupport.JSON.createObjectNode()))
          .isInstanceOf(AiCallException.class)
          .hasMessageContaining("HTTP 400")
          .hasNoCause();
      server.respond(200, UUID.randomUUID().toString());
      assertThatThrownBy(
              () -> exchange.postJson(Capability.SPARK, AiTestSupport.JSON.createObjectNode()))
          .isInstanceOf(AiCallException.class)
          .hasMessageContaining("response is not JSON")
          .hasNoCause();
    }
  }
}
