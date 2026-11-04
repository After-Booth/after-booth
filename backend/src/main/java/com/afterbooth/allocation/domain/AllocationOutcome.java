package com.afterbooth.allocation.domain;

import com.afterbooth.allocation.dto.AllocationResult;
import com.afterbooth.allocation.dto.AllocationResult.*;
import com.afterbooth.allocation.dto.ReservationResponse;
import com.afterbooth.inventory.domain.Item;
import com.afterbooth.inventory.domain.VehicleFitResult;
import com.afterbooth.inventory.dto.ItemResponse;
import com.afterbooth.request.domain.PickupRequest;
import java.util.*;

public final class AllocationOutcome {
  private AllocationOutcome() {}

  public enum Status {
    HELD,
    NO_MATCH
  }

  public static AllocationResult assemble(
      PickupRequest request,
      List<ReservationResponse> holds,
      Map<Long, Rejection> rejections,
      List<ToolTrace> trace,
      List<Item> inventory,
      java.util.function.Function<Item, VehicleFitResult> vehicleFit,
      java.time.Instant now,
      String explanation) {
    Map<Long, Rejection> rejected = new LinkedHashMap<>(rejections);
    int held = holds.stream().mapToInt(ReservationResponse::quantity).sum();
    int remaining = request.quantity() - held;
    List<Alternative> alternatives = new ArrayList<>();
    for (Item item : inventory) {
      if (remaining <= 0) continue;
      VehicleFitResult fit = vehicleFit.apply(item);
      String code = AllocationCandidate.rejection(item, fit, now);
      if (code == null) {
        alternatives.add(
            new Alternative(
                ItemResponse.from(item, false),
                Math.min(remaining, item.availableQty()),
                "Meets your vehicle and pickup requirements. Select it to place a hold."));
        rejected.remove(item.id());
      } else if (item.category() == request.category() && !rejected.containsKey(item.id())) {
        rejected.put(
            item.id(),
            new Rejection(
                item.id(),
                code,
                AllocationCandidate.message(item, code, fit.limitingDimension()),
                code.equals("VEHICLE_TOO_SMALL") ? fit.limitingDimension() : null));
      }
    }
    explanation = AllocationCandidate.explanation(request.quantity(), held, explanation);
    return new AllocationResult(
        request.id(),
        (held > 0 ? Status.HELD : Status.NO_MATCH).name(),
        explanation,
        List.copyOf(holds),
        List.copyOf(rejected.values()),
        List.copyOf(alternatives),
        List.copyOf(trace));
  }
}
