package com.fairtix.holds.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.holds.domain.EventHold;
import com.fairtix.holds.domain.HoldCategory;
import com.fairtix.holds.dto.CreateEventHoldRequest;
import com.fairtix.holds.infrastructure.EventHoldRepository;
import com.fairtix.inventory.application.SeatHoldConflictException;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.domain.TicketKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EventHoldServiceTest {

  private EventRepository events;
  private SeatRepository seats;
  private EventHoldRepository holds;
  private CompService compService;
  private AuditService audit;
  private EventHoldService service;

  private UUID actorId;
  private UUID eventId;
  private Event event;

  @BeforeEach
  void setUp() {
    events = mock(EventRepository.class);
    seats = mock(SeatRepository.class);
    holds = mock(EventHoldRepository.class);
    compService = mock(CompService.class);
    audit = mock(AuditService.class);
    service = new EventHoldService(events, seats, holds, compService, audit);

    actorId = UUID.randomUUID();
    eventId = UUID.randomUUID();
    event = mock(Event.class);
    when(event.getId()).thenReturn(eventId);
    when(events.findById(eventId)).thenReturn(Optional.of(event));
    when(holds.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void createTransitionsAvailableSeatsToHeld() {
    UUID s1 = UUID.randomUUID();
    Seat seat = mockSeat(s1, SeatStatus.AVAILABLE);
    when(seats.findAndLockByIdIn(List.of(s1))).thenReturn(List.of(seat));

    CreateEventHoldRequest req = new CreateEventHoldRequest(
        eventId, List.of(s1), HoldCategory.ARTIST, "guest list", null);
    List<EventHold> result = service.create(req, actorId);

    assertThat(result).hasSize(1);
    verify(seat).setStatus(SeatStatus.HELD);
    verify(audit).log(eq(actorId), eq("EVENT_HOLD_CREATE"), eq("EVENT_HOLD"), eq(eventId), anyString());
  }

  @Test
  void createRejectsUnavailableSeat() {
    UUID s1 = UUID.randomUUID();
    Seat seat = mockSeat(s1, SeatStatus.SOLD);
    when(seats.findAndLockByIdIn(List.of(s1))).thenReturn(List.of(seat));

    CreateEventHoldRequest req = new CreateEventHoldRequest(
        eventId, List.of(s1), HoldCategory.PRESS, null, null);
    assertThatThrownBy(() -> service.create(req, actorId))
        .isInstanceOf(SeatHoldConflictException.class);
  }

  @Test
  void releaseReturnsSeatToAvailable() {
    UUID holdId = UUID.randomUUID();
    UUID seatId = UUID.randomUUID();
    Seat seat = mockSeat(seatId, SeatStatus.HELD);
    EventHold hold = new EventHold(event, seat, HoldCategory.ARTIST, "n", actorId, null);
    when(holds.findById(holdId)).thenReturn(Optional.of(hold));
    when(seats.findAndLockById(seatId)).thenReturn(Optional.of(seat));

    // Set hold id via reflection-free path: just verify the side effects
    EventHold result = service.release(holdId, actorId);

    verify(seat).setStatus(SeatStatus.AVAILABLE);
    assertThat(result.isActive()).isFalse();
    verify(audit).log(eq(actorId), eq("EVENT_HOLD_RELEASE"), eq("EVENT_HOLD"), eq(holdId), anyString());
  }

  @Test
  void convertToCompDelegatesToCompService() {
    UUID holdId = UUID.randomUUID();
    UUID seatId = UUID.randomUUID();
    Seat seat = mockSeat(seatId, SeatStatus.HELD);
    EventHold hold = new EventHold(event, seat, HoldCategory.HOUSE, null, actorId, null);
    when(holds.findById(holdId)).thenReturn(Optional.of(hold));
    when(seats.findAndLockById(seatId)).thenReturn(Optional.of(seat));

    Ticket issued = mock(Ticket.class);
    UUID ticketId = UUID.randomUUID();
    when(issued.getId()).thenReturn(ticketId);
    when(issued.getKind()).thenReturn(TicketKind.COMP);
    when(compService.issueComp(any(), eq(actorId))).thenReturn(List.of(issued));

    Ticket result = service.convertToComp(holdId, "Bono", "bono@u2.test", "guest", true, actorId);

    assertThat(result).isSameAs(issued);
    assertThat(hold.getConvertedTicketId()).isEqualTo(ticketId);
    verify(compService).issueComp(any(), eq(actorId));
  }

  private Seat mockSeat(UUID id, SeatStatus status) {
    Seat seat = mock(Seat.class);
    when(seat.getId()).thenReturn(id);
    when(seat.getEvent()).thenReturn(event);
    when(seat.getStatus()).thenReturn(status);
    when(seat.getSection()).thenReturn("BAL");
    when(seat.getRowLabel()).thenReturn("C");
    when(seat.getSeatNumber()).thenReturn("12");
    return seat;
  }
}
