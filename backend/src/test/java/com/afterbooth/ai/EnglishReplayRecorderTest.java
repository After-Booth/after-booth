package com.afterbooth.ai;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class EnglishReplayRecorderTest {
  @Test
  void budgetIncludesPreviousAttemptsAndUnresolvedReservations() {
    EnglishReplayRecorder.checkBudget(
        29, new BigDecimal("0.10"), EnglishReplayRecorder.SPARK_RESERVE);
    assertThatThrownBy(
            () ->
                EnglishReplayRecorder.checkBudget(
                    30, BigDecimal.ZERO, EnglishReplayRecorder.SAM_RESERVE))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                EnglishReplayRecorder.checkBudget(
                    1, EnglishReplayRecorder.SPARK_RESERVE, EnglishReplayRecorder.SPARK_RESERVE))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void reviewedImageHydratesBackToExactRedactedRequest() {
    var body =
        EnglishReplayManifest.recordedRequest(
            "d65a99d1191a0ef88b8c7e5c859570facfc128fceb16a8e61ea7aea65c8a2805");
    EnglishReplayManifest.validate(Capability.SPARK, body);
    assertThat(FixtureStore.redact(EnglishReplayManifest.hydrate(body))).isEqualTo(body);
  }

  @Test
  void refusesPersonalInputOrOtherCapabilityBeforeTransport() {
    var body =
        (tools.jackson.databind.node.ObjectNode)
            EnglishReplayManifest.recordedRequest(
                    "4c5fb1f9cfcbd4263b79d4c0f71865b7b42eb1e67e0eb7d9351e63dc92ccb711")
                .deepCopy();
    ((tools.jackson.databind.node.ObjectNode) body.path("messages").path(1))
        .put("content", "someone@example.test");
    assertThatThrownBy(() -> EnglishReplayManifest.validate(Capability.SPARK, body))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EnglishReplayManifest.validate(Capability.TRANSCRIBE, body))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
