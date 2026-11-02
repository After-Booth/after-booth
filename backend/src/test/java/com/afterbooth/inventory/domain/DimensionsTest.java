package com.afterbooth.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DimensionsTest {
  private final Dimensions cargo = new Dimensions(120, 80, 60);

  @Test
  void allSixAxisAlignedRotationsFitAtTheExactBoundary() {
    for (Dimensions item :
        new Dimensions[] {
          new Dimensions(120, 80, 60), new Dimensions(120, 60, 80),
          new Dimensions(80, 120, 60), new Dimensions(80, 60, 120),
          new Dimensions(60, 120, 80), new Dimensions(60, 80, 120)
        }) {
      assertThat(item.limitingDimension(cargo)).isNull();
    }
  }

  @Test
  void eachSortedAxisRejectsOneCentimeterOverflow() {
    assertThat(new Dimensions(121, 80, 60).limitingDimension(cargo)).isEqualTo("width");
    assertThat(new Dimensions(120, 81, 60).limitingDimension(cargo)).isEqualTo("depth");
    assertThat(new Dimensions(120, 80, 61).limitingDimension(cargo)).isEqualTo("height");
  }

  @Test
  void diagnosticMapsRotatedAxesBackToTheOriginalItem() {
    assertThat(new Dimensions(60, 121, 80).limitingDimension(cargo)).isEqualTo("depth");
    assertThat(new Dimensions(81, 60, 120).limitingDimension(cargo)).isEqualTo("width");
    assertThat(new Dimensions(80, 120, 61).limitingDimension(cargo)).isEqualTo("height");
  }

  @Test
  void greatestOverflowWinsAndEqualOverflowUsesOriginalAxisOrder() {
    assertThat(new Dimensions(121, 83, 62).limitingDimension(cargo)).isEqualTo("depth");
    assertThat(new Dimensions(61, 121, 81).limitingDimension(cargo)).isEqualTo("width");
    assertThat(new Dimensions(120, 81, 61).limitingDimension(cargo)).isEqualTo("depth");
  }

  @Test
  void equalItemAxesUseStableWidthDepthHeightOrder() {
    assertThat(new Dimensions(81, 81, 81).limitingDimension(cargo)).isEqualTo("width");
    assertThat(new Dimensions(1, 81, 81).limitingDimension(cargo)).isEqualTo("depth");
  }

  @Test
  void rotationSupportsUnsortedCargoOverrides() {
    assertThat(new Dimensions(120, 80, 60).limitingDimension(new Dimensions(60, 120, 80))).isNull();
    assertThat(new Dimensions(60, 121, 80).limitingDimension(new Dimensions(60, 120, 80)))
        .isEqualTo("depth");
  }
}
