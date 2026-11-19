package com.fairtix.events.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.events.domain.Event;
import com.fairtix.events.domain.EventStatus;
import com.fairtix.events.dto.UpdateEventRequest;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.domain.HoldStatus;
import com.fairtix.notifications.application.EmailTemplateService;
import com.fairtix.notifications.application.NotificationGate;
import com.fairtix.notifications.domain.NotificationCategory;
import com.fairtix.organizations.domain.OrgPermission;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.domain.OrganizationMember;
import com.fairtix.organizations.infrastructure.OrganizationMemberRepository;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.refunds.application.RefundService;
import com.fairtix.users.domain.Role;
import com.fairtix.users.infrastructure.UserRepository;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatHoldRepository;
import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.domain.TicketStatus;
import com.fairtix.tickets.domain.TicketTransferRequest;
import com.fairtix.tickets.domain.TransferStatus;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.tickets.infrastructure.TicketTransferRequestRepository;
import com.fairtix.performers.domain.Performer;
import com.fairtix.performers.infrastructure.PerformerRepository;
import com.fairtix.users.domain.User;
import com.fairtix.venues.domain.Venue;
import com.fairtix.venues.infrastructure.VenueRepository;

import jakarta.transaction.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class EventService {

  private static final Logger log = LoggerFactory.getLogger(EventService.class);
  private static final int TRANSFER_LOOKUP_CHUNK = 1000;

  private final EventRepository repository;
  private final VenueRepository venueRepository;
  private final PerformerRepository performerRepository;
  private final SeatHoldRepository seatHoldRepository;
  private final TicketRepository ticketRepository;
  private final RefundService refundService;
  private final EmailTemplateService emailTemplateService;
  private final NotificationGate notificationGate;
  private final OrganizationMemberRepository organizationMemberRepository;
  private final OrganizationRepository organizationRepository;
  private final UserRepository userRepository;
  private final TicketTransferRequestRepository transferRepository;
  private final AuditService auditService;

  @PersistenceContext
  private EntityManager entityManager;

  public EventService(EventRepository repository, VenueRepository venueRepository,
      PerformerRepository performerRepository,
      SeatHoldRepository seatHoldRepository, TicketRepository ticketRepository,
      RefundService refundService,
      EmailTemplateService emailTemplateService,
      NotificationGate notificationGate,
      OrganizationMemberRepository organizationMemberRepository,
      OrganizationRepository organizationRepository,
      UserRepository userRepository,
      TicketTransferRequestRepository transferRepository,
      AuditService auditService) {
    this.repository = repository;
    this.venueRepository = venueRepository;
    this.performerRepository = performerRepository;
    this.seatHoldRepository = seatHoldRepository;
    this.ticketRepository = ticketRepository;
    this.refundService = refundService;
    this.emailTemplateService = emailTemplateService;
    this.notificationGate = notificationGate;
    this.organizationMemberRepository = organizationMemberRepository;
    this.organizationRepository = organizationRepository;
    this.userRepository = userRepository;
    this.transferRepository = transferRepository;
    this.auditService = auditService;
  }

  public Event createEvent(String title, Instant startTime, UUID venueId, UUID organizerId,
      boolean queueRequired, Integer queueCapacity, Integer maxTicketsPerUser) {
    return createEvent(title, startTime, venueId, organizerId,
        resolveDefaultOrganizationId(organizerId),
        queueRequired, queueCapacity, maxTicketsPerUser);
  }

  public Event createEvent(String title, Instant startTime, UUID venueId, UUID organizerId,
      UUID organizationId,
      boolean queueRequired, Integer queueCapacity, Integer maxTicketsPerUser) {
    Venue venue = venueId != null
        ? venueRepository.findById(venueId)
            .orElseThrow(() -> new ResourceNotFoundException("Venue not found: " + venueId))
        : null;
    Event event = new Event(title, venue, startTime, organizerId);
    if (organizationId != null) {
      event.setOrganizationId(organizationId);
    }
    event.updateQueueSettings(queueRequired, queueCapacity);
    event.setMaxTicketsPerUser(maxTicketsPerUser);
    return repository.save(event);
  }

  /**
   * Best-effort default: if the organizer is a member of exactly one organization,
   * attach the event there so it isn't orphaned. Multi-org members must call the
   * 5-arg overload with an explicit organization to avoid ambiguity. Returns null
   * when no membership exists; the event becomes an orphan and verifyOwnership
   * falls back to the legacy organizer_id check.
   */
  private UUID resolveDefaultOrganizationId(UUID organizerId) {
    if (organizerId == null) return null;
    var memberships = organizationMemberRepository.findAllByUserId(organizerId);
    return memberships.size() == 1 ? memberships.get(0).getOrganizationId() : null;
  }

  public Event getEvent(UUID id) {
    return repository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Event not found"));
  }

  public Event update(UUID id, UpdateEventRequest request, UUID callerId) {
    Event event = repository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + id));
    verifyOwnership(event, callerId);
    event.update(request.title(), request.startTime());
    if (request.queueRequired() != null || request.queueCapacity() != null) {
      boolean qr = request.queueRequired() != null ? request.queueRequired() : event.isQueueRequired();
      event.updateQueueSettings(qr, request.queueCapacity());
    }
    if (request.maxTicketsPerUser() != null) {
      event.setMaxTicketsPerUser(request.maxTicketsPerUser());
    }
    if (request.performerIds() != null) {
      List<Performer> performers = performerRepository.findAllById(request.performerIds());
      event.getPerformers().clear();
      event.getPerformers().addAll(performers);
    }
    return event;
  }

  public void delete(UUID id, UUID callerId) {
    Event event = repository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + id));
    verifyOwnership(event, callerId);
    repository.delete(event);
  }

  // --- Lifecycle transitions ---

  public Event publishEvent(UUID eventId, UUID callerId) {
    Event event = repository.findById(eventId)
        .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));
    verifyOwnership(event, callerId);
    // M2-07: organizers cannot publish until Stripe Connect KYC is complete.
    // Legacy events with no organization_id (pre-M1 backfill edge case) skip
    // the check rather than block; new events always carry an org.
    UUID orgId = event.getOrganizationId();
    if (orgId != null) {
      Organization org = organizationRepository.findById(orgId).orElse(null);
      if (org != null && org.getStripeConnectAccountId() != null && !org.isStripeChargesEnabled()) {
        throw new IllegalStateException(
            "Stripe Connect onboarding is incomplete for this organization. "
                + "Finish your Stripe setup before publishing events.");
      }
      // Phase H: only ACTIVE orgs can publish. PENDING_REVIEW awaits admin sign-off.
      // Pre-M2 orgs in PENDING are grandfathered as ACTIVE for back-compat.
      if (org != null) {
        var status = org.getStatus();
        if (status == com.fairtix.organizations.domain.OrganizationStatus.PENDING_REVIEW) {
          throw new IllegalStateException(
              "Your organization is awaiting admin approval and cannot publish events yet.");
        }
        if (status == com.fairtix.organizations.domain.OrganizationStatus.REJECTED
            || status == com.fairtix.organizations.domain.OrganizationStatus.SUSPENDED) {
          throw new IllegalStateException(
              "This organization is not active and cannot publish events.");
        }
      }
    }
    event.publish();
    return event;
  }

  public Event activateEvent(UUID eventId, UUID callerId) {
    Event event = repository.findById(eventId)
        .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));
    verifyOwnership(event, callerId);
    event.activate();
    return event;
  }

  public Event completeEvent(UUID eventId, UUID callerId) {
    Event event = repository.findById(eventId)
        .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));
    verifyOwnership(event, callerId);
    event.complete();
    return event;
  }

  /**
   * Lock order, the one {@code RefundService.finalise} uses: the open refund rows of the
   * event's orders (in {@code processCancellationRefunds}), then the event's VALID ticket rows
   * one by one in {@link UUID#compareTo} order (finalise's order; PostgreSQL's own uuid order
   * differs, so no {@code ORDER BY id} lock over several tickets), then their pending transfer
   * requests in request-id order. {@code TransferService.acceptTransfer} takes one ticket,
   * then its request. No cap lock is taken here, and none may be taken after a ticket lock.
   */
  public Event cancelEvent(UUID eventId, UUID callerId, String reason) {
    Event event = repository.findById(eventId)
        .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));
    verifyOwnership(event, callerId);
    event.cancel(reason);

    // Release all ACTIVE holds for this event
    List<SeatHold> activeHolds = seatHoldRepository.findAllBySeat_Event_IdAndStatus(eventId, HoldStatus.ACTIVE);
    for (SeatHold hold : activeHolds) {
      hold.setStatus(HoldStatus.RELEASED);
      hold.getSeat().setStatus(SeatStatus.AVAILABLE);
    }
    seatHoldRepository.saveAll(activeHolds);

    // Approve a refund for every completed order (locks the open refund rows first)
    refundService.processCancellationRefunds(eventId, callerId);

    // Lock and re-read the tickets before changing them: an acceptance or a refund may have
    // committed since they were read, and writing a stale copy back would undo it.
    List<Ticket> ticketsToNotify = lockValidTickets(eventId);

    // Cancel the locked tickets (finalising the refunds approved above marks them REFUNDED
    // later), then their pending transfers: a cancelled ticket can't change hands.
    for (Ticket ticket : ticketsToNotify) {
      ticket.setStatus(TicketStatus.CANCELLED);
    }
    ticketRepository.saveAll(ticketsToNotify);
    cancelPendingTransfers(ticketsToNotify, eventId, callerId);

    // Send cancellation emails after the transaction commits
    List<Ticket> emailTargets = List.copyOf(ticketsToNotify);
    String eventTitle = event.getTitle();
    String eventDate = event.getStartTime().toString();
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        for (Ticket ticket : emailTargets) {
          sendCancellationEmail(ticket.getUser(), eventTitle, eventDate);
        }
      }
    });

    return event;
  }

  /**
   * The event's VALID tickets, each locked in {@link UUID#compareTo} order and read after its
   * lock. A ticket that stopped being VALID while its lock was awaited is left out.
   */
  private List<Ticket> lockValidTickets(UUID eventId) {
    List<UUID> candidates = ticketRepository.findValidTicketIdsByEvent(eventId).stream()
        .sorted()
        .toList();
    List<Ticket> valid = new ArrayList<>();
    for (UUID id : candidates) {
      if (ticketRepository.lockIfValid(id).isEmpty()) {
        continue;
      }
      Ticket ticket = entityManager.find(Ticket.class, id);
      // Under open-in-view the context may hold a copy read before the lock.
      entityManager.refresh(ticket);
      valid.add(ticket);
    }
    return valid;
  }

  /**
   * Ends the pending transfer requests of the cancelled tickets. Each request row is locked
   * in request-id order and re-read, as {@code RefundService.finalise} does, so a reject,
   * cancel or expiry committed since the query is not overwritten.
   */
  private void cancelPendingTransfers(List<Ticket> tickets, UUID eventId, UUID actorId) {
    if (tickets.isEmpty()) {
      return;
    }
    // In chunks: one bind parameter per ticket would pass the JDBC driver's limit on a
    // stadium-size event and fail the whole cancellation.
    List<UUID> ticketIds = tickets.stream().map(Ticket::getId).toList();
    List<TicketTransferRequest> found = new ArrayList<>();
    for (int from = 0; from < ticketIds.size(); from += TRANSFER_LOOKUP_CHUNK) {
      found.addAll(transferRepository.findByTicket_IdInAndStatus(
          ticketIds.subList(from, Math.min(from + TRANSFER_LOOKUP_CHUNK, ticketIds.size())),
          TransferStatus.PENDING));
    }
    List<TicketTransferRequest> pending = found.stream()
        .sorted(Comparator.comparing(TicketTransferRequest::getId))
        .toList();
    for (TicketTransferRequest request : pending) {
      entityManager.refresh(request, LockModeType.PESSIMISTIC_WRITE);
      if (request.getStatus() != TransferStatus.PENDING) {
        continue;
      }
      request.setStatus(TransferStatus.CANCELLED);
      request.setResolvedAt(Instant.now());
      transferRepository.save(request);
      auditService.logInCurrentTransaction(actorId, "TRANSFER_CANCELLED", "TICKET",
          request.getTicket().getId(), "event cancelled, event=" + eventId);
    }
  }

  private void sendCancellationEmail(User user, String eventTitle, String eventDate) {
    try {
      String body = emailTemplateService.buildEventCancelledEmail(user.getEmail(), eventTitle, eventDate);
      notificationGate.sendEmail(user.getId(), NotificationCategory.EVENT_CANCELLED,
          user.getEmail(), "Event Cancelled: " + eventTitle, body);
    } catch (Exception ex) {
      log.warn("Failed to send cancellation email to {}: {}", user.getEmail(), ex.getMessage());
    }
  }

  public Event archiveEvent(UUID eventId, UUID callerId) {
    Event event = repository.findById(eventId)
        .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));
    verifyOwnership(event, callerId);
    event.archive();
    return event;
  }

  // --- Search ---

  public Page<Event> search(
      String venue,
      String title,
      String performerName,
      Boolean upcoming,
      EventStatus status,
      boolean adminView,
      Pageable pageable) {

    Specification<Event> spec = (root, query, cb) -> {

      List<Predicate> predicates = new ArrayList<>();

      if (venue != null && !venue.isBlank()) {
        predicates.add(
            cb.like(
                cb.lower(root.join("venue", JoinType.LEFT).get("name")),
                "%" + venue.toLowerCase() + "%"));
      }

      if (title != null && !title.isBlank()) {
        predicates.add(
            cb.like(
                cb.lower(root.get("title")),
                "%" + title.toLowerCase() + "%"));
      }

      if (performerName != null && !performerName.isBlank()) {
        predicates.add(
            cb.like(
                cb.lower(root.join("performers", JoinType.LEFT).get("name")),
                "%" + performerName.toLowerCase() + "%"));
        query.distinct(true);
      }

      if (upcoming == null || upcoming) {
        predicates.add(
            cb.greaterThan(
                root.get("startTime"),
                Instant.now()));
      }

      if (status != null) {
        // Explicit status filter requested
        predicates.add(cb.equal(root.get("status"), status));
      } else if (!adminView) {
        // Public view: only show PUBLISHED and ACTIVE events
        predicates.add(root.get("status").in(EventStatus.PUBLISHED, EventStatus.ACTIVE));
      }

      return cb.and(predicates.toArray(new Predicate[0]));
    };

    return repository.findAll(spec, pageable);
  }

  /**
   * Checks that {@code callerId} may write to {@code event}. Resolution order:
   * <ol>
   *   <li>Platform admin always wins.</li>
   *   <li>If the event is bound to an organization, the caller must be a member
   *       whose role carries {@link OrgPermission#EVENTS_WRITE}.</li>
   *   <li>Legacy fallback: if the event has no organization yet (an orphan from
   *       before V33 ran, or one created via the legacy API), the caller must
   *       match the original {@code organizer_id} the way the pre-org model worked.</li>
   * </ol>
   * Anything else throws {@link org.springframework.security.access.AccessDeniedException}.
   */
  /**
   * Checks that {@code callerId} may write to {@code event}. Resolution order:
   * <ol>
   *   <li>Platform admin always wins (looked up by user id, not by Spring Security
   *       context, so the check works inside transactional service calls).</li>
   *   <li>If the event is bound to an organization, the caller must be a member
   *       whose role carries {@link OrgPermission#EVENTS_WRITE}.</li>
   *   <li>Legacy fallback: if the event has no organization yet (orphan from
   *       before V33 ran, or one created via the legacy API), match the original
   *       {@code organizer_id} the way the pre-org model worked. {@code null}
   *       organizerId + {@code null} callerId is allowed to preserve the pre-M1
   *       service-layer behaviour exercised by older tests.</li>
   * </ol>
   */
  private void verifyOwnership(Event event, UUID callerId) {
    if (callerId != null && userRepository.findById(callerId)
        .map(u -> u.getRole() == Role.ADMIN)
        .orElse(false)) {
      return;
    }

    UUID orgId = event.getOrganizationId();
    if (orgId != null) {
      if (callerId == null) {
        throw new org.springframework.security.access.AccessDeniedException(
            "Authentication required to modify this event");
      }
      OrganizationMember member = organizationMemberRepository
          .findByOrganizationIdAndUserId(orgId, callerId)
          .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException(
              "You are not a member of this event's organization"));
      if (!member.getRole().has(OrgPermission.EVENTS_WRITE)) {
        throw new org.springframework.security.access.AccessDeniedException(
            "Your role (" + member.getRole() + ") does not allow modifying events");
      }
      return;
    }

    // Orphan event. Fall back to the pre-org owner check.
    if (event.getOrganizerId() != null && !event.getOrganizerId().equals(callerId)) {
      throw new org.springframework.security.access.AccessDeniedException(
          "You do not own this event");
    }
  }
}
