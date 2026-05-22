package com.fairtix.holds.dto;

import com.fairtix.holds.domain.HoldCategory;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreateEventHoldRequest(
    @NotNull UUID eventId,
    @NotEmpty List<UUID> seatIds,
    @NotNull HoldCategory category,
    @Size(max = 1000) String note,
    Instant autoReleaseAt) {
}
