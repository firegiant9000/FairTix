package com.fairtix.holds.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.events.domain.Event;
import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.infrastructure.TicketRepository;
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

class WillCallServiceTest {

  private TicketRepository tickets;
  private AuditService audit;
  private WillCallService service;

  @BeforeEach
  void setUp() {
    tickets = mock(TicketRepository.class);
    audit = mock(AuditService.class);
    service = new WillCallService(tickets, audit);
  }

  @Test
  void listUsesPrintQueryWhenNoSearchTerm() {
    UUID eventId = UUID.randomUUID();
    when(tickets.findWillCallForPrint(eventId)).thenReturn(List.of());
    service.list(eventId, null);
    verify(tickets).findWillCallForPrint(eventId);
    verifyNoMoreInteractions(tickets);
  }

  @Test
  void listUsesSearchWhenTermPresent() {
    UUID eventId = UUID.randomUUID();
    when(tickets.searchWillCall(eventId, "smith")).thenReturn(List.of());
    service.list(eventId, " smith ");
    verify(tickets).searchWillCall(eventId, "smith");
  }

  @Test
  void markClaimedIsIdempotentOnAlreadyClaimedTickets() {
    UUID ticketId = UUID.randomUUID();
    Ticket ticket = mock(Ticket.class);
    when(ticket.isWillCall()).thenReturn(true);
    when(ticket.getWillCallClaimedAt()).thenReturn(java.time.Instant.now());
    when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));

    UUID actor = UUID.randomUUID();
    Ticket result = service.markClaimed(ticketId, actor);
    assertThat(result).isSameAs(ticket);
    verify(ticket, never()).markWillCallClaimed(any());
    verify(audit, never()).log(any(), anyString(), anyString(), any(), anyString());
  }

  @Test
  void markClaimedRejectsNonWillCallTickets() {
    UUID ticketId = UUID.randomUUID();
    Ticket ticket = mock(Ticket.class);
    when(ticket.isWillCall()).thenReturn(false);
    when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));

    assertThatThrownBy(() -> service.markClaimed(ticketId, UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void markClaimedPersistsAndAuditsFirstClaim() {
    UUID ticketId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    Event event = mock(Event.class);
    when(event.getId()).thenReturn(eventId);
    Ticket ticket = mock(Ticket.class);
    when(ticket.isWillCall()).thenReturn(true);
    when(ticket.getWillCallClaimedAt()).thenReturn(null);
    when(ticket.getEvent()).thenReturn(event);
    when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));

    UUID actor = UUID.randomUUID();
    service.markClaimed(ticketId, actor);

    verify(ticket).markWillCallClaimed(actor);
    verify(tickets).save(ticket);
    verify(audit).log(eq(actor), eq("WILL_CALL_CLAIMED"), eq("TICKET"), eq(ticketId), anyString());
  }
}
