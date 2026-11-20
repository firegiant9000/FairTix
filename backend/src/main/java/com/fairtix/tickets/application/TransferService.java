package com.fairtix.tickets.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.common.AfterCommit;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.events.application.EventSaleGuard;
import com.fairtix.fraud.application.RiskScoringService;
import com.fairtix.fraud.application.UserFlaggedForAbuseException;
import com.fairtix.fraud.domain.RiskTier;
import com.fairtix.notifications.application.EmailTemplateService;
import com.fairtix.notifications.application.NotificationGate;
import com.fairtix.notifications.domain.NotificationCategory;
import com.fairtix.orders.application.PurchaseCapExceededException;
import com.fairtix.refunds.domain.RefundStatus;
import com.fairtix.refunds.infrastructure.RefundRepository;
import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.domain.TicketStatus;
import com.fairtix.tickets.domain.TicketTransferRequest;
import com.fairtix.tickets.domain.TransferStatus;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.tickets.infrastructure.TicketTransferRequestRepository;
import com.fairtix.users.application.UserEmailLookup;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class TransferService {

    private static final int TRANSFER_EXPIRY_DAYS = 7;

    private final TicketRepository ticketRepository;
    private final TicketTransferRequestRepository transferRepository;
    private final AuditService auditService;
    private final NotificationGate notificationGate;
    private final EmailTemplateService emailTemplateService;
    private final TransferVelocityLimiter velocityLimiter;
    private final RiskScoringService riskScoringService;
    private final PurchaseCapGuard purchaseCapGuard;
    private final RefundRepository refundRepository;
    private final EntityManager entityManager;
    private final EventSaleGuard eventSaleGuard;
    private final UserEmailLookup userEmailLookup;

    public TransferService(TicketRepository ticketRepository,
                           TicketTransferRequestRepository transferRepository,
                           AuditService auditService,
                           NotificationGate notificationGate,
                           EmailTemplateService emailTemplateService,
                           TransferVelocityLimiter velocityLimiter,
                           RiskScoringService riskScoringService,
                           PurchaseCapGuard purchaseCapGuard,
                           RefundRepository refundRepository,
                           EntityManager entityManager,
                           EventSaleGuard eventSaleGuard,
                           UserEmailLookup userEmailLookup) {
        this.userEmailLookup = userEmailLookup;
        this.eventSaleGuard = eventSaleGuard;
        this.purchaseCapGuard = purchaseCapGuard;
        this.refundRepository = refundRepository;
        this.entityManager = entityManager;
        this.ticketRepository = ticketRepository;
        this.transferRepository = transferRepository;
        this.auditService = auditService;
        this.notificationGate = notificationGate;
        this.emailTemplateService = emailTemplateService;
        this.velocityLimiter = velocityLimiter;
        this.riskScoringService = riskScoringService;
    }

    @Transactional
    public TicketTransferRequest createTransferRequest(UUID ticketId, UUID fromUserId, String toEmail) {
        // Transfers carry no additional price — anti-scalping by design; face value is preserved.
        RiskTier tier = riskScoringService.getTier(fromUserId);
        if (tier == RiskTier.CRITICAL) {
            auditService.log(fromUserId, "TRANSFER_BLOCKED_FRAUD_RISK", "TICKET", ticketId,
                    "tier=" + tier);
            throw new UserFlaggedForAbuseException();
        }

        var ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + ticketId));

        // Checked unlocked first, so a mistake about the sender's own ticket uses no slot.
        requireTransferable(ticket, ticketId, fromUserId);

        // A request on an ended event could never be accepted. Unlocked: the acceptance
        // checks again under the event's sale lock.
        eventSaleGuard.requireOnSale(ticket.getEvent());

        // The slot is taken here, after the checks on the sender's own ticket and
        // before the recipient lookup: a 404 for an unknown email reveals whether an
        // account exists, so every lookup counts toward the limit, while mistakes
        // about the sender's own ticket do not. Both run before the ticket lock, so a
        // slow Redis never holds the ticket row.
        velocityLimiter.acquire(fromUserId);

        // Ignoring case, ranked as comps rank it: registration keeps the address as typed.
        var toUser = userEmailLookup.findRecipient(toEmail)
                .orElseThrow(() -> new ResourceNotFoundException("No user found with email: " + toEmail));

        if (toUser.getId().equals(fromUserId)) {
            throw new IllegalArgumentException("Cannot transfer a ticket to yourself");
        }

        // Lock the ticket row, then re-read it and check again: an event cancellation, a
        // refund's finalise or an acceptance may have changed it since the read above.
        // Without the lock, the request's INSERT waited on a cancellation's ticket lock and
        // then succeeded, leaving a PENDING request on a CANCELLED ticket. A native query on
        // tickets alone, as in RefundService.finalise: an entity lock joins users and would
        // lose a row whose owner changed while it waited. Two requests for one ticket also
        // serialise here, so the pending check below sees the first. Lock order: sale lock
        // (none taken here) -> ticket row -> transfer request rows; this path locks no
        // request row, it only inserts one.
        ticketRepository.lockById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + ticketId));
        entityManager.refresh(ticket);
        requireTransferable(ticket, ticketId, fromUserId);

        var expiresAt = Instant.now().plus(TRANSFER_EXPIRY_DAYS, ChronoUnit.DAYS);
        var request = new TicketTransferRequest(ticket, ticket.getUser(), toUser, expiresAt);
        transferRepository.save(request);

        auditService.logInCurrentTransaction(fromUserId, "TRANSFER_REQUESTED", "TICKET", ticketId,
                "to=" + toEmail);

        UUID toUserId = toUser.getId();
        String fromEmail = ticket.getUser().getEmail();
        String eventTitle = ticket.getEvent().getTitle();
        String seatDesc = seatDesc(request);
        AfterCommit.run("transfer request email, request " + request.getId(), () -> notificationGate.sendEmail(
                toUserId,
                NotificationCategory.TICKET_TRANSFER,
                toEmail,
                "Ticket transfer request from FairTix",
                emailTemplateService.buildTransferRequestEmail(
                        toEmail, fromEmail, eventTitle, seatDesc, null)));

        return request;
    }

    /** The sender owns the ticket, it is VALID, and no request on it is pending. */
    private void requireTransferable(Ticket ticket, UUID ticketId, UUID fromUserId) {
        if (!ticket.getUser().getId().equals(fromUserId)) {
            throw new ResourceNotFoundException("Ticket not found: " + ticketId);
        }
        if (ticket.getStatus() != TicketStatus.VALID) {
            throw new IllegalStateException("Only VALID tickets can be transferred");
        }
        transferRepository.findByTicket_IdAndStatus(ticketId, TransferStatus.PENDING).ifPresent(r -> {
            throw new IllegalStateException("A pending transfer request already exists for this ticket");
        });
    }

    /**
     * Lock order: the recipient's cap lock, then the event's sale lock, then the ticket row. The ticket row
     * lock is the one {@code RefundService.finalise} takes, so an acceptance and a
     * refund of the same ticket run one after the other, and the second sees what
     * the first committed.
     */
    @Transactional(noRollbackFor = TransferNoLongerAcceptableException.class)
    public TicketTransferRequest acceptTransfer(UUID requestId, UUID toUserId) {
        var request = findRequestForRecipient(requestId, toUserId);

        if (request.getExpiresAt().isBefore(Instant.now())) {
            request.setStatus(TransferStatus.EXPIRED);
            request.setResolvedAt(Instant.now());
            transferRepository.save(request);
            throw new IllegalStateException("Transfer request has expired");
        }

        var ticket = request.getTicket();

        // An accepted ticket counts toward the recipient's purchase cap like one they
        // bought. Checked under the same (event, user) lock as the hold gate and
        // checkout, and before anything is written, so an acceptance cannot overlap
        // the recipient's own hold or checkout and end over the cap.
        var event = ticket.getEvent();
        Integer cap = event.getMaxTicketsPerUser();
        if (cap != null) {
            purchaseCapGuard.lock(event.getId(), toUserId);
        }
        // No transfers on an ended event. After the cap lock, before the ticket row: an event
        // cancellation takes the sale lock before its tickets (EventSaleGuard lock order).
        eventSaleGuard.lockOnSale(event.getId());

        // Lock the ticket, then the request, and re-read both: they were loaded
        // without a lock, and a refund, a cancel, a reject, an expiry or another
        // acceptance may have committed since. Ticket before request, as in
        // RefundService.finalise; the paths that lock only the request
        // (reject, cancel, expiry) never wait on a ticket.
        entityManager.refresh(ticket, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(request, LockModeType.PESSIMISTIC_WRITE);
        if (request.getStatus() != TransferStatus.PENDING) {
            throw new IllegalStateException("Transfer request is no longer pending");
        }
        if (ticket.getStatus() != TicketStatus.VALID) {
            request.setStatus(TransferStatus.CANCELLED);
            request.setResolvedAt(Instant.now());
            transferRepository.save(request);
            auditService.logInCurrentTransaction(toUserId, "TRANSFER_CANCELLED", "TICKET",
                    ticket.getId(), "ticket is " + ticket.getStatus() + ", no longer transferable");
            throw new TransferNoLongerAcceptableException(
                    "This ticket can no longer be transferred (status: " + ticket.getStatus() + ")");
        }
        // A refund that is approved, or already COMPLETED by the charge.refunded
        // webhook while finalise has not yet run (or failed and awaits a retry), is
        // about to void the ticket; finalise also cancels this request. The ticket
        // is still VALID here, so the order is not yet REFUNDED. Nothing is resolved.
        if (refundRepository.existsByOrderIdAndStatusIn(ticket.getOrder().getId(),
                List.of(RefundStatus.APPROVED, RefundStatus.COMPLETED))) {
            throw new IllegalStateException("A refund is in progress for this ticket's order");
        }

        if (cap != null) {
            long owned = purchaseCapGuard.activeTickets(toUserId, event.getId());
            long held = purchaseCapGuard.liveHolds(toUserId, event.getId());
            if (owned + held + 1 > cap) {
                throw new PurchaseCapExceededException(
                        "Purchase cap of " + cap + " ticket(s) per user exceeded for event: "
                                + event.getTitle() + " (already purchased: " + owned
                                + ", held: " + held + ", transferred: 1)");
            }
        }

        ticket.setUser(request.getToUser());
        ticketRepository.save(ticket);

        request.setStatus(TransferStatus.ACCEPTED);
        request.setResolvedAt(Instant.now());
        transferRepository.save(request);

        auditService.logInCurrentTransaction(toUserId, "TRANSFER_ACCEPTED", "TICKET", ticket.getId(),
                "from=" + request.getFromUser().getEmail());

        // Sent after the commit, so not while the cap lock and ticket row are held.
        MailToSender mail = MailToSender.of(request, seatDesc(request));
        AfterCommit.run("transfer accepted email, request " + request.getId(), () -> notificationGate.sendEmail(
                mail.fromUserId(),
                NotificationCategory.TICKET_TRANSFER,
                mail.fromEmail(),
                "Your ticket transfer was accepted",
                emailTemplateService.buildTransferAcceptedEmail(
                        mail.fromEmail(), mail.toEmail(), mail.eventTitle(), mail.seatDesc())));

        return request;
    }

    @Transactional
    public TicketTransferRequest rejectTransfer(UUID requestId, UUID toUserId) {
        var request = findRequestForRecipient(requestId, toUserId);
        lockPending(request);

        request.setStatus(TransferStatus.REJECTED);
        request.setResolvedAt(Instant.now());
        transferRepository.save(request);

        auditService.logInCurrentTransaction(toUserId, "TRANSFER_REJECTED", "TICKET", request.getTicket().getId(),
                "from=" + request.getFromUser().getEmail());

        MailToSender mail = MailToSender.of(request, seatDesc(request));
        AfterCommit.run("transfer rejected email, request " + request.getId(), () -> notificationGate.sendEmail(
                mail.fromUserId(),
                NotificationCategory.TICKET_TRANSFER,
                mail.fromEmail(),
                "Your ticket transfer was declined",
                emailTemplateService.buildTransferRejectedEmail(
                        mail.fromEmail(), mail.toEmail(), mail.eventTitle(), mail.seatDesc())));

        return request;
    }

    @Transactional
    public TicketTransferRequest cancelTransfer(UUID requestId, UUID fromUserId) {
        var request = transferRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer request not found: " + requestId));

        if (!request.getFromUser().getId().equals(fromUserId)) {
            throw new ResourceNotFoundException("Transfer request not found: " + requestId);
        }

        if (request.getStatus() != TransferStatus.PENDING) {
            throw new IllegalStateException("Only PENDING requests can be cancelled");
        }
        lockPending(request);

        request.setStatus(TransferStatus.CANCELLED);
        request.setResolvedAt(Instant.now());
        transferRepository.save(request);

        auditService.logInCurrentTransaction(fromUserId, "TRANSFER_CANCELLED", "TICKET", request.getTicket().getId(),
                "to=" + request.getToUser().getEmail());

        return request;
    }

    public List<TicketTransferRequest> listIncoming(UUID userId) {
        return transferRepository.findByToUser_IdAndStatus(userId, TransferStatus.PENDING);
    }

    public List<TicketTransferRequest> listOutgoing(UUID userId) {
        return transferRepository.findByFromUser_Id(userId);
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireStaleRequests() {
        MDC.put("requestId", "sched-expireStaleRequests-" + UUID.randomUUID());
        try {
            // Locked one by one below, in id order: the order RefundService.finalise
            // locks requests in, so a sweep and a refund cannot deadlock.
            List<TicketTransferRequest> expired =
                    transferRepository.findByStatusAndExpiresAtBefore(TransferStatus.PENDING, Instant.now())
                            .stream()
                            .sorted(java.util.Comparator.comparing(TicketTransferRequest::getId))
                            .toList();

            for (TicketTransferRequest request : expired) {
                // Accepted, rejected or cancelled since the query: leave it.
                entityManager.refresh(request, LockModeType.PESSIMISTIC_WRITE);
                if (request.getStatus() != TransferStatus.PENDING) {
                    continue;
                }
                request.setStatus(TransferStatus.EXPIRED);
                request.setResolvedAt(Instant.now());
                transferRepository.save(request);

                auditService.logInCurrentTransaction(request.getFromUser().getId(), "TRANSFER_EXPIRED", "TICKET",
                        request.getTicket().getId(), "to=" + request.getToUser().getEmail());

                MailToSender mail = MailToSender.of(request, seatDesc(request));
                AfterCommit.run("transfer expired email, request " + request.getId(), () -> notificationGate.sendEmail(
                        mail.fromUserId(),
                        NotificationCategory.TICKET_TRANSFER,
                        mail.fromEmail(),
                        "Your ticket transfer request expired",
                        emailTemplateService.buildTransferExpiredEmail(
                                mail.fromEmail(), mail.eventTitle(), mail.seatDesc())));
            }
        } finally {
            MDC.remove("requestId");
        }
    }

    private TicketTransferRequest findRequestForRecipient(UUID requestId, UUID toUserId) {
        var request = transferRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer request not found: " + requestId));

        if (!request.getToUser().getId().equals(toUserId)) {
            throw new ResourceNotFoundException("Transfer request not found: " + requestId);
        }

        if (request.getStatus() != TransferStatus.PENDING) {
            throw new IllegalStateException("Transfer request is no longer pending");
        }

        return request;
    }

    /**
     * Locks the request row and re-reads it. The row has no version column, so
     * without the lock a writer that read PENDING earlier would overwrite whatever
     * another transaction committed in between.
     */
    private void lockPending(TicketTransferRequest request) {
        entityManager.refresh(request, LockModeType.PESSIMISTIC_WRITE);
        if (request.getStatus() != TransferStatus.PENDING) {
            throw new IllegalStateException("Transfer request is no longer pending");
        }
    }

    /**
     * What a mail to the sender needs, read inside the transaction: the entities
     * may be detached by the time the after-commit send runs.
     */
    private record MailToSender(UUID fromUserId, String fromEmail, String toEmail,
                                String eventTitle, String seatDesc) {
        static MailToSender of(TicketTransferRequest request, String seatDesc) {
            return new MailToSender(request.getFromUser().getId(), request.getFromUser().getEmail(),
                    request.getToUser().getEmail(), request.getTicket().getEvent().getTitle(), seatDesc);
        }
    }

    private String seatDesc(TicketTransferRequest r) {
        var seat = r.getTicket().getSeat();
        return seat.getSection() + "-" + seat.getRowLabel() + "-" + seat.getSeatNumber();
    }
}
