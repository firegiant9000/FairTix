package com.fairtix.tickets.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.fraud.application.RiskScoringService;
import com.fairtix.fraud.application.UserFlaggedForAbuseException;
import com.fairtix.fraud.domain.RiskTier;
import com.fairtix.notifications.application.EmailTemplateService;
import com.fairtix.notifications.application.NotificationGate;
import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.domain.TicketStatus;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.tickets.infrastructure.TicketTransferRequestRepository;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TransferServiceTest {

    private TransferVelocityLimiter velocityLimiter;
    private RiskScoringService riskScoringService;
    private AuditService auditService;
    private TicketRepository ticketRepository;
    private UserRepository userRepository;
    private TransferService transferService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TICKET_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        velocityLimiter = mock(TransferVelocityLimiter.class);
        riskScoringService = mock(RiskScoringService.class);
        auditService = mock(AuditService.class);
        ticketRepository = mock(TicketRepository.class);
        userRepository = mock(UserRepository.class);

        transferService = new TransferService(
                ticketRepository,
                mock(TicketTransferRequestRepository.class),
                userRepository,
                auditService,
                mock(NotificationGate.class),
                mock(EmailTemplateService.class),
                velocityLimiter,
                riskScoringService,
                mock(PurchaseCapGuard.class));

        when(riskScoringService.getTier(USER_ID)).thenReturn(RiskTier.LOW);
    }

    @Test
    void criticalTierUser_isBlockedAndAuditEventEmitted() {
        when(riskScoringService.getTier(USER_ID)).thenReturn(RiskTier.CRITICAL);

        assertThatThrownBy(() ->
                transferService.createTransferRequest(TICKET_ID, USER_ID, "other@example.com"))
                .isInstanceOf(UserFlaggedForAbuseException.class);

        verify(auditService).log(eq(USER_ID), eq("TRANSFER_BLOCKED_FRAUD_RISK"),
                anyString(), any(), anyString());
        verifyNoInteractions(velocityLimiter);
    }

    @Test
    void velocityLimitReached_throwsBeforeTheRecipientLookup() {
        ownedValidTicket();
        doThrow(new TransferVelocityExceededException(5, 24))
                .when(velocityLimiter).acquire(USER_ID);

        assertThatThrownBy(() ->
                transferService.createTransferRequest(TICKET_ID, USER_ID, "other@example.com"))
                .isInstanceOf(TransferVelocityExceededException.class)
                .hasMessageContaining("Transfer limit exceeded");

        verify(userRepository, never()).findByEmail(anyString());
    }

    /** A mistake about the sender's own ticket reveals nothing, so it uses no slot. */
    @Test
    void unknownTicket_usesNoSlot() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                transferService.createTransferRequest(TICKET_ID, USER_ID, "other@example.com"))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(velocityLimiter);
    }

    @Test
    void someoneElsesTicket_usesNoSlot() {
        Ticket ticket = ticketOwnedBy(UUID.randomUUID(), TicketStatus.VALID);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() ->
                transferService.createTransferRequest(TICKET_ID, USER_ID, "other@example.com"))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(velocityLimiter);
    }

    @Test
    void ticketNotValid_usesNoSlot() {
        Ticket ticket = ticketOwnedBy(USER_ID, TicketStatus.CANCELLED);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() ->
                transferService.createTransferRequest(TICKET_ID, USER_ID, "other@example.com"))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(velocityLimiter);
    }

    /**
     * A 404 for an unknown email reveals whether an account exists, so every
     * recipient lookup uses a slot, found or not.
     */
    @Test
    void unknownRecipient_usesASlot() {
        ownedValidTicket();
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                transferService.createTransferRequest(TICKET_ID, USER_ID, "nobody@example.com"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(velocityLimiter).acquire(USER_ID);
    }

    private void ownedValidTicket() {
        Ticket ticket = ticketOwnedBy(USER_ID, TicketStatus.VALID);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
    }

    private static Ticket ticketOwnedBy(UUID ownerId, TicketStatus status) {
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(ownerId);
        Ticket ticket = mock(Ticket.class);
        when(ticket.getUser()).thenReturn(owner);
        when(ticket.getStatus()).thenReturn(status);
        return ticket;
    }
}
