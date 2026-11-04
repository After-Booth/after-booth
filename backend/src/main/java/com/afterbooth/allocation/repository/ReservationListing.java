package com.afterbooth.allocation.repository;

import com.afterbooth.allocation.domain.Reservation;
import com.afterbooth.inventory.domain.ItemCategory;

public record ReservationListing(Reservation reservation, ItemCategory category) {
  public String itemLabel() {
    return category.label();
  }
}
