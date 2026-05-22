package com.fairtix.support.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.notifications.application.EmailTemplateService;
import com.fairtix.notifications.application.NotificationGate;
import com.fairtix.notifications.domain.NotificationCategory;
import com.fairtix.support.domain.SupportTicket;
import com.fairtix.support.domain.TicketCategory;
import com.fairtix.support.domain.TicketMessage;
import com.fairtix.support.domain.TicketStatus;
import com.fairtix.support.dto.AdminUpdateTicketRequest;
import com.fairtix.support.dto.SupportTicketResponse;
import com.fairtix.support.dto.TicketMessageResponse;
import com.fairtix.support.infrastructure.SupportTicketRepository;
import com.fairtix.support.infrastructure.TicketMessageRepository;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import org.slf4j.Logger;
import org.springframework.security.access.AccessDeniedException;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SupportTicketService {

    private static final Logger log = LoggerFactory.getLogger(SupportTicketService.class);

    private final SupportTicketRepository ticketRepository;
    private final TicketMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationGate notificationGate;
    private final EmailTemplateService emailTemplateService;

    public SupportTicketService(SupportTicketRepository ticketRepository,
                                TicketMessageRepository messageRepository,
                                UserRepository userRepository,
                                AuditService auditService,
                                NotificationGate notificationGate,
                                EmailTemplateService emailTemplateService) {
        this.ticketRepository = ticketRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.notificationGate = notificationGate;
        this.emailTemplateService = emailTemplateService;
    }

    @Transactional
    public SupportTicketResponse createTicket(UUID userId, String subject, TicketCategory category,
                                              String message, UUID orderId, UUID eventId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        SupportTicket ticket = new SupportTicket(user, subject, category, orderId, eventId);
        ticketRepository.save(ticket);

        TicketMessage initialMessage = new TicketMessage(ticket, user, message, false);
        messageRepository.save(initialMessage);

        auditService.log(userId, "CREATE", "SUPPORT_TICKET", ticket.getId(), "subject=" + subject);

        try {
            String html = emailTemplateService.buildTicketCreatedEmail(
                    user.getEmail(), ticket.getId().toString(), subject);
            notificationGate.sendEmail(userId, NotificationCategory.SUPPORT,
                    user.getEmail(), "Support Ticket Received: " + subject, html);
        } catch (Exception e) {
            log.warn("Failed to send ticket creation email for ticket {}: {}", ticket.getId(), e.getMessage());
        }

        List<TicketMessageResponse> messages = List.of(TicketMessageResponse.from(initialMessage));
        return SupportTicketResponse.from(ticket, messages);
    }

    @Transactional
    public TicketMessageResponse addMessage(UUID ticketId, UUID userId, String message) {
        SupportTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new SupportTicketNotFoundException(ticketId));

        User author = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        boolean isOwner = ticket.getUser().getId().equals(userId);
        boolean isAdmin = author.getRole().name().equals("ADMIN");
        if (!isOwner && !isAdmin) {
            throw new AccessDeniedException("Access denied to ticket: " + ticketId);
        }

        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new IllegalStateException("Cannot reply to a closed ticket");
        }

        TicketMessage msg = new TicketMessage(ticket, author, message, isAdmin);
        messageRepository.save(msg);

        ticket.touch();
        if (isAdmin && ticket.getStatus() == TicketStatus.OPEN) {
            ticket.setStatus(TicketStatus.IN_PROGRESS);
        } else if (!isAdmin && ticket.getStatus() == TicketStatus.WAITING_ON_USER) {
            ticket.setStatus(TicketStatus.IN_PROGRESS);
        }
        ticketRepository.save(ticket);

        auditService.log(userId, "REPLY", "SUPPORT_TICKET", ticketId, "isStaff=" + isAdmin);

        // Notify the other party
        try {
            if (isAdmin) {
                // Staff replied — notify the ticket owner
                User owner = ticket.getUser();
                String html = emailTemplateService.buildTicketReplyEmail(
                        owner.getEmail(), ticketId.toString(), ticket.getSubject(), message);
                notificationGate.sendEmail(owner.getId(), NotificationCategory.SUPPORT,
                        owner.getEmail(), "New Reply on Your Support Ticket", html);
            }
            // User replied — admin notification is handled via queue/dashboard; skip email to avoid spam
        } catch (Exception e) {
            log.warn("Failed to send reply notification for ticket {}: {}", ticketId, e.getMessage());
        }

        return TicketMessageResponse.from(msg);
    }

    public Page<SupportTicketResponse> getUserTickets(UUID userId, int page) {
        PageRequest pageable = PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "updatedAt"));
        return ticketRepository.findByUser_Id(userId, pageable)
                .map(SupportTicketResponse::summary);
    }

    public SupportTicketResponse getTicket(UUID ticketId, UUID requestingUserId, boolean isAdmin) {
        SupportTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new SupportTicketNotFoundException(ticketId));

        if (!isAdmin && !ticket.getUser().getId().equals(requestingUserId)) {
            throw new AccessDeniedException("Access denied to ticket: " + ticketId);
        }

        List<TicketMessageResponse> messages = messageRepository
                .findByTicket_IdOrderByCreatedAtAsc(ticketId)
                .stream()
                .map(TicketMessageResponse::from)
                .collect(Collectors.toList());

        return SupportTicketResponse.from(ticket, messages);
    }

    @Transactional
    public SupportTicketResponse closeTicket(UUID ticketId, UUID userId) {
        SupportTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new SupportTicketNotFoundException(ticketId));

        boolean isAdmin = userRepository.findById(userId)
                .map(u -> u.getRole().name().equals("ADMIN"))
                .orElse(false);

        if (!isAdmin && !ticket.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("Access denied to ticket: " + ticketId);
        }

        ticket.setStatus(TicketStatus.CLOSED);
        ticketRepository.save(ticket);

        auditService.log(userId, "CLOSE", "SUPPORT_TICKET", ticketId, null);

        try {
            User owner = ticket.getUser();
            String html = emailTemplateService.buildTicketClosedEmail(
                    owner.getEmail(), ticketId.toString(), ticket.getSubject());
            notificationGate.sendEmail(owner.getId(), NotificationCategory.SUPPORT,
                    owner.getEmail(), "Support Ticket Closed", html);
        } catch (Exception e) {
            log.warn("Failed to send close notification for ticket {}: {}", ticketId, e.getMessage());
        }

        return SupportTicketResponse.summary(ticket);
    }

    public Page<SupportTicketResponse> getAdminTickets(TicketStatus statusFilter, UUID userId, int page) {
        PageRequest pageable = PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "updatedAt"));
        if (userId != null && statusFilter != null) {
            return ticketRepository.findByUser_IdAndStatus(userId, statusFilter, pageable)
                    .map(SupportTicketResponse::summary);
        }
        if (userId != null) {
            return ticketRepository.findByUser_Id(userId, pageable)
                    .map(SupportTicketResponse::summary);
        }
        if (statusFilter != null) {
            return ticketRepository.findByStatus(statusFilter, pageable)
                    .map(SupportTicketResponse::summary);
        }
        return ticketRepository.findAll(pageable)
                .map(SupportTicketResponse::summary);
    }

    @Transactional
    public SupportTicketResponse adminUpdateTicket(UUID ticketId, UUID adminId, AdminUpdateTicketRequest request) {
        SupportTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new SupportTicketNotFoundException(ticketId));

        if (request.status() != null) {
            ticket.setStatus(request.status());
        }
        if (request.priority() != null) {
            ticket.setPriority(request.priority());
        }
        if (request.assignedTo() != null) {
            ticket.setAssignedTo(request.assignedTo());
        }
        ticketRepository.save(ticket);

        auditService.log(adminId, "UPDATE", "SUPPORT_TICKET", ticketId,
                "status=" + request.status() + ",priority=" + request.priority());

        return SupportTicketResponse.summary(ticket);
    }
}
