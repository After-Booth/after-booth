package com.afterbooth.handover.dto;

import com.afterbooth.allocation.dto.ReservationResponse;
import com.afterbooth.global.domain.BoundingBox;
import com.afterbooth.handover.domain.HandoverStatus;
import java.util.List;

public record HandoverResult(
    ReservationResponse reservation,
    HandoverStatus status,
    int expectedQty,
    int countedQty,
    List<BoundingBox> boxes) {}
