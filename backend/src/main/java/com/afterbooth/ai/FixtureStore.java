package com.afterbooth.ai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Replay fixtures under {@code <fixtures-dir>/replay/<capability>/<key>.json}. The key is the
 * SHA-256 of method, path and the canonical (sorted keys) request JSON in which every image and
 * audio payload is replaced by its SHA-256, so the same request always finds the same file. Spark
 * output is not deterministic, so the response cannot be part of the key. The file holds that
 * redacted request for humans and the response; never the key or headers.
 */
final class FixtureStore {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final String IMAGE_URL = "image_url";
  private static final DefaultIndenter LF_INDENT = new DefaultIndenter("  ", "\n");

  private final Path replayRoot;
  private final JsonMapper json;
  private final ObjectWriter prettyWriter;

  FixtureStore(Path fixturesDir, JsonMapper json) {
    this.replayRoot = fixturesDir.resolve("replay").toAbsolutePath().normalize();
    this.json = json;
    // LF everywhere so a fixture recorded on Windows diffs cleanly
    this.prettyWriter =
        json.writer()
            .with(
                new DefaultPrettyPrinter()
                    .withObjectIndenter(LF_INDENT)
                    .withArrayIndenter(LF_INDENT));
  }

  String key(String method, String path, JsonNode redactedBody) {
    String canonical = json.writeValueAsString(sortedKeys(redactedBody));
    return sha256((method + " " + path + "\n" + canonical).getBytes(StandardCharsets.UTF_8));
  }

  Path file(Capability capability, String key) {
    return replayRoot.resolve(capability.folder()).resolve(key + ".json");
  }

  JsonNode readResponseBody(Capability capability, String key) {
    Path file = file(capability, key);
    if (!Files.isRegularFile(file)) {
      throw new MissingFixtureException(capability, key, file);
    }
    JsonNode response;
    try {
      response = json.readTree(Files.readAllBytes(file)).path("response");
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read replay fixture " + file, e);
    } catch (JacksonException e) {
      throw new IllegalStateException("Replay fixture " + file + " is not valid JSON", e);
    }
    // A setup error, never an AiCallException: callers treat those as model failures
    int status = response.path("status").asInt(0);
    if (status < 200 || status >= 300 || !response.path("body").isObject()) {
      throw new IllegalStateException(
          "Replay fixture "
              + file
              + " needs a 2xx response.status and a response.body object; re-record it"
              + " (fixtures/README.md)");
    }
    return response.path("body");
  }

  boolean exists(Capability capability, String key) {
    return Files.isRegularFile(file(capability, key));
  }

  void write(
      Capability capability,
      String key,
      String method,
      String path,
      JsonNode redactedBody,
      int status,
      JsonNode responseBody) {
    ObjectNode fixture = NODES.objectNode();
    ObjectNode request = fixture.putObject("request");
    request.put("method", method);
    request.put("path", path);
    request.set("body", redactedBody);
    ObjectNode response = fixture.putObject("response");
    response.put("status", status);
    response.set("body", responseBody);
    Path file = file(capability, key);
    try {
      Files.createDirectories(file.getParent());
      Files.write(
          file, (prettyWriter.writeValueAsString(fixture) + "\n").getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write replay fixture " + file, e);
    }
  }

  /**
   * A copy of {@code node} with every image payload replaced by its SHA-256 and byte size. Only
   * base64 data URLs under an {@code image_url} property count, which is where the gateway puts the
   * images it encodes (Spark {@code image_url.url}, SAM {@code image_url}). User text that merely
   * looks like a data URL is part of the request and stays as it is.
   */
  static JsonNode redact(JsonNode node) {
    return redact(node, false);
  }

  private static JsonNode redact(JsonNode node, boolean underImageUrl) {
    if (underImageUrl && node.isString()) {
      return redactDataUrl(node);
    }
    if (node.isObject()) {
      ObjectNode copy = NODES.objectNode();
      node.properties()
          .forEach(
              e ->
                  copy.set(
                      e.getKey(),
                      redact(e.getValue(), underImageUrl || e.getKey().equals(IMAGE_URL))));
      return copy;
    }
    if (node.isArray()) {
      ArrayNode copy = NODES.arrayNode();
      node.forEach(element -> copy.add(redact(element, underImageUrl)));
      return copy;
    }
    return node;
  }

  private static JsonNode redactDataUrl(JsonNode node) {
    String url = node.stringValue();
    int comma = url.indexOf(',');
    if (!url.startsWith("data:") || comma < 0 || !url.substring(0, comma).endsWith(";base64")) {
      return node;
    }
    byte[] payload;
    try {
      payload = Base64.getDecoder().decode(url.substring(comma + 1));
    } catch (IllegalArgumentException notBase64) {
      return node;
    }
    ObjectNode redacted = digest(payload);
    redacted.put("redactedDataUrl", url.substring(0, comma));
    return redacted;
  }

  static ObjectNode digest(byte[] bytes) {
    ObjectNode digest = NODES.objectNode();
    digest.put("sha256", sha256(bytes));
    digest.put("bytes", bytes.length);
    return digest;
  }

  private static JsonNode sortedKeys(JsonNode node) {
    if (node.isObject()) {
      Map<String, JsonNode> sorted = new TreeMap<>();
      node.properties().forEach(e -> sorted.put(e.getKey(), sortedKeys(e.getValue())));
      ObjectNode copy = NODES.objectNode();
      sorted.forEach(copy::set);
      return copy;
    }
    if (node.isArray()) {
      ArrayNode copy = NODES.arrayNode();
      node.forEach(element -> copy.add(sortedKeys(element)));
      return copy;
    }
    return node;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every JVM ships SHA-256", e);
    }
  }
}
