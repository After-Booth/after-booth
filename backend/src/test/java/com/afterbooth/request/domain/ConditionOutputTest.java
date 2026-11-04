package com.afterbooth.request.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ConditionOutputTest {
  @Test
  void receiverRequiresCategoryAndVehicleOnly() {
    assertThat(
            ConditionOutput.missingFields(
                ConditionContext.RECEIVER, ConditionOutput.parse("{\"purpose\":\"\"}")))
        .containsExactly("category", "vehicleType");
    assertThat(
            ConditionOutput.missingFields(
                ConditionContext.RECEIVER,
                ConditionOutput.parse("{\"category\":\"DISPLAY_RACK\",\"purpose\":\"\"}")))
        .containsExactly("vehicleType");
    assertThat(
            ConditionOutput.missingFields(
                ConditionContext.RECEIVER,
                ConditionOutput.parse("{\"vehicleType\":\"SEDAN\",\"purpose\":\"\"}")))
        .containsExactly("category");
    assertThat(
            ConditionOutput.missingFields(
                ConditionContext.RECEIVER,
                ConditionOutput.parse(
                    "{\"category\":\"DISPLAY_RACK\",\"vehicleType\":\"SEDAN\",\"purpose\":\"\"}")))
        .isEmpty();
  }

  @Test
  void giverHasNoRequiredInterpretationFields() {
    assertThat(
            ConditionOutput.missingFields(
                ConditionContext.GIVER, ConditionOutput.parse("{\"purpose\":\"\"}")))
        .isEmpty();
  }

  @Test
  void englishPurposePromptPreservesKoreanInputAndSeoulTimeRules() {
    assertThat(ConditionPrompt.SYSTEM)
        .contains("short English phrase", "Flea market stall", "진열대", "Asia/Seoul", "+09:00")
        .doesNotContain("short Korean phrase");
  }

  @Test
  void schemaRequiresStringPurpose() {
    var schema = JsonMapper.builder().build().readTree(ConditionPrompt.SCHEMA);
    assertThat(schema.get("required").size()).isEqualTo(1);
    assertThat(schema.get("required").get(0).stringValue()).isEqualTo("purpose");
    assertThat(schema.get("properties").get("purpose").get("type").stringValue())
        .isEqualTo("string");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "  \\t\\n"})
  void blankPurposeBecomesAbsent(String purpose) {
    assertThat(ConditionOutput.parse("{\"purpose\":\"" + purpose + "\"}").purpose()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"quantity\":3}", "{\"purpose\":null}", "{\"purpose\":3}"})
  void rejectsMissingOrNonStringPurpose(String output) {
    assertThatThrownBy(() -> ConditionOutput.parse(output))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"category", "vehicleType", "pickupDeadline"})
  void otherTextFieldsStillRejectBlanks(String field) {
    assertThatThrownBy(() -> ConditionOutput.parse("{\"purpose\":\"\",\"" + field + "\":\" \"}"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
