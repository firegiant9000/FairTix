package com.fairtix.tickets.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.fraud.application.RiskScoringService;
import com.fairtix.fraud.application.UserFlaggedForAbuseException;
import com.fairtix.fraud.domain.RiskTier;
import com.fairtix.notifications.application.EmailTemplateService;
import com.fairtix.notifications.application.NotificationGate;
import com.fairtix.notifications.domain.NotificationCategory;
import com.fairtix.orders.application.PurchaseCapExceededException;
import com.fairtix.refunds.domain.RefundStatus;
import com.fairtix.refunds.infrastructure.RefundRepository;
import com.fairtix.tickets.domain.TicketStatus;
import com.fairtix.tickets.domain.TicketTransferRequest;
import com.fairtix.tickets.domain.TransferStatus;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.tickets.infrastructure.TicketTransferRequestRepository;
import com.fairtix.users.infrastructure.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final int TRANSFER_EXPIRY_DAYS = 7;

    private final TicketRepository ticketRepository;
    private final TicketTransferRequestRepository transferRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationGate notificationGate;
    private final EmailTemplateService emailTemplateService;
    private final TransferVelocityLimiter velocityLimiter;
    private final RiskScoringService riskScoringService;
    private final PurchaseCapGuard purchaseCapGuard;
    private final RefundRepository refundRepository;
    private final EntityManager entityManager;

    public TransferService(TicketRepository ticketRepository,
                           TicketTransferRequestRepository transferRepository,
                           UserRepository userRepository,
                           AuditService auditService,
                           NotificationGate notificationGate,
                           EmailTemplateService emailTemplateService,
                           TransferVelocityLimiter velocityLimiter,
                           RiskScoringService riskScoringService,
                           PurchaseCapGuard purchaseCapGuard,
                           RefundRepository refundRepository,
                           EntityManager entityManager) {
        this.purchaseCapGuard = purchaseCapGuard;
        this.refundRepository = refundRepository;
        this.entityManager = entityManager;
        this.ticketRepository = ticketRepository;
        this.transferRepository = transferRepository;
        this.userRepository = userRepository;
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

        if (!ticket.getUser().getId().equals(fromUserId)) {
            throw new ResourceNotFoundException("Ticket not found: " + ticketId);
        }

        if (ticket.getStatus() != TicketStatus.VALID) {
            throw new IllegalStateException("Only VALID tickets can be transferred");
        }

        transferRepository.findByTicket_IdAndStatus(ticketId, TransferStatus.PENDING).ifPresent(r -> {
            throw new IllegalStateException("A pending transfer request already exists for this ticket");
        });

        // The slot is taken here, after the checks on the sender's own ticket and
        // before the recipient lookup: a 404 for an unknown email reveals whether an
        // account exists, so every lookup counts toward the limit, while mistakes
        // about the sender's own ticket do not.
        velocityLimiter.acquire(fromUserId);

        var toUser = userRepository.findByEmail(toEmail)
                .orElseThrow(() -> new ResourceNotFoundException("No user found with email: " + toEmail));

        if (toUser.getId().equals(fromUserId)) {
            throw new IllegalArgumentException("Cannot transfer a ticket to yourself");
        }

        var expiresAt = Instant.now().plus(TRANSFER_EXPIRY_DAYS, ChronoUnit.DAYS);
        var request = new TicketTransferRequest(ticket, ticket.getUser(), toUser, expiresAt);
        transferRepository.save(request);

        auditService.logInCurrentTransaction(fromUserId, "TRANSFER_REQUESTED", "TICKET", ticketId,
                "to=" + toEmail);

        String seatDesc = seatDesc(request);
        try {
            notificationGate.sendEmail(
                    toUser.getId(),
                    NotificationCategory.TICKET_TRANSFER,
                    toEmail,
                    "Ticket transfer request from FairTix",
                    emailTemplateService.buildTransferRequestEmail(
                            toEmail, ticket.getUser().getEmail(),
                            ticket.getEvent().getTitle(), seatDesc, null));
        } catch (Exception e) {
            log.warn("Failed to send transfer request email to={}", toEmail, e);
        }

        return request;
    }

    /**
     * Lock order: the recipient's cap lock, then the ticket row. The ticket row
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

        String seatDesc = seatDesc(request);
        try {
            notificationGate.sendEmail(
                    request.getFromUser().getId(),
                    NotificationCategory.TICKET_TRANSFER,
                    request.getFromUser().getEmail(),
                    "Your ticket transfer was accepted",
                    emailTemplateService.buildTransferAcceptedEmail(
                            request.getFromUser().getEmail(), request.getToUser().getEmail(),
                            ticket.getEvent().getTitle(), seatDesc));
        } catch (Exception e) {
            log.warn("Failed to send transfer accepted email", e);
        }

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

        String seatDesc = seatDesc(request);
        try {
            notificationGate.sendEmail(
                    request.getFromUser().getId(),
                    NotificationCategory.TICKET_TRANSFER,
                    request.getFromUser().getEmail(),
                    "Your ticket transfer was declined",
                    emailTemplateService.buildTransferRejectedEmail(
                            request.getFromUser().getEmail(), request.getToUser().getEmail(),
                            request.getTicket().getEvent().getTitle(), seatDesc));
        } catch (Exception e) {
            log.warn("Failed to send transfer rejected email", e);
        }

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

                String seatDesc = seatDesc(request);
                try {
                    notificationGate.sendEmail(
                            request.getFromUser().getId(),
                            NotificationCategory.TICKET_TRANSFER,
                            request.getFromUser().getEmail(),
                            "Your ticket transfer request expired",
                            emailTemplateService.buildTransferExpiredEmail(
                                    request.getFromUser().getEmail(),
                                    request.getTicket().getEvent().getTitle(), seatDesc));
                } catch (Exception e) {
                    log.warn("Failed to send transfer expired email to={}", request.getFromUser().getEmail(), e);
                }
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

    private String seatDesc(TicketTransferRequest r) {
        var seat = r.getTicket().getSeat();
        return seat.getSection() + "-" + seat.getRowLabel() + "-" + seat.getSeatNumber();
    }
}
