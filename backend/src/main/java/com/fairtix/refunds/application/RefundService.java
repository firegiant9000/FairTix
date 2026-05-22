package com.fairtix.refunds.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.fraud.application.RiskScoringService;
import com.fairtix.fraud.domain.RiskTier;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.notifications.application.EmailTemplateService;
import com.fairtix.notifications.application.NotificationGate;
import com.fairtix.notifications.domain.NotificationCategory;
import com.fairtix.orders.domain.Order;
import com.fairtix.orders.domain.OrderStatus;
import com.fairtix.orders.infrastructure.OrderRepository;
import com.fairtix.payments.application.StripePaymentService;
import com.fairtix.payments.domain.PaymentRecord;
import com.fairtix.payments.domain.PaymentStatus;
import com.fairtix.payments.infrastructure.PaymentRecordRepository;
import com.fairtix.refunds.domain.RefundRequest;
import com.fairtix.refunds.domain.RefundStatus;
import com.fairtix.refunds.infrastructure.RefundRepository;
import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.domain.TicketStatus;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class RefundService {

  private static final Logger log = LoggerFactory.getLogger(RefundService.class);

  private final RefundRepository refundRepository;
  private final OrderRepository orderRepository;
  private final TicketRepository ticketRepository;
  private final SeatRepository seatRepository;
  private final PaymentRecordRepository paymentRecordRepository;
  private final UserRepository userRepository;
  private final AuditService auditService;
  private final NotificationGate notificationGate;
  private final EmailTemplateService emailTemplateService;
  private final RiskScoringService riskScoringService;
  private final StripePaymentService stripePaymentService;

  private static final long STRIPE_REFUND_WINDOW_DAYS = 180L;

  @Value("${fairtix.refund.enabled:true}")
  private boolean refundEnabled;

  @Value("${fairtix.refund.time-window-days:14}")
  private int refundWindowDays;

  @Value("${fairtix.refund.auto-approve-threshold:50.00}")
  private BigDecimal autoApproveThreshold;

  public RefundService(RefundRepository refundRepository,
      OrderRepository orderRepository,
      TicketRepository ticketRepository,
      SeatRepository seatRepository,
      PaymentRecordRepository paymentRecordRepository,
      UserRepository userRepository,
      AuditService auditService,
      NotificationGate notificationGate,
      EmailTemplateService emailTemplateService,
      RiskScoringService riskScoringService,
      StripePaymentService stripePaymentService) {
    this.refundRepository = refundRepository;
    this.orderRepository = orderRepository;
    this.ticketRepository = ticketRepository;
    this.seatRepository = seatRepository;
    this.paymentRecordRepository = paymentRecordRepository;
    this.userRepository = userRepository;
    this.auditService = auditService;
    this.notificationGate = notificationGate;
    this.emailTemplateService = emailTemplateService;
    this.riskScoringService = riskScoringService;
    this.stripePaymentService = stripePaymentService;
  }

  @Transactional
  public RefundRequest requestRefund(UUID userId, UUID orderId, String reason) {
    if (!refundEnabled) {
      throw new RefundNotEligibleException("Refunds are not currently enabled.");
    }

    Order order = orderRepository.findById(orderId)
        .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

    if (!order.getUser().getId().equals(userId)) {
      throw new IllegalArgumentException("Order not found: " + orderId);
    }

    if (order.getStatus() != OrderStatus.COMPLETED) {
      throw new RefundNotEligibleException(
          "Refunds can only be requested for completed orders. Order status: " + order.getStatus());
    }

    // Check no pending refund exists for this order
    refundRepository.findByOrderIdAndStatusIn(orderId,
        List.of(RefundStatus.REQUESTED, RefundStatus.PENDING_MANUAL, RefundStatus.APPROVED))
        .ifPresent(existing -> {
          throw new RefundNotEligibleException(
              "A refund request is already pending for this order (status: " + existing.getStatus() + ")");
        });

    // Validate no tickets are USED
    List<Ticket> tickets = ticketRepository.findAllByOrder_Id(orderId);
    boolean anyUsed = tickets.stream().anyMatch(t -> t.getStatus() == TicketStatus.USED);
    if (anyUsed) {
      throw new RefundNotEligibleException("Cannot refund an order with used tickets.");
    }

    // Validate refund time window
    Instant cutoff = Instant.now().minus(refundWindowDays, ChronoUnit.DAYS);
    if (order.getCreatedAt().isBefore(cutoff)) {
      throw new RefundNotEligibleException(
          "Refund window of " + refundWindowDays + " days has expired.");
    }

    BigDecimal amount = order.getTotalAmount();
    RefundRequest refund = new RefundRequest(orderId, userId, amount, reason);
    refund = refundRepository.save(refund);

    auditService.log(userId, "REFUND_REQUESTED", "REFUND", refund.getId(),
        "Refund requested for order " + orderId + ", amount=" + amount);

    RiskTier tier = riskScoringService.getTier(userId);
    if (tier == RiskTier.HIGH || tier == RiskTier.CRITICAL) {
      refund.holdForManualReview();
      refundRepository.save(refund);
      auditService.log(userId, "REFUND_HELD_FRAUD_RISK", "REFUND", refund.getId(),
          "tier=" + tier);
      return refund;
    }

    // Auto-approve if amount is below the configured threshold
    if (amount.compareTo(autoApproveThreshold) <= 0) {
      refund.approve(userId, "Auto-approved: amount within threshold");
      refundRepository.save(refund);
      auditService.log(userId, "REFUND_AUTO_APPROVED", "REFUND", refund.getId(),
          "Auto-approved refund for order " + orderId + ", amount=" + amount);
      processRefund(refund, userId);
    } else {
      sendRefundRequestedEmail(userId, refund);
    }
    return refund;
  }

  @Transactional
  public RefundRequest reviewRefund(UUID adminUserId, UUID refundId, boolean approved, String notes) {
    RefundRequest refund = refundRepository.findById(refundId)
        .orElseThrow(() -> new IllegalArgumentException("Refund request not found: " + refundId));

    if (refund.getStatus() != RefundStatus.REQUESTED && refund.getStatus() != RefundStatus.PENDING_MANUAL) {
      throw new IllegalStateException(
          "Refund is not in REQUESTED or PENDING_MANUAL status (current: " + refund.getStatus() + ")");
    }

    if (approved) {
      refund.approve(adminUserId, notes);
      refundRepository.save(refund);
      auditService.log(adminUserId, "REFUND_APPROVED", "REFUND", refundId,
          "Refund approved for order " + refund.getOrderId());
      processRefund(refund, adminUserId);
    } else {
      refund.reject(adminUserId, notes);
      refundRepository.save(refund);
      auditService.log(adminUserId, "REFUND_REJECTED", "REFUND", refundId,
          "Refund rejected for order " + refund.getOrderId() + ": " + notes);
      sendRefundRejectedEmail(refund, notes);
    }

    return refund;
  }

  @Transactional
  public void processRefund(RefundRequest refund, UUID actorId) {
    // Idempotency: if Stripe refund already initiated, do nothing.
    if (refund.getStripeRefundId() != null && !refund.getStripeRefundId().isBlank()) {
      log.info("processRefund: refund {} already has stripeRefundId={}, skipping",
          refund.getId(), refund.getStripeRefundId());
      return;
    }

    Order order = orderRepository.findById(refund.getOrderId())
        .orElseThrow(() -> new IllegalStateException("Order not found during refund processing"));

    // Stripe 180-day refund window guard.
    if (order.getCreatedAt() != null
        && order.getCreatedAt().isBefore(Instant.now().minus(STRIPE_REFUND_WINDOW_DAYS, ChronoUnit.DAYS))) {
      throw new RefundNotEligibleException(
          "Original payment is older than " + STRIPE_REFUND_WINDOW_DAYS
              + " days and cannot be refunded through Stripe.");
    }

    // Look up original Stripe PaymentIntent before mutating any state.
    PaymentRecord originalPayment = paymentRecordRepository.findByOrderId(refund.getOrderId()).orElse(null);
    String stripeRefundId = null;
    boolean stripeRefundInitiated = false;

    if (stripePaymentService.isStripeEnabled()
        && originalPayment != null
        && originalPayment.getTransactionId() != null
        && originalPayment.getTransactionId().startsWith("pi_")) {
      try {
        long amountCents = refund.getAmount()
            .multiply(BigDecimal.valueOf(100))
            .setScale(0, java.math.RoundingMode.HALF_UP)
            .longValueExact();
        com.stripe.model.Refund stripeRefund = stripePaymentService.createRefund(
            originalPayment.getTransactionId(), amountCents, refund.getReason());
        stripeRefundId = stripeRefund.getId();
        stripeRefundInitiated = true;
      } catch (RuntimeException ex) {
        log.error("Stripe refund failed for refund {} (order {}): {}",
            refund.getId(), refund.getOrderId(), ex.getMessage());
        auditService.log(actorId, "REFUND_STRIPE_FAILED", "REFUND", refund.getId(),
            "Stripe refund call failed: " + ex.getMessage());
        // Leave refund in APPROVED; do NOT mark order REFUNDED.
        throw ex;
      }
    }

    order.setStatus(OrderStatus.REFUNDED);
    orderRepository.save(order);

    // Create a negative-amount PaymentRecord for audit trail
    if (originalPayment != null) {
      PaymentRecord refundRecord = new PaymentRecord(
          refund.getOrderId(), refund.getUserId(),
          refund.getAmount().negate(), order.getCurrency(),
          PaymentStatus.REFUNDED,
          stripeRefundId != null
              ? stripeRefundId
              : "REFUND-" + refund.getId().toString().substring(0, 8).toUpperCase(),
          null);
      paymentRecordRepository.save(refundRecord);
    }

    // Mark tickets as REFUNDED and release seats
    List<Ticket> tickets = ticketRepository.findAllByOrder_Id(refund.getOrderId());
    for (Ticket ticket : tickets) {
      ticket.setStatus(TicketStatus.REFUNDED);
      ticketRepository.save(ticket);
      Seat seat = ticket.getSeat();
      seat.setStatus(SeatStatus.AVAILABLE);
      seatRepository.save(seat);
    }

    if (stripeRefundId != null) {
      refund.setStripeRefundId(stripeRefundId);
    }

    if (stripeRefundInitiated) {
      // Stripe is processing; the charge.refunded webhook will flip to COMPLETED
      // and send the "refund complete" email. Email user that the refund was initiated.
      refundRepository.save(refund);
      auditService.log(actorId, "REFUND_INITIATED", "REFUND", refund.getId(),
          "Stripe refund initiated for order " + refund.getOrderId()
              + ", amount=" + refund.getAmount() + ", stripeRefundId=" + stripeRefundId);
      sendRefundInitiatedEmail(refund);
    } else {
      // Stripe disabled or no Stripe PaymentIntent (e.g. cash/manual order):
      // complete the refund inline to preserve existing behavior.
      refund.complete();
      refundRepository.save(refund);
      auditService.log(actorId, "REFUND_COMPLETED", "REFUND", refund.getId(),
          "Refund completed for order " + refund.getOrderId() + ", amount=" + refund.getAmount());
      sendRefundCompletedEmail(refund);
    }
  }

  /**
   * Called by EventService when an event is cancelled.
   * Auto-approves and processes refunds for all COMPLETED orders on this event.
   */
  @Transactional
  public void processCancellationRefunds(UUID eventId, UUID actorId) {
    List<Ticket> tickets = ticketRepository.findAllByEvent_IdAndStatus(eventId, TicketStatus.VALID);
    // Group by order and create/process refunds
    tickets.stream()
        .map(t -> t.getOrder())
        .filter(o -> o.getStatus() == OrderStatus.COMPLETED)
        .distinct()
        .forEach(order -> {
          // Skip only if this order has already been definitively refunded
          boolean alreadyRefunded = refundRepository.findAllByOrderId(order.getId()).stream()
              .anyMatch(r -> r.getStatus() == RefundStatus.COMPLETED);
          if (alreadyRefunded) return;

          try {
            RefundRequest refund = new RefundRequest(
                order.getId(), order.getUser().getId(),
                order.getTotalAmount(), "Event cancelled");
            refund.approve(actorId, "Automatic refund due to event cancellation");
            refund = refundRepository.save(refund);
            processRefund(refund, actorId);
          } catch (Exception ex) {
            log.error("Failed to auto-process cancellation refund for order {}: {}",
                order.getId(), ex.getMessage());
          }
        });
  }

  public List<RefundRequest> getUserRefunds(UUID userId) {
    return refundRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
  }

  public RefundRequest getRefund(UUID refundId, UUID userId) {
    RefundRequest refund = refundRepository.findById(refundId)
        .orElseThrow(() -> new IllegalArgumentException("Refund not found: " + refundId));
    if (!refund.getUserId().equals(userId)) {
      throw new IllegalArgumentException("Refund not found: " + refundId);
    }
    return refund;
  }

  public Page<RefundRequest> adminListRefunds(RefundStatus status, Pageable pageable) {
    if (status != null) {
      return refundRepository.findAllByStatusOrderByCreatedAtDesc(status, pageable);
    }
    return refundRepository.findAllByOrderByCreatedAtDesc(pageable);
  }

  // -------------------------------------------------------------------------

  private void sendRefundRequestedEmail(UUID userId, RefundRequest refund) {
    try {
      User user = userRepository.findById(userId).orElse(null);
      if (user == null) return;
      String body = emailTemplateService.buildRefundRequestedEmail(
          user.getEmail(), refund.getOrderId().toString(), refund.getAmount().toPlainString(), refund.getReason());
      notificationGate.sendEmail(user.getId(), NotificationCategory.REFUND,
          user.getEmail(), "Your FairTix refund request was received", body);
    } catch (Exception ex) {
      log.warn("Failed to send refund-requested email for refund {}: {}", refund.getId(), ex.getMessage());
    }
  }

  private void sendRefundInitiatedEmail(RefundRequest refund) {
    try {
      User user = userRepository.findById(refund.getUserId()).orElse(null);
      if (user == null) return;
      String body = emailTemplateService.buildRefundInitiatedEmail(
          user.getEmail(), refund.getOrderId().toString(), refund.getAmount().toPlainString());
      notificationGate.sendEmail(user.getId(), NotificationCategory.REFUND,
          user.getEmail(), "Your FairTix refund has been initiated", body);
    } catch (Exception ex) {
      log.warn("Failed to send refund-initiated email for refund {}: {}", refund.getId(), ex.getMessage());
    }
  }

  private void sendRefundCompletedEmail(RefundRequest refund) {
    try {
      User user = userRepository.findById(refund.getUserId()).orElse(null);
      if (user == null) return;
      String body = emailTemplateService.buildRefundCompletedEmail(
          user.getEmail(), refund.getOrderId().toString(), refund.getAmount().toPlainString());
      notificationGate.sendEmail(user.getId(), NotificationCategory.REFUND,
          user.getEmail(), "Your FairTix refund has been processed", body);
    } catch (Exception ex) {
      log.warn("Failed to send refund-completed email for refund {}: {}", refund.getId(), ex.getMessage());
    }
  }

  private void sendRefundRejectedEmail(RefundRequest refund, String adminNotes) {
    try {
      User user = userRepository.findById(refund.getUserId()).orElse(null);
      if (user == null) return;
      String body = emailTemplateService.buildRefundRejectedEmail(
          user.getEmail(), refund.getOrderId().toString(), refund.getReason(), adminNotes);
      notificationGate.sendEmail(user.getId(), NotificationCategory.REFUND,
          user.getEmail(), "Update on your FairTix refund request", body);
    } catch (Exception ex) {
      log.warn("Failed to send refund-rejected email for refund {}: {}", refund.getId(), ex.getMessage());
    }
  }
}
