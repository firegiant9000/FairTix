package com.fairtix.holds.dto;

import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.domain.TicketKind;
import com.fairtix.tickets.domain.TicketStatus;

import java.time.Instant;
import java.util.UUID;

public record CompTicketResponse(
    UUID id,
    UUID eventId,
    UUID seatId,
    String seatLabel,
    TicketKind kind,
    TicketStatus status,
    String recipientName,
    String recipientEmail,
    String reason,
    UUID issuedBy,
    boolean willCall,
    Instant issuedAt) {

  public static CompTicketResponse from(Ticket t) {
    String label = t.getSeat().getSection() + " " + t.getSeat().getRowLabel() + " " + t.getSeat().getSeatNumber();
    return new CompTicketResponse(
        t.getId(), t.getEvent().getId(), t.getSeat().getId(), label,
        t.getKind(), t.getStatus(), t.getRecipientName(), t.getRecipientEmail(),
        t.getKindReason(), t.getKindIssuedBy(), t.isWillCall(), t.getIssuedAt());
  }
}
