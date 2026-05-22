package com.fairtix.tickets.domain;

public enum TicketKind {
  PAID,
  COMP,
  HOLD_ARTIST,
  HOLD_PRESS,
  HOLD_HOUSE;

  public boolean isHold() {
    return this == HOLD_ARTIST || this == HOLD_PRESS || this == HOLD_HOUSE;
  }
}
