package com.fairtix.holds.dto;

import java.util.UUID;

/**
 * Single-source-of-truth inventory split for an event.
 * {@code capacity = sold + comped + held + cartHeld + available}.
 */
public record InventoryStatsResponse(
    UUID eventId,
    long capacity,
    long sold,
    long comped,
    long held,
    long cartHeld,
    long available) {
}
