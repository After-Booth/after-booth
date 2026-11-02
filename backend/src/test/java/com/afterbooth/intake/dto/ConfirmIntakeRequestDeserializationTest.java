package com.afterbooth.intake.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class ConfirmIntakeRequestDeserializationTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void acceptsJsonIntegersForLineIdQuantityAndSizes() {
    var request = read(body("1", "2", sizeCm("90", "40", "180")));
    var line = request.lines().getFirst();
    assertThat(line.lineId()).isEqualTo(1L);
    assertThat(line.quantity()).isEqualTo(2);
    assertThat(line.sizeCm()).isEqualTo(new SizeCm(90, 40, 180));
  }

  @Test
  void rejectsNonIntegerLineId() {
    for (String lineId : List.of("1.5", "1.0", "\"1\"", "true"))
      assertRejected(body(lineId, "2", sizeCm("90", "40", "180")));
  }

  @Test
  void rejectsNonIntegerQuantity() {
    for (String quantity : List.of("1.5", "2.0", "\"2\"", "true"))
      assertRejected(body("1", quantity, sizeCm("90", "40", "180")));
  }

  @Test
  void rejectsNonIntegerSizes() {
    for (String size : List.of("\"90\"", "90.5", "90.0", "true")) {
      assertRejected(body("1", "2", sizeCm(size, "40", "180")));
      assertRejected(body("1", "2", sizeCm("90", size, "180")));
      assertRejected(body("1", "2", sizeCm("90", "40", size)));
    }
  }

  private static String body(String lineId, String quantity, String sizeCm) {
    return "{\"lines\":[{\"lineId\":"
        + lineId
        + ",\"category\":\"STORAGE_BIN\",\"quantity\":"
        + quantity
        + ",\"sizeCm\":"
        + sizeCm
        + ",\"condition\":\"GOOD\"}]}";
  }

  private static String sizeCm(String width, String depth, String height) {
    return "{\"width\":" + width + ",\"depth\":" + depth + ",\"height\":" + height + "}";
  }

  private static ConfirmIntakeRequest read(String body) {
    return JSON.readValue(body, ConfirmIntakeRequest.class);
  }

  private static void assertRejected(String body) {
    assertThatThrownBy(() -> read(body)).isInstanceOf(JacksonException.class);
  }
}
