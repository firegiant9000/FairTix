package com.fairtix.holds.dto;

import com.fairtix.holds.domain.EventHold;
import com.fairtix.holds.domain.HoldCategory;

import java.time.Instant;
import java.util.UUID;

public record EventHoldResponse(
    UUID id,
    UUID eventId,
    UUID seatId,
    String seatLabel,
    HoldCategory category,
    String note,
    UUID createdBy,
    Instant createdAt,
    Instant autoReleaseAt,
    Instant releasedAt,
    UUID convertedTicketId) {

  public static EventHoldResponse from(EventHold h) {
    var s = h.getSeat();
    String label = s.getSection() + " " + s.getRowLabel() + " " + s.getSeatNumber();
    return new EventHoldResponse(
        h.getId(), h.getEvent().getId(), s.getId(), label,
        h.getCategory(), h.getNote(), h.getCreatedBy(), h.getCreatedAt(),
        h.getAutoReleaseAt(), h.getReleasedAt(), h.getConvertedTicketId());
  }
}
