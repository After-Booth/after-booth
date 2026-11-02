package com.afterbooth.ai;

import static com.afterbooth.ai.AiTestSupport.JSON;
import static com.afterbooth.ai.AiTestSupport.SPIKE_FIXTURES;
import static com.afterbooth.ai.AiTestSupport.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SamOutputTest {

  private static final String ONE =
      "1<|box;x1=1;y1=2;x2=3;y2=4;w=5;h=6;c=0.9|><|mask;x=0;y=0;c=0.9;data=1,1,!|>";
  private static final String TWO =
      "2<|box;x1=5;y1=6;x2=7;y2=8;w=9;h=9;c=0.8|><|mask;x=0;y=0;c=0.8;data=1,1,!|>";

  /** Every SAM response Meta really returned: the spike's and the recorded replay fixture. */
  static Stream<Path> realSamResponses() {
    return Stream.of(
            SPIKE_FIXTURES.resolve("sam"),
            SPIKE_FIXTURES.resolve("sam-boxes"),
            Path.of("../fixtures/replay/sam"))
        .filter(Files::isDirectory)
        .flatMap(SamOutputTest::list);
  }

  @ParameterizedTest
  @MethodSource("realSamResponses")
  void parsesEveryObjectInEveryRealResponse(Path fixture) {
    String text =
        JSON.readTree(read(fixture)).at("/response/body/output/0/content/0/text").stringValue();

    List<SamSegment> segments = SamOutput.parse(text);

    assertThat(segments).hasSize(text.split("<\\|box;", -1).length - 1);
    assertThat(segments)
        .allSatisfy(
            segment -> {
              assertThat(segment.score()).isBetween(0.0, 1.0);
              assertThat(segment.box().x2()).isGreaterThan(segment.box().x1());
              assertThat(segment.mask().data()).isNotBlank();
            });
  }

  @Test
  void emptyTextMeansNothingWasFound() {
    assertThat(SamOutput.parse("")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<0f>" + ONE + "," + TWO + "\n",
        "<0f>" + ONE + "," + TWO,
        "<0f>" + ONE + "," + TWO + "\r\n",
        "<0f>" + ONE + "," + TWO + " ",
        "<0f>" + ONE + "," + TWO + "\n\n",
        "<0f>" + ONE + "," + TWO + " \t\r\n"
      })
  void parsesObjectsFollowedByOnlyTrailingWhitespace(String text) {
    assertThat(SamOutput.parse(text)).extracting(SamSegment::id).containsExactly(1, 2);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<0f>1<|bo",
        "not valid SAM output",
        " ",
        "\n",
        "<0f>",
        ONE,
        "<0f>" + ONE + "trailing",
        "<0f>" + ONE + " trailing",
        "<0f>" + ONE + "\r\ntrailing",
        "<0f>" + ONE + "\n\nx",
        "<0f>" + ONE + ",",
        "<0f>" + ONE + ",junk," + TWO,
        "<0f>" + ONE + TWO,
        "<0f>1<|box;x1=1;y1=2;x2=3;y2=4;w=5;h=6;c=0.9|>",
        "<0f>1<|box;x1=1;y1=2;x2=3;y2=4;w=5;h=6|><|mask;x=0;y=0;data=1,1,!|>",
        "<0f>1<|box;x1=abc;y1=2;x2=3;y2=4;w=5;h=6;c=0.9|><|mask;x=0;y=0;c=0.9;data=1,1,!|>",
        "<0f>1<|box;x1=1;y1=2;x2=3;y2=4;w=5;h=6;c=high|><|mask;x=0;y=0;c=0.9;data=1,1,!|>",
        "<0f>1<|box;x1=1;y1=2;x2=3;y2=4;w=5;h=6;c=0.9|><|mask;x=0;y=one;c=0.9;data=1,1,!|>",
        "<0f>99999999999<|box;x1=1;y1=2;x2=3;y2=4;w=5;h=6;c=0.9|><|mask;x=0;y=0;c=0.9;data=1,1,!|>",
        "<0f>1<|box;x1=1;x1=2;y1=2;x2=3;y2=4;w=5;h=6;c=0.9|><|mask;x=0;y=0;c=0.9;data=1,1,!|>",
        "<0f>1<|box;x1=1;y1=2;x2=3;y2=4;w=5;h=6;c=0.9|><|mask;x=0;x=0;y=0;c=0.9;data=1,1,!|>"
      })
  void malformedOutputIsAlwaysASamCallFailure(String text) {
    assertThatThrownBy(() -> SamOutput.parse(text))
        .isExactlyInstanceOf(AiCallException.class)
        .satisfies(e -> assertThat(((AiCallException) e).capability()).isEqualTo(Capability.SAM));
  }

  private static Stream<Path> list(Path directory) {
    try {
      return Files.list(directory).toList().stream();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
