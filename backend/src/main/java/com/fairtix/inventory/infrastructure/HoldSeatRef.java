package com.fairtix.inventory.infrastructure;

import com.fairtix.inventory.domain.HoldStatus;
import java.util.UUID;

/**
 * A hold's id, status and seat id, read without loading the {@code SeatHold} or
 * {@code Seat} entity. See {@link SeatHoldRepository#findRefsByIdInAndOwnerId}.
 */
public record HoldSeatRef(UUID id, HoldStatus status, UUID seatId) {
}
