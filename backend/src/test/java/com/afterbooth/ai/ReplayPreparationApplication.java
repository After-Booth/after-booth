package com.afterbooth.ai;

import com.afterbooth.AfterboothApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Test-classpath-only replay diagnostics; never records responses or calls a provider. */
public final class ReplayPreparationApplication {
  public static void main(String[] args) {
    if (!"replay".equals(System.getenv("AI_MODE")))
      throw new IllegalStateException("Preparation requires replay");
    SpringApplication application =
        new SpringApplication(AfterboothApplication.class, CaptureConfiguration.class);
    application.setDefaultProperties(java.util.Map.of("afterbooth.replay.preparation", "true"));
    application.run(args);
  }

  @TestConfiguration(proxyBeanMethods = false)
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      name = "afterbooth.replay.preparation",
      havingValue = "true")
  static class CaptureConfiguration {
    @Bean
    @Primary
    MetaExchange preparationExchange(AiProperties properties, JdbcTemplate jdbc) {
      return new CaptureExchange(properties, jdbc);
    }
  }

  static final class CaptureExchange extends MetaExchange {
    private final AiProperties properties;
    private final JdbcTemplate jdbc;
    private final Path directory;
    private final String race = System.getenv().getOrDefault("AFTERBOOTH_REPLAY_RACE_BRANCH", "");
    private final CountDownLatch finishes = new CountDownLatch(2);
    private final AtomicInteger vanStarts = new AtomicInteger();

    CaptureExchange(AiProperties properties, JdbcTemplate jdbc) {
      super(properties);
      if (properties.mode() != AiMode.REPLAY) throw new IllegalStateException("Replay only");
      this.properties = properties;
      this.jdbc = jdbc;
      directory = Path.of(System.getenv("AFTERBOOTH_REPLAY_CAPTURE_DIR")).toAbsolutePath();
      if (!directory.startsWith(Path.of(System.getProperty("java.io.tmpdir")))
          && !directory.startsWith(Path.of("/private/tmp")))
        throw new IllegalArgumentException("Capture directory must be private temporary storage");
    }

    @Override
    JsonNode postJson(Capability capability, ObjectNode body) {
      JsonNode redacted = FixtureStore.redact(body);
      boolean van = capability == Capability.SPARK && EnglishReplayManifest.vanAllocation(redacted);
      if (van
          && body.path("messages").size() == 2
          && vanStarts.incrementAndGet() == 2
          && race.equals("stock-0-after-commit")) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (jdbc.queryForObject("select available_qty from item where id=2", Integer.class)
            != 0) {
          if (System.nanoTime() > until)
            throw new IllegalStateException("Race scheduling timed out");
          try {
            Thread.sleep(10);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted");
          }
        }
      }
      try {
        JsonNode result = super.postJson(capability, body);
        if (van
            && race.equals("simultaneous-reject")
            && result.at("/choices/0/finish_reason").asText().equals("stop")) {
          finishes.countDown();
          try {
            if (!finishes.await(20, TimeUnit.SECONDS))
              throw new IllegalStateException("Race scheduling timed out");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted");
          }
        }
        return result;
      } catch (MissingFixtureException missing) {
        EnglishReplayManifest.validate(capability, redacted);
        ObjectNode manifest = JsonMapper.builder().build().createObjectNode();
        manifest.put("capability", capability.name());
        manifest.put("key", missing.key());
        manifest.put("path", properties.paths().of(capability));
        manifest.set("body", redacted);
        try {
          Files.createDirectories(directory);
          Path file = directory.resolve(missing.key() + ".json");
          if (!Files.exists(file))
            Files.writeString(file, manifest.toString(), StandardOpenOption.CREATE_NEW);
        } catch (java.nio.file.FileAlreadyExistsException sameRequest) {
          // Concurrent equivalent synthetic requests share one manifest.
        } catch (java.io.IOException e) {
          throw new java.io.UncheckedIOException(e);
        }
        throw missing;
      }
    }
  }
}
