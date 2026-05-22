package com.fairtix.events.application;

import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.events.domain.Event;
import com.fairtix.events.domain.EventStatus;
import com.fairtix.events.dto.UpdateEventRequest;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.domain.HoldStatus;
import com.fairtix.notifications.application.EmailTemplateService;
import com.fairtix.notifications.application.NotificationGate;
import com.fairtix.notifications.domain.NotificationCategory;
import com.fairtix.refunds.application.RefundService;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatHoldRepository;
import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.domain.TicketStatus;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.performers.domain.Performer;
import com.fairtix.performers.infrastructure.PerformerRepository;
import com.fairtix.users.domain.User;
import com.fairtix.venues.domain.Venue;
import com.fairtix.venues.infrastructure.VenueRepository;

import jakarta.transaction.Transactional;
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
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class EventService {

  private static final Logger log = LoggerFactory.getLogger(EventService.class);

  private final EventRepository repository;
  private final VenueRepository venueRepository;
  private final PerformerRepository performerRepository;
  private final SeatHoldRepository seatHoldRepository;
  private final TicketRepository ticketRepository;
  private final RefundService refundService;
  private final EmailTemplateService emailTemplateService;
  private final NotificationGate notificationGate;

  public EventService(EventRepository repository, VenueRepository venueRepository,
      PerformerRepository performerRepository,
      SeatHoldRepository seatHoldRepository, TicketRepository ticketRepository,
      RefundService refundService,
      EmailTemplateService emailTemplateService,
      NotificationGate notificationGate) {
    this.repository = repository;
    this.venueRepository = venueRepository;
    this.performerRepository = performerRepository;
    this.seatHoldRepository = seatHoldRepository;
    this.ticketRepository = ticketRepository;
    this.refundService = refundService;
    this.emailTemplateService = emailTemplateService;
    this.notificationGate = notificationGate;
  }

  public Event createEvent(String title, Instant startTime, UUID venueId, UUID organizerId,
      boolean queueRequired, Integer queueCapacity, Integer maxTicketsPerUser) {
    Venue venue = venueId != null
        ? venueRepository.findById(venueId)
            .orElseThrow(() -> new ResourceNotFoundException("Venue not found: " + venueId))
        : null;
    Event event = new Event(title, venue, startTime, organizerId);
    event.updateQueueSettings(queueRequired, queueCapacity);
    event.setMaxTicketsPerUser(maxTicketsPerUser);
    return repository.save(event);
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

  public Event cancelEvent(UUID eventId, UUID callerId, String reason) {
    Event event = repository.findById(eventId)
        .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));
    verifyOwnership(event, callerId);
    event.cancel(reason);

    // Collect ticket holders before any status changes so all are notified
    List<Ticket> ticketsToNotify = ticketRepository.findAllByEvent_IdAndStatus(eventId, TicketStatus.VALID);

    // Release all ACTIVE holds for this event
    List<SeatHold> activeHolds = seatHoldRepository.findAllBySeat_Event_IdAndStatus(eventId, HoldStatus.ACTIVE);
    for (SeatHold hold : activeHolds) {
      hold.setStatus(HoldStatus.RELEASED);
      hold.getSeat().setStatus(SeatStatus.AVAILABLE);
    }
    seatHoldRepository.saveAll(activeHolds);

    // Cancel all VALID tickets and auto-process refunds for completed orders
    refundService.processCancellationRefunds(eventId, callerId);

    // Mark any remaining VALID tickets (those without completed orders) as CANCELLED
    List<Ticket> validTickets = ticketRepository.findAllByEvent_IdAndStatus(eventId, TicketStatus.VALID);
    for (Ticket ticket : validTickets) {
      ticket.setStatus(TicketStatus.CANCELLED);
    }
    ticketRepository.saveAll(validTickets);

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

  private void verifyOwnership(Event event, UUID callerId) {
    if (event.getOrganizerId() != null && !event.getOrganizerId().equals(callerId)) {
      throw new org.springframework.security.access.AccessDeniedException(
          "You do not own this event");
    }
  }
}
