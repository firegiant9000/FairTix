package com.fairtix.tickets.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.fraud.application.RiskScoringService;
import com.fairtix.fraud.application.UserFlaggedForAbuseException;
import com.fairtix.fraud.domain.RiskTier;
import com.fairtix.notifications.application.EmailTemplateService;
import com.fairtix.notifications.application.NotificationGate;
import com.fairtix.notifications.domain.NotificationCategory;
import com.fairtix.tickets.domain.TicketStatus;
import com.fairtix.tickets.domain.TicketTransferRequest;
import com.fairtix.tickets.domain.TransferStatus;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.tickets.infrastructure.TicketTransferRequestRepository;
import com.fairtix.users.infrastructure.UserRepository;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final int TRANSFER_EXPIRY_DAYS = 7;
    private static final String VELOCITY_KEY_PREFIX = "transfer:velocity:";

    private final TicketRepository ticketRepository;
    private final TicketTransferRequestRepository transferRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationGate notificationGate;
    private final EmailTemplateService emailTemplateService;
    private final RedissonClient redissonClient;
    private final RiskScoringService riskScoringService;

    @Value("${fairtix.transfer.velocity.max-per-window:5}")
    private int velocityMaxPerWindow;

    @Value("${fairtix.transfer.velocity.window-hours:24}")
    private int velocityWindowHours;

    public TransferService(TicketRepository ticketRepository,
                           TicketTransferRequestRepository transferRepository,
                           UserRepository userRepository,
                           AuditService auditService,
                           NotificationGate notificationGate,
                           EmailTemplateService emailTemplateService,
                           RedissonClient redissonClient,
                           RiskScoringService riskScoringService) {
        this.ticketRepository = ticketRepository;
        this.transferRepository = transferRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.notificationGate = notificationGate;
        this.emailTemplateService = emailTemplateService;
        this.redissonClient = redissonClient;
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

        checkVelocity(fromUserId);

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

        var toUser = userRepository.findByEmail(toEmail)
                .orElseThrow(() -> new ResourceNotFoundException("No user found with email: " + toEmail));

        if (toUser.getId().equals(fromUserId)) {
            throw new IllegalArgumentException("Cannot transfer a ticket to yourself");
        }

        var expiresAt = Instant.now().plus(TRANSFER_EXPIRY_DAYS, ChronoUnit.DAYS);
        var request = new TicketTransferRequest(ticket, ticket.getUser(), toUser, expiresAt);
        transferRepository.save(request);

        auditService.log(fromUserId, "TRANSFER_REQUESTED", "TICKET", ticketId,
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

    @Transactional
    public TicketTransferRequest acceptTransfer(UUID requestId, UUID toUserId) {
        var request = findRequestForRecipient(requestId, toUserId);

        if (request.getExpiresAt().isBefore(Instant.now())) {
            request.setStatus(TransferStatus.EXPIRED);
            request.setResolvedAt(Instant.now());
            transferRepository.save(request);
            throw new IllegalStateException("Transfer request has expired");
        }

        var ticket = request.getTicket();
        ticket.setUser(request.getToUser());
        ticket.setStatus(TicketStatus.VALID);
        ticketRepository.save(ticket);

        request.setStatus(TransferStatus.ACCEPTED);
        request.setResolvedAt(Instant.now());
        transferRepository.save(request);

        auditService.log(toUserId, "TRANSFER_ACCEPTED", "TICKET", ticket.getId(),
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

        request.setStatus(TransferStatus.REJECTED);
        request.setResolvedAt(Instant.now());
        transferRepository.save(request);

        auditService.log(toUserId, "TRANSFER_REJECTED", "TICKET", request.getTicket().getId(),
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

        request.setStatus(TransferStatus.CANCELLED);
        request.setResolvedAt(Instant.now());
        transferRepository.save(request);

        auditService.log(fromUserId, "TRANSFER_CANCELLED", "TICKET", request.getTicket().getId(),
                "to=" + request.getToUser().getEmail());

        return request;
    }

    public List<TicketTransferRequest> listIncoming(UUID userId) {
        return transferRepository.findByToUser_IdAndStatus(userId, TransferStatus.PENDING);
    }

    public List<TicketTransferRequest> listOutgoing(UUID userId) {
        return transferRepository.findByFromUser_Id(userId);
    }

    private void checkVelocity(UUID userId) {
        RScoredSortedSet<String> velocitySet = redissonClient.getScoredSortedSet(
                VELOCITY_KEY_PREFIX + userId);
        long windowStartMillis = Instant.now()
                .minus(velocityWindowHours, ChronoUnit.HOURS)
                .toEpochMilli();
        velocitySet.removeRangeByScore(Double.NEGATIVE_INFINITY, false,
                (double) windowStartMillis, true);
        if (velocitySet.size() >= velocityMaxPerWindow) {
            throw new TransferVelocityExceededException(velocityMaxPerWindow, velocityWindowHours);
        }
        velocitySet.add((double) Instant.now().toEpochMilli(), UUID.randomUUID().toString());
        velocitySet.expire(Duration.ofHours(velocityWindowHours + 1));
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireStaleRequests() {
        MDC.put("requestId", "sched-expireStaleRequests-" + UUID.randomUUID());
        try {
            List<TicketTransferRequest> expired =
                    transferRepository.findByStatusAndExpiresAtBefore(TransferStatus.PENDING, Instant.now());

            for (TicketTransferRequest request : expired) {
                request.setStatus(TransferStatus.EXPIRED);
                request.setResolvedAt(Instant.now());
                transferRepository.save(request);

                auditService.log(request.getFromUser().getId(), "TRANSFER_EXPIRED", "TICKET",
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

    private String seatDesc(TicketTransferRequest r) {
        var seat = r.getTicket().getSeat();
        return seat.getSection() + "-" + seat.getRowLabel() + "-" + seat.getSeatNumber();
    }
}
