package com.afterbooth.ai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class AiTestSupport {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final Path SPIKE_FIXTURES = Path.of("../fixtures/spike");
  static final Path SAMPLES = Path.of("../spike/samples");

  private AiTestSupport() {}

  static AiProperties properties(AiMode mode, String baseUrl, Path fixturesDir, String apiKey) {
    return properties(mode, baseUrl, fixturesDir, apiKey, Duration.ZERO);
  }

  static AiProperties properties(
      AiMode mode, String baseUrl, Path fixturesDir, String apiKey, Duration fallbackCooldown) {
    return new AiProperties(
        mode,
        fixturesDir.toString(),
        baseUrl,
        apiKey,
        new AiProperties.PerCapability("muse-spark-1.3", "sam-3.1", "muse-voice-transcribe-1.0"),
        new AiProperties.PerCapability(
            "/v1/chat/completions", "/v1/responses", "/v1/asr/transcribe"),
        new AiProperties.Timeouts(
            Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5)),
        new AiProperties.Timeouts(
            Duration.ofMillis(200), Duration.ofMillis(200), Duration.ofMillis(200)),
        fallbackCooldown,
        List.of("Korean"));
  }

  static AiGateway gateway(AiMode mode, String baseUrl, Path fixturesDir, String apiKey) {
    AiProperties properties = properties(mode, baseUrl, fixturesDir, apiKey);
    return new MetaAiGateway(properties, new MetaExchange(properties));
  }

  /** The response body Meta really returned in the spike, as raw JSON text. */
  static String spikeResponse(String file) {
    return spikeExchange(file).path("response").path("body").toString();
  }

  static JsonNode spikeExchange(String file) {
    return JSON.readTree(read(SPIKE_FIXTURES.resolve(file)));
  }

  static byte[] sample(String file) {
    return read(SAMPLES.resolve(file));
  }

  static List<Path> fixtureFiles(Path fixturesDir) {
    Path replay = fixturesDir.resolve("replay");
    if (!Files.isDirectory(replay)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(replay)) {
      return files.filter(Files::isRegularFile).toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static byte[] read(Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
