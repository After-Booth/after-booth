package com.afterbooth.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code afterbooth.ai.*}. Every value comes from configuration; nothing is guessed in code. */
@Validated
@ConfigurationProperties("afterbooth.ai")
record AiProperties(
    @NotNull AiMode mode,
    @NotBlank String fixturesDir,
    @NotBlank String baseUrl,
    String apiKey,
    @NotNull @Valid PerCapability models,
    @NotNull @Valid PerCapability paths,
    @NotNull @Valid Timeouts timeouts,
    @NotNull @Valid Timeouts fallbackAfter,
    @NotNull Duration fallbackCooldown,
    @NotEmpty List<String> transcribeLanguageBias) {

  AiProperties {
    if (mode != null && mode != AiMode.REPLAY && (apiKey == null || apiKey.isBlank())) {
      throw new IllegalStateException(
          "AI_MODE="
              + mode
              + " calls the Meta API and needs META_API_KEY; only replay runs without a key");
    }
    if (baseUrl != null && (baseUrl.endsWith("/") || baseUrl.endsWith("/v1"))) {
      throw new IllegalStateException(
          "META_BASE_URL must not end with / or /v1 because the endpoint paths carry /v1: "
              + baseUrl);
    }
  }

  // The generated toString would print the key into logs and startup failure reports
  @Override
  public String toString() {
    return "AiProperties[mode="
        + mode
        + ", fixturesDir="
        + fixturesDir
        + ", baseUrl="
        + baseUrl
        + ", apiKey="
        + (apiKey == null || apiKey.isBlank() ? "<empty>" : "<redacted>")
        + ", models="
        + models
        + ", paths="
        + paths
        + ", timeouts="
        + timeouts
        + ", fallbackAfter="
        + fallbackAfter
        + ", fallbackCooldown="
        + fallbackCooldown
        + ", transcribeLanguageBias="
        + transcribeLanguageBias
        + "]";
  }

  record PerCapability(@NotBlank String spark, @NotBlank String sam, @NotBlank String transcribe) {

    String of(Capability capability) {
      return switch (capability) {
        case SPARK -> spark;
        case SAM -> sam;
        case TRANSCRIBE -> transcribe;
      };
    }
  }

  record Timeouts(@NotNull Duration spark, @NotNull Duration sam, @NotNull Duration transcribe) {

    Duration of(Capability capability) {
      return switch (capability) {
        case SPARK -> spark;
        case SAM -> sam;
        case TRANSCRIBE -> transcribe;
      };
    }
  }
}
