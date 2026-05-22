package com.fairtix.holds.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.infrastructure.TicketRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class WillCallService {

  private final TicketRepository tickets;
  private final AuditService audit;

  public WillCallService(TicketRepository tickets, AuditService audit) {
    this.tickets = tickets;
    this.audit = audit;
  }

  @Transactional
  public List<Ticket> list(UUID eventId, String query) {
    if (query == null || query.isBlank()) {
      return tickets.findWillCallForPrint(eventId);
    }
    return tickets.searchWillCall(eventId, query.trim());
  }

  @Transactional
  public Ticket markClaimed(UUID ticketId, UUID actorUserId) {
    Ticket ticket = tickets.findById(ticketId)
        .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + ticketId));
    if (!ticket.isWillCall()) {
      throw new IllegalStateException("Ticket is not flagged for will-call");
    }
    if (ticket.getWillCallClaimedAt() != null) {
      // Idempotent: already claimed
      return ticket;
    }
    ticket.markWillCallClaimed(actorUserId);
    tickets.save(ticket);
    audit.log(actorUserId, "WILL_CALL_CLAIMED", "TICKET", ticketId,
        "event=" + ticket.getEvent().getId());
    return ticket;
  }
}
