package com.afterbooth.ai;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Applies live fallback, incremental recording and replay at the shared exchange seam. */
@Component
class MetaExchange {

  private static final String METHOD = "POST";
  private static final int ERROR_BODY_LIMIT = 500;
  private static final Logger log = LoggerFactory.getLogger(MetaExchange.class);

  private final AiProperties properties;
  private final JsonMapper json = JsonMapper.builder().build();
  private final FixtureStore fixtures;
  private final Map<Capability, RestClient> clients;
  private final Map<String, CompletableFuture<JsonNode>> recordings = new ConcurrentHashMap<>();
  private volatile long liveSkippedUntil = System.nanoTime();

  MetaExchange(AiProperties properties) {
    this.properties = properties;
    this.fixtures = new FixtureStore(Path.of(properties.fixturesDir()), json);
    this.clients = properties.mode() == AiMode.REPLAY ? Map.of() : clients(properties);
  }

  JsonNode postJson(Capability capability, ObjectNode body) {
    return exchange(
        capability,
        FixtureStore.redact(body),
        request ->
            request.contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsBytes(body)));
  }

  /**
   * Multipart with a JSON {@code request} part and an {@code audio} WAV part, built by hand as one
   * buffered body with a Content-Length. Spring's multipart writer streamed it chunked, with part
   * Content-Length headers and {@code ;boundary=} without a space, and Transcribe answered "400
   * Malformed multipart body"; this byte layout is the one the spike's working request used.
   */
  JsonNode postAudio(Capability capability, ObjectNode requestPart, byte[] wav) {
    ObjectNode redacted = JsonNodeFactory.instance.objectNode();
    redacted.set("request", requestPart);
    redacted.set("audio", FixtureStore.digest(wav));
    return exchange(
        capability,
        redacted,
        request -> {
          String boundary = "----afterbooth-" + UUID.randomUUID();
          return request
              .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
              .body(audioBody(boundary, json.writeValueAsBytes(requestPart), wav));
        });
  }

  private JsonNode exchange(
      Capability capability,
      JsonNode redactedBody,
      UnaryOperator<RestClient.RequestBodySpec> withBody) {
    String path = properties.paths().of(capability);
    String key = fixtures.key(METHOD, path, redactedBody);
    long started = System.nanoTime();
    String outcome = "failed";
    try {
      if (properties.mode() == AiMode.REPLAY) {
        JsonNode body = fixtures.readResponseBody(capability, key);
        outcome = "replay";
        return body;
      }
      if (properties.mode() == AiMode.RECORD) {
        ExchangeResult result = record(capability, key, path, redactedBody, withBody);
        outcome = result.outcome();
        return result.body();
      }
      if (fixtures.exists(capability, key)) {
        JsonNode fallback = fixtures.readResponseBody(capability, key);
        // A multi-turn step must not wait one window per turn once the network is known to be down
        if (System.nanoTime() - liveSkippedUntil < 0) {
          outcome = "fallback";
          return fallback;
        }
        FutureTask<JsonNode> call =
            new FutureTask<>(() -> parse(capability, send(capability, path, withBody)));
        Thread.startVirtualThread(call);
        try {
          JsonNode body =
              call.get(properties.fallbackAfter().of(capability).toNanos(), TimeUnit.NANOSECONDS);
          outcome = "live";
          return body;
        } catch (TimeoutException e) {
          log.warn("AI fallback capability={} key={} reason=window expired", capability, key);
        } catch (ExecutionException e) {
          if (!(e.getCause() instanceof AiCallException failure)) throw propagate(e.getCause());
          log.warn(
              "AI fallback capability={} key={} reason={}",
              capability,
              key,
              scrub(failure.getMessage()).replace('\r', ' ').replace('\n', ' '));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AiCallException(capability, null, "call interrupted", e);
        } finally {
          call.cancel(true);
        }
        liveSkippedUntil = System.nanoTime() + properties.fallbackCooldown().toNanos();
        outcome = "fallback";
        return fallback;
      }
      JsonNode body = parse(capability, send(capability, path, withBody));
      outcome = "live";
      return body;
    } finally {
      log.info(
          "AI exchange capability={} mode={} outcome={} elapsedMs={}",
          capability,
          properties.mode(),
          outcome,
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
  }

  private ExchangeResult record(
      Capability capability,
      String key,
      String path,
      JsonNode redactedBody,
      UnaryOperator<RestClient.RequestBodySpec> withBody) {
    String flightKey = capability + ":" + key;
    CompletableFuture<JsonNode> flight = new CompletableFuture<>();
    CompletableFuture<JsonNode> existing = recordings.putIfAbsent(flightKey, flight);
    if (existing != null) {
      try {
        return new ExchangeResult(existing.get().deepCopy(), "reused");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AiCallException(capability, null, "recording wait interrupted", e);
      } catch (ExecutionException e) {
        throw propagate(e.getCause());
      }
    }
    try {
      if (fixtures.exists(capability, key)) {
        JsonNode body = fixtures.readResponseBody(capability, key);
        flight.complete(body);
        return new ExchangeResult(body.deepCopy(), "reused");
      }
      HttpResult result = send(capability, path, withBody);
      JsonNode body = parse(capability, result);
      fixtures.write(capability, key, METHOD, path, redactedBody, result.status(), body);
      flight.complete(body);
      return new ExchangeResult(body.deepCopy(), "recorded");
    } catch (RuntimeException | Error e) {
      flight.completeExceptionally(e);
      throw e;
    } finally {
      recordings.remove(flightKey, flight);
    }
  }

  private static RuntimeException propagate(Throwable cause) {
    if (cause instanceof RuntimeException runtime) return runtime;
    if (cause instanceof Error error) throw error;
    return new IllegalStateException("AI exchange failed", cause);
  }

  private HttpResult send(
      Capability capability, String path, UnaryOperator<RestClient.RequestBodySpec> withBody) {
    try {
      return withBody
          .apply(clients.get(capability).post().uri(path).accept(MediaType.APPLICATION_JSON))
          .exchange(
              (request, response) ->
                  new HttpResult(
                      response.getStatusCode().value(), response.getBody().readAllBytes()));
    } catch (RestClientException e) {
      throw new AiCallException(capability, null, scrub(String.valueOf(e.getMessage())), e);
    }
  }

  private JsonNode parse(Capability capability, HttpResult result) {
    String text = scrub(new String(result.body(), StandardCharsets.UTF_8));
    String excerpt = text.length() > ERROR_BODY_LIMIT ? text.substring(0, ERROR_BODY_LIMIT) : text;
    if (result.status() < 200 || result.status() >= 300) {
      throw new AiCallException(
          capability, result.status(), "HTTP " + result.status() + ": " + excerpt, null);
    }
    try {
      return json.readTree(result.body());
    } catch (JacksonException e) {
      throw new AiCallException(capability, result.status(), "response is not JSON: " + excerpt, e);
    }
  }

  private String scrub(String message) {
    String key = properties.apiKey();
    return key == null || key.isBlank() ? message : message.replace(key, "<redacted>");
  }

  private static byte[] audioBody(String boundary, byte[] requestJson, byte[] wav) {
    ByteArrayOutputStream body = new ByteArrayOutputStream(wav.length + 512);
    body.writeBytes(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"request\""
                + "\r\nContent-Type: application/json\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    body.writeBytes(requestJson);
    body.writeBytes(
        ("\r\n--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"audio.wav\""
                + "\r\nContent-Type: audio/wav\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    body.writeBytes(wav);
    body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return body.toByteArray();
  }

  private static Map<Capability, RestClient> clients(AiProperties properties) {
    Map<Capability, RestClient> clients = new EnumMap<>(Capability.class);
    for (Capability capability : Capability.values()) {
      // Non-streaming calls answer in one piece, so the read timeout bounds the whole call
      SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
      factory.setConnectTimeout(properties.timeouts().of(capability));
      factory.setReadTimeout(properties.timeouts().of(capability));
      clients.put(
          capability,
          RestClient.builder()
              .baseUrl(properties.baseUrl())
              .requestFactory(factory)
              .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
              .build());
    }
    return clients;
  }

  private record HttpResult(int status, byte[] body) {}

  private record ExchangeResult(JsonNode body, String outcome) {}
}
