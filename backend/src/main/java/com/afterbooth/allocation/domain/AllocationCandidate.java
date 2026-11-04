package com.afterbooth.allocation.domain;

import com.afterbooth.global.error.ErrorCode;
import com.afterbooth.inventory.domain.Item;
import com.afterbooth.inventory.domain.VehicleFitResult;
import java.time.Instant;

public final class AllocationCandidate {
  private AllocationCandidate() {}

  public static String rejection(Item item, VehicleFitResult fit, Instant now) {
    if (item.isPickupClosed(now)) return "PICKUP_CLOSED";
    if (item.availableQty() <= 0) return "QTY_UNAVAILABLE";
    return fit.fit() ? null : "VEHICLE_TOO_SMALL";
  }

  public static String message(Item item, String code, String dimension) {
    return item.label() + ": " + reason(item, code, dimension);
  }

  private static String reason(Item item, String code, String dimension) {
    if (code.equals("CATEGORY_MISMATCH"))
      return "Only items in the requested category can be allocated automatically.";
    if (!code.equals("VEHICLE_TOO_SMALL")) return ErrorCode.valueOf(code).message();
    if (dimension == null) return "Your vehicle does not meet the giver's minimum requirement.";
    int cm =
        switch (dimension) {
          case "width" -> item.dimensions().widthCm();
          case "depth" -> item.dimensions().depthCm();
          case "height" -> item.dimensions().heightCm();
          default -> throw new IllegalArgumentException("Unknown limiting dimension");
        };
    String axis =
        switch (dimension) {
          case "width" -> "width";
          case "depth" -> "depth";
          default -> "height";
        };
    return "The item does not fit your vehicle: " + axis + " is " + cm + " cm.";
  }

  public static String explanation(int requested, int held, String modelExplanation) {
    if (modelExplanation != null
        && modelExplanation.length() <= 2000
        && !modelExplanation.isBlank()
        && modelExplanation.matches("(?s).*[A-Za-z].*")
        && !modelExplanation.matches("(?s).*[가-힣].*")) return modelExplanation;
    return held > 0
        ? "Held "
            + held
            + " of "
            + requested
            + " requested items. Review and confirm your reservations."
        : "No items could be allocated automatically. Review the reasons and select an alternative.";
  }
}
