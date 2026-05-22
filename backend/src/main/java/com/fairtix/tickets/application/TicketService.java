package com.fairtix.tickets.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.orders.domain.Order;
import com.fairtix.organizations.application.PlanEnforcementService;
import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.infrastructure.TicketRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class TicketService {

  private final TicketRepository ticketRepository;
  private final AuditService auditService;
  private final PlanEnforcementService planEnforcementService;

  public TicketService(TicketRepository ticketRepository,
                       AuditService auditService,
                       PlanEnforcementService planEnforcementService) {
    this.ticketRepository = ticketRepository;
    this.auditService = auditService;
    this.planEnforcementService = planEnforcementService;
  }

  public void issueTickets(Order order, List<SeatHold> holds) {
    if (!holds.isEmpty()) {
      planEnforcementService.checkCanIssueTicket(
          holds.get(0).getSeat().getEvent().getOrganizationId());
    }
    List<Ticket> tickets = holds.stream()
        .map(hold -> new Ticket(
            order,
            order.getUser(),
            hold.getSeat(),
            hold.getSeat().getEvent(),
            hold.getSeat().getPrice()))
        .toList();
    ticketRepository.saveAll(tickets);
    auditService.log(order.getUser().getId(), "TICKETS_ISSUED", "TICKET", order.getId(),
        "count=" + tickets.size());
  }

  public List<Ticket> listTickets(UUID userId) {
    return ticketRepository.findAllByUser_IdOrderByIssuedAtDesc(userId);
  }

  public Ticket getTicket(UUID ticketId, UUID userId) {
    Ticket ticket = ticketRepository.findById(ticketId)
        .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + ticketId));
    if (!ticket.getUser().getId().equals(userId)) {
      throw new ResourceNotFoundException("Ticket not found: " + ticketId);
    }
    return ticket;
  }

  public Ticket getTicketForCalendar(UUID ticketId, UUID userId) {
    Ticket ticket = ticketRepository.findByIdWithEventAndVenue(ticketId)
        .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + ticketId));
    if (!ticket.getUser().getId().equals(userId)) {
      throw new ResourceNotFoundException("Ticket not found: " + ticketId);
    }
    return ticket;
  }
}
