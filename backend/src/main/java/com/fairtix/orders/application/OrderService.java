package com.fairtix.orders.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.events.domain.Event;
import com.fairtix.inventory.application.SeatHoldConflictException;
import com.fairtix.inventory.domain.HoldStatus;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatHoldRepository;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.notifications.application.EmailTemplateService;
import com.fairtix.notifications.application.NotificationGate;
import com.fairtix.notifications.domain.NotificationCategory;
import com.fairtix.orders.domain.Order;
import com.fairtix.orders.domain.OrderStatus;
import com.fairtix.orders.infrastructure.OrderRepository;
import com.fairtix.payments.application.PaymentFailedException;
import com.fairtix.payments.application.PaymentSimulationService;
import com.fairtix.payments.application.StripePaymentService;
import com.fairtix.payments.domain.PaymentRecord;
import com.fairtix.payments.domain.PaymentStatus;
import com.fairtix.queue.application.QueueService;
import com.fairtix.tickets.application.TicketService;
import com.fairtix.tickets.domain.TicketStatus;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OrderService {

  private static final Logger log = LoggerFactory.getLogger(OrderService.class);
  private static final DateTimeFormatter EMAIL_DATE_FMT =
      DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a z").withZone(ZoneId.of("UTC"));

  private final OrderRepository orderRepository;
  private final SeatHoldRepository seatHoldRepository;
  private final SeatRepository seatRepository;
  private final UserRepository userRepository;
  private final TicketService ticketService;
  private final TicketRepository ticketRepository;
  private final PaymentSimulationService paymentSimulationService;
  private final StripePaymentService stripePaymentService;
  private final QueueService queueService;
  private final AuditService auditService;
  private final NotificationGate notificationGate;
  private final EmailTemplateService emailTemplateService;

  public OrderService(OrderRepository orderRepository,
      SeatHoldRepository seatHoldRepository,
      SeatRepository seatRepository,
      UserRepository userRepository,
      TicketService ticketService,
      TicketRepository ticketRepository,
      Optional<PaymentSimulationService> paymentSimulationService,
      StripePaymentService stripePaymentService,
      QueueService queueService,
      AuditService auditService,
      NotificationGate notificationGate,
      EmailTemplateService emailTemplateService) {
    this.orderRepository = orderRepository;
    this.seatHoldRepository = seatHoldRepository;
    this.seatRepository = seatRepository;
    this.userRepository = userRepository;
    this.ticketService = ticketService;
    this.ticketRepository = ticketRepository;
    this.paymentSimulationService = paymentSimulationService.orElse(null);
    this.stripePaymentService = stripePaymentService;
    this.queueService = queueService;
    this.auditService = auditService;
    this.notificationGate = notificationGate;
    this.emailTemplateService = emailTemplateService;
  }

  /**
   * Original order creation (MVP path, no payment). Always succeeds with $0 total.
   */
  @Transactional
  public Order createOrder(UUID userId, List<UUID> holdIds) {
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

    List<SeatHold> holds = validateHolds(userId, holdIds);
    validatePurchaseCaps(userId, holds);

    for (SeatHold hold : holds) {
      Seat seat = hold.getSeat();
      if (seat.getStatus() == SeatStatus.SOLD) {
        throw new SeatHoldConflictException(
            "Seat " + seat.getId() + " has already been sold");
      }
      if (seat.getStatus() != SeatStatus.BOOKED) {
        throw new SeatHoldConflictException(
            "Seat " + seat.getId() + " is not in BOOKED state (status: " + seat.getStatus() + ")");
      }
      seat.setStatus(SeatStatus.SOLD);
      seatRepository.save(seat);
    }

    BigDecimal totalAmount = holds.stream()
        .map(hold -> hold.getSeat().getPrice())
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    Order order = new Order(user, holdIds, totalAmount, "USD");
    order = orderRepository.save(order);
    ticketService.issueTickets(order, holds);
    auditService.log(userId, "CREATE", "ORDER", order.getId(),
        "Order completed: " + holdIds.size() + " hold(s), total=" + totalAmount + " USD");
    sendOrderConfirmationEmail(user, order, holds);
    return order;
  }

  /**
   * Creates an order with simulated payment processing.
   *
   * Flow:
   * 1. Validate holds belong to user and are CONFIRMED
   * 2. Transition seats BOOKED → SOLD
   * 3. Create order as PENDING
   * 4. Process simulated payment
   * 5. On success: mark order COMPLETED, issue tickets
   * 6. On failure/cancel: mark order CANCELLED, rollback seats to BOOKED
   */
  @Transactional(noRollbackFor = PaymentFailedException.class)
  public Order createOrderWithPayment(UUID userId, List<UUID> holdIds,
      PaymentStatus simulatedOutcome) {

    User user = userRepository.findById(userId)
        .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

    List<SeatHold> holds = validateHolds(userId, holdIds);
    validatePurchaseCaps(userId, holds);

    // Transition seats from BOOKED → SOLD
    for (SeatHold hold : holds) {
      Seat seat = hold.getSeat();
      if (seat.getStatus() == SeatStatus.SOLD) {
        throw new SeatHoldConflictException(
            "Seat " + seat.getId() + " has already been sold");
      }
      if (seat.getStatus() != SeatStatus.BOOKED) {
        throw new SeatHoldConflictException(
            "Seat " + seat.getId() + " is not in BOOKED state (status: " + seat.getStatus() + ")");
      }
      seat.setStatus(SeatStatus.SOLD);
      seatRepository.save(seat);
    }

    BigDecimal totalAmount = holds.stream()
        .map(hold -> hold.getSeat().getPrice())
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    // Create order as PENDING
    Order order = new Order(user, holdIds, totalAmount, "USD", OrderStatus.PENDING);
    order = orderRepository.save(order);

    // Process simulated payment
    if (paymentSimulationService == null) {
      throw new IllegalStateException("Payment simulation is not available in this environment");
    }
    PaymentRecord payment = paymentSimulationService.processPayment(
        order.getId(), userId, totalAmount, "USD", simulatedOutcome);

    if (payment.getStatus() == PaymentStatus.SUCCESS) {
      order.setStatus(OrderStatus.COMPLETED);
      orderRepository.save(order);
      ticketService.issueTickets(order, holds);
      for (SeatHold hold : holds) {
        hold.setStatus(HoldStatus.RELEASED);
        seatHoldRepository.save(hold);
        if (hold.getSeat().getEvent().isQueueRequired()) {
          queueService.completeQueueEntry(hold.getSeat().getEvent().getId(), userId);
        }
      }
      auditService.log(userId, "CREATE", "ORDER", order.getId(),
          "Order completed via payment: " + holdIds.size() + " hold(s), total=" + totalAmount
              + " USD, txn=" + payment.getTransactionId());
      sendOrderConfirmationEmail(user, order, holds);
    } else {
      // Rollback: mark order cancelled and revert seats to BOOKED
      order.setStatus(OrderStatus.CANCELLED);
      orderRepository.save(order);
      for (SeatHold hold : holds) {
        Seat seat = hold.getSeat();
        seat.setStatus(SeatStatus.BOOKED);
        seatRepository.save(seat);
      }
      auditService.log(userId, "CANCEL", "ORDER", order.getId(),
          "Payment " + payment.getStatus() + ": " + payment.getFailureReason()
              + ", txn=" + payment.getTransactionId());
      throw new PaymentFailedException(
          payment.getFailureReason(), payment.getStatus(), payment.getTransactionId());
    }

    return order;
  }

  /**
   * Creates an order for a payment already confirmed by Stripe.
   * Payment verification must happen before calling this method.
   */
  @Transactional
  public Order createOrderWithStripePayment(UUID userId, List<UUID> holdIds, String paymentIntentId) {
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

    List<SeatHold> holds = validateHolds(userId, holdIds);
    validatePurchaseCaps(userId, holds);

    for (SeatHold hold : holds) {
      Seat seat = hold.getSeat();
      if (seat.getStatus() == SeatStatus.SOLD) {
        throw new SeatHoldConflictException("Seat " + seat.getId() + " has already been sold");
      }
      if (seat.getStatus() != SeatStatus.BOOKED) {
        throw new SeatHoldConflictException(
            "Seat " + seat.getId() + " is not in BOOKED state (status: " + seat.getStatus() + ")");
      }
      seat.setStatus(SeatStatus.SOLD);
      seatRepository.save(seat);
    }

    BigDecimal totalAmount = holds.stream()
        .map(hold -> hold.getSeat().getPrice())
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    Order order = new Order(user, holdIds, totalAmount, "USD", OrderStatus.PENDING);
    order = orderRepository.save(order);

    stripePaymentService.recordStripePayment(paymentIntentId, order.getId(), userId, totalAmount, "USD");

    order.setStatus(OrderStatus.COMPLETED);
    orderRepository.save(order);
    ticketService.issueTickets(order, holds);

    for (SeatHold hold : holds) {
      hold.setStatus(HoldStatus.RELEASED);
      seatHoldRepository.save(hold);
      if (hold.getSeat().getEvent().isQueueRequired()) {
        queueService.completeQueueEntry(hold.getSeat().getEvent().getId(), userId);
      }
    }

    auditService.log(userId, "CREATE", "ORDER", order.getId(),
        "Order completed via Stripe: " + holdIds.size() + " hold(s), total=" + totalAmount
            + " USD, txn=" + paymentIntentId);
    sendOrderConfirmationEmail(user, order, holds);
    return order;
  }

  /**
   * Defers email sending to after the transaction commits so that SMTP latency
   * or failures never block or roll back the order creation.
   */
  private void sendOrderConfirmationEmail(User user, Order order, List<SeatHold> holds) {
    try {
      // Capture all data needed for the email now (entities may be detached after commit)
      Event event = holds.get(0).getSeat().getEvent();
      UUID userId = user.getId();
      String toEmail = user.getEmail();
      String orderId = order.getId().toString();
      String eventTitle = event.getTitle();
      String venueName = event.getVenue() != null ? event.getVenue().getName() : "TBD";
      String eventDate = EMAIL_DATE_FMT.format(event.getStartTime());
      String total = order.getTotalAmount().toPlainString() + " " + order.getCurrency();

      List<String> seatLines = holds.stream()
          .map(h -> {
            Seat s = h.getSeat();
            return s.getSection() + " / Row " + s.getRowLabel() + " / Seat " + s.getSeatNumber()
                + " — $" + s.getPrice().toPlainString();
          })
          .toList();

      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          try {
            String body = emailTemplateService.buildOrderConfirmationEmail(
                toEmail, orderId, eventTitle, venueName, eventDate, seatLines, total);
            notificationGate.sendEmail(userId, NotificationCategory.ORDER,
                toEmail, "Your FairTix order is confirmed!", body);
          } catch (Exception ex) {
            log.warn("Failed to send order confirmation email for order {}: {}", orderId, ex.getMessage());
          }
        }
      });
    } catch (Exception ex) {
      log.warn("Failed to prepare order confirmation email for order {}: {}", order.getId(), ex.getMessage());
    }
  }

  private List<SeatHold> validateHolds(UUID userId, List<UUID> holdIds) {
    List<SeatHold> holds = holdIds.stream()
        .map(holdId -> seatHoldRepository.findByIdAndOwnerId(holdId, userId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Hold not found or does not belong to you: " + holdId)))
        .toList();

    for (SeatHold hold : holds) {
      if (hold.getStatus() != HoldStatus.CONFIRMED) {
        throw new IllegalArgumentException(
            "Hold " + hold.getId() + " is not confirmed (status: " + hold.getStatus() + ")");
      }
    }
    return holds;
  }

  private void validatePurchaseCaps(UUID userId, List<SeatHold> holds) {
    // Group by event ID (UUID) to avoid JPA proxy identity issues
    Map<UUID, Long> newTicketsByEventId = holds.stream()
        .collect(Collectors.groupingBy(
            hold -> hold.getSeat().getEvent().getId(),
            Collectors.counting()));

    // Build a lookup map from eventId → Event for cap/title retrieval
    Map<UUID, Event> eventById = holds.stream()
        .map(hold -> hold.getSeat().getEvent())
        .collect(Collectors.toMap(Event::getId, e -> e, (a, b) -> a));

    for (Map.Entry<UUID, Long> entry : newTicketsByEventId.entrySet()) {
      UUID eventId = entry.getKey();
      Event event = eventById.get(eventId);
      Integer cap = event.getMaxTicketsPerUser();
      if (cap == null) continue;

      long existing = ticketRepository.countByUser_IdAndEvent_IdAndStatusNot(
          userId, eventId, TicketStatus.CANCELLED);
      long newCount = entry.getValue();
      if (existing + newCount > cap) {
        throw new PurchaseCapExceededException(
            "Purchase cap of " + cap + " ticket(s) per user exceeded for event: " + event.getTitle()
                + " (already purchased: " + existing + ", requested: " + newCount + ")");
      }
    }
  }

  public Order getOrder(UUID orderId, UUID userId) {
    Order order = orderRepository.findById(orderId)
        .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
    if (!order.getUser().getId().equals(userId)) {
      throw new OrderNotFoundException("Order not found: " + orderId);
    }
    return order;
  }

  public List<Order> listOrders(UUID userId) {
    return orderRepository.findAllByUser_IdOrderByCreatedAtDesc(userId);
  }

  public List<com.fairtix.orders.dto.OrderResponse> listOrdersWithDetails(UUID userId) {
    return orderRepository.findAllByUser_IdOrderByCreatedAtDesc(userId).stream()
        .map(order -> com.fairtix.orders.dto.OrderResponse.withDetails(
            order, ticketRepository.findAllByOrder_Id(order.getId())))
        .toList();
  }

  public com.fairtix.orders.dto.OrderResponse getOrderWithDetails(UUID orderId, UUID userId) {
    Order order = getOrder(orderId, userId);
    return com.fairtix.orders.dto.OrderResponse.withDetails(
        order, ticketRepository.findAllByOrder_Id(orderId));
  }
}
