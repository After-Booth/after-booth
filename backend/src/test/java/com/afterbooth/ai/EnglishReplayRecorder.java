package com.afterbooth.ai;

import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Manual, bounded recorder for reviewed synthetic manifests. No member scope is changed. */
public final class EnglishReplayRecorder {
  static final BigDecimal SPARK_RESERVE = new BigDecimal("1.867776");
  static final BigDecimal SAM_RESERVE = new BigDecimal("0.0025");
  static final BigDecimal CAP = new BigDecimal("2.00");

  public static void main(String[] args) throws Exception {
    if (args.length < 1 || args.length > 2)
      throw new IllegalArgumentException("Provide one manifest and optional --record");
    Path file = Path.of(args[0]).toAbsolutePath();
    JsonNode manifest = EnglishReplayManifest.JSON.readTree(Files.readString(file));
    Capability capability = Capability.valueOf(manifest.path("capability").asText());
    JsonNode body = manifest.path("body");
    EnglishReplayManifest.validate(capability, body);
    String path = capability == Capability.SPARK ? "/v1/chat/completions" : "/v1/responses";
    FixtureStore store =
        new FixtureStore(EnglishReplayManifest.FIXTURES, EnglishReplayManifest.JSON);
    String key = store.key("POST", path, body);
    if (!key.equals(manifest.path("key").asText())
        || !path.equals(manifest.path("path").asText())
        || !file.getFileName().toString().equals(key + ".json"))
      throw new IllegalArgumentException("Manifest hash/path mismatch");
    ObjectNode hydrated = (ObjectNode) EnglishReplayManifest.hydrate(body);
    if (!FixtureStore.redact(hydrated).equals(body))
      throw new IllegalStateException("Hydrated request mismatch");
    System.out.println("Reviewed synthetic request " + capability + " " + key);
    if (args.length == 1) {
      System.out.println("Dry run: no provider call");
      return;
    }
    if (!args[1].equals("--record")
        || !"true".equals(System.getenv("AFTERBOOTH_ENGLISH_REPLAY_APPROVED")))
      throw new IllegalArgumentException("Separate human approval is required");
    if (store.exists(capability, key)) {
      System.out.println("Existing original fixture reused; zero new calls");
      return;
    }
    Path budget = Path.of(System.getenv("AFTERBOOTH_REPLAY_BUDGET_DIR")).toAbsolutePath();
    Files.createDirectories(budget);
    try (FileChannel channel =
            FileChannel.open(
                budget.resolve("session.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        var lock = channel.tryLock()) {
      if (lock == null)
        throw new IllegalStateException("Another recorder owns this budget session");
      Path ledgerFile = budget.resolve("ledger.json");
      ObjectNode ledger =
          Files.exists(ledgerFile)
              ? (ObjectNode) EnglishReplayManifest.JSON.readTree(Files.readString(ledgerFile))
              : EnglishReplayManifest.JSON.createObjectNode();
      var entries = ledger.withArray("attempts");
      BigDecimal used = BigDecimal.ZERO;
      for (JsonNode entry : entries)
        used = used.add(new BigDecimal(entry.path("chargedOrReserved").asText()));
      BigDecimal reserve = capability == Capability.SPARK ? SPARK_RESERVE : SAM_RESERVE;
      checkBudget(entries.size(), used, reserve);
      String keyValue = System.getenv("META_API_KEY");
      if (keyValue == null || keyValue.isBlank())
        throw new IllegalStateException("Credential not supplied by approved loader");
      ObjectNode entry = entries.addObject();
      entry
          .put("key", key)
          .put("capability", capability.name())
          .put("chargedOrReserved", reserve.toPlainString())
          .put("outcome", "reserved");
      Files.writeString(ledgerFile, ledger.toString());
      // The ledger reservation is durable before transport. Failed/uncertain attempts retain it.
      AiProperties defaults =
          AiTestSupport.properties(
              AiMode.RECORD, "https://api.meta.ai", EnglishReplayManifest.FIXTURES, keyValue);
      AiProperties properties =
          new AiProperties(
              defaults.mode(),
              defaults.fixturesDir(),
              defaults.baseUrl(),
              defaults.apiKey(),
              defaults.models(),
              defaults.paths(),
              new AiProperties.Timeouts(
                  java.time.Duration.ofSeconds(30),
                  java.time.Duration.ofSeconds(10),
                  java.time.Duration.ofSeconds(15)),
              defaults.fallbackAfter(),
              defaults.fallbackCooldown(),
              defaults.transcribeLanguageBias());
      try {
        JsonNode response =
            EnglishReplayManifest.invokeGateway(properties, capability, hydrated, body);
        BigDecimal charged = reserve;
        if (capability == Capability.SAM) charged = SAM_RESERVE;
        else if (response.path("usage").path("prompt_tokens").isIntegralNumber()
            && response.path("usage").path("completion_tokens").isIntegralNumber()) {
          long input = response.at("/usage/prompt_tokens").asLong(-1);
          long output = response.at("/usage/completion_tokens").asLong(-1);
          if (input >= 0 && input <= 1048576 && output >= 0 && output <= 131072) {
            charged =
                BigDecimal.valueOf(input)
                    .multiply(new BigDecimal("0.00000125"))
                    .add(BigDecimal.valueOf(output).multiply(new BigDecimal("0.00000425")));
            entry.put("promptTokens", input).put("completionTokens", output);
          }
        }
        entry.put("chargedOrReserved", charged.toPlainString()).put("outcome", "recorded");
        Files.writeString(ledgerFile, ledger.toString());
        System.out.println(
            "Recorded " + key + "; cost estimate/reservation $" + charged.toPlainString());
      } catch (RuntimeException error) {
        entry.put("outcome", "failed-reservation-retained");
        Files.writeString(ledgerFile, ledger.toString());
        throw new IllegalStateException("Provider attempt failed; reservation retained, no retry");
      }
    }
  }

  static void checkBudget(int attempts, BigDecimal used, BigDecimal reserve) {
    if (attempts >= 30 || used.add(reserve).compareTo(CAP) > 0)
      throw new IllegalStateException("Approved attempt/dollar limit reached before transport");
  }
}
