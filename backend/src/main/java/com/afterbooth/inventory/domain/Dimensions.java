package com.afterbooth.inventory.domain;

import com.afterbooth.global.error.BusinessException;
import com.afterbooth.global.error.ErrorCode;
import jakarta.persistence.Embeddable;
import java.util.Arrays;
import java.util.Comparator;

@Embeddable
public record Dimensions(int widthCm, int depthCm, int heightCm) {
  public Dimensions {
    if (widthCm <= 0 || depthCm <= 0 || heightCm <= 0)
      throw new BusinessException(ErrorCode.INVALID_INPUT);
  }

  public String limitingDimension(Dimensions cargo) {
    Axis[] itemAxes = {
      new Axis("width", widthCm, 0), new Axis("depth", depthCm, 1), new Axis("height", heightCm, 2)
    };
    Arrays.sort(itemAxes, Comparator.comparingInt(Axis::size));
    int[] cargoAxes = {cargo.widthCm(), cargo.depthCm(), cargo.heightCm()};
    Arrays.sort(cargoAxes);
    Axis limiting = null;
    int greatestOverflow = 0;
    for (int index = 0; index < itemAxes.length; index++) {
      Axis axis = itemAxes[index];
      int overflow = axis.size() - cargoAxes[index];
      if (overflow > greatestOverflow
          || (overflow > 0 && overflow == greatestOverflow && axis.order() < limiting.order())) {
        limiting = axis;
        greatestOverflow = overflow;
      }
    }
    return limiting == null ? null : limiting.name();
  }

  private record Axis(String name, int size, int order) {}
}
