package com.fairtix.tickets.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.fraud.application.RiskScoringService;
import com.fairtix.fraud.application.UserFlaggedForAbuseException;
import com.fairtix.fraud.domain.RiskTier;
import com.fairtix.notifications.application.EmailTemplateService;
import com.fairtix.notifications.application.NotificationGate;
import com.fairtix.events.domain.Event;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.orders.domain.Order;
import com.fairtix.refunds.domain.RefundStatus;
import com.fairtix.refunds.infrastructure.RefundRepository;
import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.domain.TicketStatus;
import com.fairtix.tickets.domain.TicketTransferRequest;
import com.fairtix.tickets.domain.TransferStatus;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.tickets.infrastructure.TicketTransferRequestRepository;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TransferServiceTest {

    private TransferVelocityLimiter velocityLimiter;
    private RiskScoringService riskScoringService;
    private AuditService auditService;
    private TicketRepository ticketRepository;
    private UserRepository userRepository;
    private TicketTransferRequestRepository transferRepository;
    private RefundRepository refundRepository;
    private EntityManager entityManager;
    private NotificationGate notificationGate;
    private TransferService transferService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TICKET_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        velocityLimiter = mock(TransferVelocityLimiter.class);
        riskScoringService = mock(RiskScoringService.class);
        auditService = mock(AuditService.class);
        ticketRepository = mock(TicketRepository.class);
        userRepository = mock(UserRepository.class);
        transferRepository = mock(TicketTransferRequestRepository.class);
        refundRepository = mock(RefundRepository.class);
        entityManager = mock(EntityManager.class);
        notificationGate = mock(NotificationGate.class);

        transferService = new TransferService(
                ticketRepository,
                transferRepository,
                userRepository,
                auditService,
                notificationGate,
                mock(EmailTemplateService.class),
                velocityLimiter,
                riskScoringService,
                mock(PurchaseCapGuard.class),
                refundRepository,
                entityManager,
                mock(com.fairtix.events.application.EventSaleGuard.class));

        when(riskScoringService.getTier(USER_ID)).thenReturn(RiskTier.LOW);
        when(ticketRepository.lockById(TICKET_ID)).thenReturn(Optional.of(TICKET_ID));
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
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
     * The ticket is locked and re-read before the request is written: one cancelled while
     * the lock was awaited is refused, and nothing is inserted.
     */
    @Test
    void ticketCancelledWhileTheLockWasAwaited_isRefusedAndNothingIsInserted() {
        Ticket ticket = ownedValidTicket();
        knownRecipient();
        doAnswer(inv -> {
            when(ticket.getStatus()).thenReturn(TicketStatus.CANCELLED);
            return null;
        }).when(entityManager).refresh(ticket);

        assertThatThrownBy(() ->
                transferService.createTransferRequest(TICKET_ID, USER_ID, "other@example.com"))
                .isInstanceOf(IllegalStateException.class);

        // The slot and the recipient lookup come before the lock: a slow Redis never holds
        // the ticket row.
        var order = inOrder(velocityLimiter, ticketRepository, entityManager);
        order.verify(velocityLimiter).acquire(USER_ID);
        order.verify(ticketRepository).lockById(TICKET_ID);
        order.verify(entityManager).refresh(ticket);
        verify(transferRepository, never()).save(any());
    }

    /** A ticket handed to someone else while the lock was awaited is not the sender's. */
    @Test
    void ticketTransferredAwayWhileTheLockWasAwaited_isNotFoundAndNothingIsInserted() {
        Ticket ticket = ownedValidTicket();
        knownRecipient();
        User newOwner = mock(User.class);
        when(newOwner.getId()).thenReturn(UUID.randomUUID());
        doAnswer(inv -> {
            when(ticket.getUser()).thenReturn(newOwner);
            return null;
        }).when(entityManager).refresh(ticket);

        assertThatThrownBy(() ->
                transferService.createTransferRequest(TICKET_ID, USER_ID, "other@example.com"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(transferRepository, never()).save(any());
    }

    /** A request committed by a concurrent call while the lock was awaited is seen. */
    @Test
    void requestCreatedWhileTheLockWasAwaited_isRefusedAndNothingIsInserted() {
        ownedValidTicket();
        knownRecipient();
        when(transferRepository.findByTicket_IdAndStatus(TICKET_ID, TransferStatus.PENDING))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(mock(TicketTransferRequest.class)));

        assertThatThrownBy(() ->
                transferService.createTransferRequest(TICKET_ID, USER_ID, "other@example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending transfer request already exists");

        verify(transferRepository, never()).save(any());
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

    /**
     * A ticket that is no longer VALID is never handed over or set back to VALID:
     * the request is resolved, audited, and the acceptance is refused.
     */
    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"REFUNDED", "CANCELLED", "USED"})
    void acceptOnATicketThatIsNotValid_resolvesTheRequestAndLeavesTheTicket(TicketStatus status) {
        Ticket ticket = ticketOwnedBy(UUID.randomUUID(), status);
        TicketTransferRequest request = pendingRequestFor(ticket);

        assertThatThrownBy(() -> transferService.acceptTransfer(REQUEST_ID, USER_ID))
                .isInstanceOf(TransferNoLongerAcceptableException.class)
                .isInstanceOf(IllegalStateException.class);

        verify(entityManager).refresh(ticket, LockModeType.PESSIMISTIC_WRITE);
        verify(request).setStatus(TransferStatus.CANCELLED);
        verify(request, never()).setStatus(TransferStatus.ACCEPTED);
        verify(ticket, never()).setUser(any());
        verify(ticket, never()).setStatus(any());
        verify(ticketRepository, never()).save(any());
        verify(auditService).logInCurrentTransaction(eq(USER_ID), eq("TRANSFER_CANCELLED"),
                eq("TICKET"), any(), anyString());
    }

    /** The request is re-read under the ticket lock: one resolved meanwhile is not accepted. */
    @Test
    void acceptOnARequestResolvedWhileWaitingForTheLock_isRefused() {
        Ticket ticket = ticketOwnedBy(UUID.randomUUID(), TicketStatus.VALID);
        TicketTransferRequest request = pendingRequestFor(ticket);
        AtomicReference<TransferStatus> status = new AtomicReference<>(TransferStatus.PENDING);
        when(request.getStatus()).thenAnswer(inv -> status.get());
        doAnswer(inv -> {
            status.set(TransferStatus.CANCELLED);
            return null;
        }).when(entityManager).refresh(request, LockModeType.PESSIMISTIC_WRITE);

        assertThatThrownBy(() -> transferService.acceptTransfer(REQUEST_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer pending");

        verify(ticket, never()).setUser(any());
        verify(ticketRepository, never()).save(any());
    }

    /**
     * APPROVED, or COMPLETED by the charge.refunded webhook before finalise ran:
     * either way finalise is about to void the ticket.
     */
    @Test
    void acceptWhileARefundIsApprovedOrCompleted_isRefusedAndTheRequestStaysPending() {
        Ticket ticket = ticketOwnedBy(UUID.randomUUID(), TicketStatus.VALID);
        TicketTransferRequest request = pendingRequestFor(ticket);
        when(refundRepository.existsByOrderIdAndStatusIn(
                ORDER_ID, List.of(RefundStatus.APPROVED, RefundStatus.COMPLETED)))
                .thenReturn(true);

        assertThatThrownBy(() -> transferService.acceptTransfer(REQUEST_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refund is in progress");

        verify(request, never()).setStatus(any());
        verify(ticket, never()).setUser(any());
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void acceptOnAValidTicket_handsItOverWithoutTouchingItsStatus() {
        Ticket ticket = ticketOwnedBy(UUID.randomUUID(), TicketStatus.VALID);
        TicketTransferRequest request = pendingRequestFor(ticket);
        transferService.acceptTransfer(REQUEST_ID, USER_ID);

        verify(ticket).setUser(request.getToUser());
        verify(ticket, never()).setStatus(any());
        verify(request).setStatus(TransferStatus.ACCEPTED);
    }

    /** Reject and cancel re-read the request under its row lock before resolving it. */
    @Test
    void rejectOnARequestResolvedMeanwhile_isRefused() {
        TicketTransferRequest request =
                pendingRequestFor(ticketOwnedBy(UUID.randomUUID(), TicketStatus.VALID));
        AtomicReference<TransferStatus> status = new AtomicReference<>(TransferStatus.PENDING);
        when(request.getStatus()).thenAnswer(inv -> status.get());
        doAnswer(inv -> {
            status.set(TransferStatus.ACCEPTED);
            return null;
        }).when(entityManager).refresh(request, LockModeType.PESSIMISTIC_WRITE);

        assertThatThrownBy(() -> transferService.rejectTransfer(REQUEST_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer pending");

        verify(request, never()).setStatus(TransferStatus.REJECTED);
        verify(transferRepository, never()).save(any());
    }

    // ---- Mail is sent after the commit, never inside the transaction --------

    @Test
    void requestMail_isSentOnlyAfterTheCommit() {
        Ticket ticket = ownedValidTicket();
        User recipient = mock(User.class);
        when(recipient.getId()).thenReturn(UUID.randomUUID());
        when(userRepository.findByEmail("other@example.com")).thenReturn(Optional.of(recipient));
        TransactionSynchronizationManager.initSynchronization();

        transferService.createTransferRequest(TICKET_ID, USER_ID, "other@example.com");
        verifyNoInteractions(notificationGate);

        commit();
        verify(notificationGate).sendEmail(any(), any(), eq("other@example.com"), anyString(), any());
    }

    @Test
    void requestMail_isNotSentWhenTheTransactionRollsBack() {
        ownedValidTicket();
        User recipient = mock(User.class);
        when(recipient.getId()).thenReturn(UUID.randomUUID());
        when(userRepository.findByEmail("other@example.com")).thenReturn(Optional.of(recipient));
        TransactionSynchronizationManager.initSynchronization();

        transferService.createTransferRequest(TICKET_ID, USER_ID, "other@example.com");
        rollBack();

        verifyNoInteractions(notificationGate);
    }

    @Test
    void acceptedMail_isSentOnlyAfterTheCommit() {
        pendingRequestFor(ticketOwnedBy(UUID.randomUUID(), TicketStatus.VALID));
        TransactionSynchronizationManager.initSynchronization();

        transferService.acceptTransfer(REQUEST_ID, USER_ID);
        verifyNoInteractions(notificationGate);

        commit();
        verify(notificationGate).sendEmail(any(), any(), any(), anyString(), any());
    }

    /** The commit has happened; a mail failure after it is logged, not thrown. */
    @Test
    void acceptedMailFailure_doesNotReachTheCaller() {
        pendingRequestFor(ticketOwnedBy(UUID.randomUUID(), TicketStatus.VALID));
        doThrow(new RuntimeException("Email delivery failed"))
                .when(notificationGate).sendEmail(any(), any(), any(), anyString(), any());
        TransactionSynchronizationManager.initSynchronization();

        transferService.acceptTransfer(REQUEST_ID, USER_ID);

        assertThatCode(this::commit).doesNotThrowAnyException();
        verify(notificationGate).sendEmail(any(), any(), any(), anyString(), any());
    }

    @Test
    void rejectedMail_isNotSentWhenTheTransactionRollsBack() {
        pendingRequestFor(ticketOwnedBy(UUID.randomUUID(), TicketStatus.VALID));
        TransactionSynchronizationManager.initSynchronization();

        transferService.rejectTransfer(REQUEST_ID, USER_ID);
        rollBack();

        verifyNoInteractions(notificationGate);
    }

    @Test
    void expiredMail_isSentOnlyAfterTheCommit() {
        TicketTransferRequest request =
                pendingRequestFor(ticketOwnedBy(UUID.randomUUID(), TicketStatus.VALID));
        when(transferRepository.findByStatusAndExpiresAtBefore(eq(TransferStatus.PENDING), any()))
                .thenReturn(List.of(request));
        TransactionSynchronizationManager.initSynchronization();

        transferService.expireStaleRequests();
        verify(request).setStatus(TransferStatus.EXPIRED);
        verifyNoInteractions(notificationGate);

        commit();
        verify(notificationGate).sendEmail(any(), any(), any(), anyString(), any());
    }

    private void commit() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
    }

    private void rollBack() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    }

    /** A PENDING, unexpired request addressed to {@link #USER_ID}, on an uncapped event. */
    private TicketTransferRequest pendingRequestFor(Ticket ticket) {
        Event event = mock(Event.class);
        when(event.getMaxTicketsPerUser()).thenReturn(null);
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(ORDER_ID);
        when(ticket.getEvent()).thenReturn(event);
        when(ticket.getOrder()).thenReturn(order);
        when(ticket.getSeat()).thenReturn(mock(Seat.class));

        User recipient = mock(User.class);
        when(recipient.getId()).thenReturn(USER_ID);
        User sender = ticket.getUser();
        TicketTransferRequest request = mock(TicketTransferRequest.class);
        when(request.getToUser()).thenReturn(recipient);
        when(request.getFromUser()).thenReturn(sender);
        when(request.getStatus()).thenReturn(TransferStatus.PENDING);
        when(request.getExpiresAt()).thenReturn(Instant.now().plusSeconds(3600));
        when(request.getTicket()).thenReturn(ticket);
        when(transferRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
        return request;
    }

    private Ticket ownedValidTicket() {
        Ticket ticket = ticketOwnedBy(USER_ID, TicketStatus.VALID);
        when(ticket.getEvent()).thenReturn(mock(Event.class));
        when(ticket.getSeat()).thenReturn(mock(Seat.class));
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        return ticket;
    }

    private User knownRecipient() {
        User recipient = mock(User.class);
        when(recipient.getId()).thenReturn(UUID.randomUUID());
        when(userRepository.findByEmail("other@example.com")).thenReturn(Optional.of(recipient));
        return recipient;
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
