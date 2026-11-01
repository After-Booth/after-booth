package com.afterbooth.ai;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A local stand-in for the Meta API on 127.0.0.1 that records every request it receives. */
final class StubMetaServer implements AutoCloseable {

  record Received(String method, String path, Headers headers, byte[] body) {

    /** The first value of a request header, or null when it was not sent. */
    String header(String name) {
      return headers.getFirst(name);
    }
  }

  private final HttpServer server;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private volatile int status = 200;
  private volatile String response = "{}";
  private volatile Duration delay = Duration.ZERO;
  private volatile boolean stallBody;
  private final CountDownLatch disconnected = new CountDownLatch(1);

  StubMetaServer() {
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext("/", this::handle);
    server.start();
  }

  StubMetaServer respond(int status, String response) {
    this.status = status;
    this.response = response;
    return this;
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  StubMetaServer delay(Duration delay) {
    this.delay = delay;
    return this;
  }

  StubMetaServer stallBody() {
    this.stallBody = true;
    return this;
  }

  boolean awaitDisconnect() throws InterruptedException {
    return disconnected.await(3, TimeUnit.SECONDS);
  }

  List<Received> received() {
    return received;
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    received.add(
        new Received(
            exchange.getRequestMethod(),
            exchange.getRequestURI().getPath(),
            exchange.getRequestHeaders(),
            exchange.getRequestBody().readAllBytes()));
    if (stallBody) {
      exchange.sendResponseHeaders(200, 16 * 1024 * 1024);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write('{');
        out.flush();
        Thread.sleep(Duration.ofMillis(500));
        byte[] chunk = new byte[64 * 1024];
        for (int i = 0; i < 255; i++) out.write(chunk);
        out.write(chunk, 0, chunk.length - 1);
      } catch (IOException e) {
        disconnected.countDown();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return;
    }
    try {
      Thread.sleep(delay);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    byte[] body = response.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
}
