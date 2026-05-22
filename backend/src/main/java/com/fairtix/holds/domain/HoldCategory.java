package com.fairtix.holds.domain;

import com.fairtix.tickets.domain.TicketKind;

public enum HoldCategory {
  ARTIST,
  PRESS,
  HOUSE;

  public TicketKind toTicketKind() {
    return switch (this) {
      case ARTIST -> TicketKind.HOLD_ARTIST;
      case PRESS  -> TicketKind.HOLD_PRESS;
      case HOUSE  -> TicketKind.HOLD_HOUSE;
    };
  }
}
