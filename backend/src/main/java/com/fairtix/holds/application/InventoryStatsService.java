package com.fairtix.holds.application;

import com.fairtix.holds.dto.InventoryStatsResponse;
import com.fairtix.holds.infrastructure.EventHoldRepository;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.tickets.domain.TicketKind;
import com.fairtix.tickets.infrastructure.TicketRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Single-source-of-truth inventory split per event. Dashboards must source
 * sold/held/comped/available from here rather than computing them via four
 * separate queries that drift apart.
 */
@Service
public class InventoryStatsService {

  private final SeatRepository seats;
  private final TicketRepository tickets;
  private final EventHoldRepository holds;

  public InventoryStatsService(SeatRepository seats, TicketRepository tickets, EventHoldRepository holds) {
    this.seats = seats;
    this.tickets = tickets;
    this.holds = holds;
  }

  public InventoryStatsResponse statsFor(UUID eventId) {
    var seatList = seats.findByEvent_Id(eventId);
    long capacity = seatList.size();
    long sold = tickets.countByEvent_IdAndKind(eventId, TicketKind.PAID);
    long comped = tickets.countByEvent_IdAndKind(eventId, TicketKind.COMP);
    long held = holds.countActiveByEvent(eventId);
    long cartHeld = seatList.stream().filter(s -> s.getStatus() == SeatStatus.HELD).count() - held;
    if (cartHeld < 0) cartHeld = 0;
    long available = seatList.stream().filter(s -> s.getStatus() == SeatStatus.AVAILABLE).count();
    return new InventoryStatsResponse(eventId, capacity, sold, comped, held, cartHeld, available);
  }

  public List<TicketKind> allHoldKinds() {
    return List.of(TicketKind.HOLD_ARTIST, TicketKind.HOLD_PRESS, TicketKind.HOLD_HOUSE);
  }
}
