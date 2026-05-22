package com.fairtix.holds.dto;

import java.util.UUID;

/**
 * Single-source-of-truth inventory split for an event.
 *
 * <p>{@code capacity = sold + comped + held + cartHeld + available}. Always
 * source dashboard inventory from this aggregate; computing the four numbers
 * from independent queries leads to drift.
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
