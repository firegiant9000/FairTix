package com.fairtix.events.domain;

import java.util.UUID;

/**
 * A purchase path was asked to sell, hold, comp, transfer or queue for an event that has
 * ended: {@link EventStatus#CANCELLED}, {@link EventStatus#COMPLETED} or
 * {@link EventStatus#ARCHIVED}. Answered with 409 {@code EVENT_NOT_ON_SALE}.
 */
public class EventNotOnSaleException extends RuntimeException {

  private final UUID eventId;
  private final EventStatus status;

  public EventNotOnSaleException(UUID eventId, EventStatus status) {
    super("This event is " + status.name().toLowerCase() + " and no longer on sale");
    this.eventId = eventId;
    this.status = status;
  }

  public UUID getEventId() {
    return eventId;
  }

  public EventStatus getStatus() {
    return status;
  }
}
