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
    if (code.equals("CATEGORY_MISMATCH")) return "요청한 종류의 물품만 자동 배정할 수 있어요.";
    if (!code.equals("VEHICLE_TOO_SMALL")) return ErrorCode.valueOf(code).message();
    if (dimension == null) return "제공자가 지정한 최소 차량 조건에 맞지 않아요.";
    int cm =
        switch (dimension) {
          case "width" -> item.dimensions().widthCm();
          case "depth" -> item.dimensions().depthCm();
          case "height" -> item.dimensions().heightCm();
          default -> throw new IllegalArgumentException("Unknown limiting dimension");
        };
    String axis =
        switch (dimension) {
          case "width" -> "너비";
          case "depth" -> "깊이";
          default -> "높이";
        };
    return axis + " " + cm + "cm인 물품이라 선택한 차량에 실을 수 없어요.";
  }

  public static String explanation(int requested, int held, String modelExplanation) {
    if (modelExplanation != null
        && modelExplanation.length() <= 2000
        && modelExplanation.matches("(?s).*[가-힣].*")) return modelExplanation;
    return held > 0
        ? "요청한 " + requested + "개 중 " + held + "개를 임시 확보했어요. 예약 현황을 확인하고 직접 확정해 주세요."
        : "요청한 물품을 자동 배정하지 못했어요. 거절 사유와 직접 선택할 수 있는 대안을 확인해 주세요.";
  }
}
