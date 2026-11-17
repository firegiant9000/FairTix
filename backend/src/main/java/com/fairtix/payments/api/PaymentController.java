package com.fairtix.payments.api;

import com.fairtix.audit.application.AuditService;
import com.fairtix.auth.application.VerifiedEmailGuard;
import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.common.LockBusyException;
import com.fairtix.inventory.application.SeatHoldService;
import com.fairtix.inventory.infrastructure.SeatHoldRepository;
import com.fairtix.orders.application.OrderService;
import com.fairtix.orders.domain.Order;
import com.fairtix.orders.domain.OrderStatus;
import com.fairtix.organizations.application.OrgSalesCapService;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.domain.Plan;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.payments.application.PaymentFailedException;
import com.fairtix.payments.application.PaymentSimulationService;
import com.fairtix.payments.application.StripeIdempotencyKeys;
import com.fairtix.payments.application.StripePaymentService;
import com.fairtix.payments.application.StripePaymentService.ConnectContext;
import com.fairtix.payments.dto.PaymentIntentRequest;
import com.fairtix.payments.dto.PaymentIntentResponse;
import com.fairtix.payments.dto.PaymentRequest;
import com.fairtix.payments.dto.PaymentResponse;
import com.fairtix.payments.infrastructure.PaymentRecordRepository;
import com.fairtix.queue.application.QueueService;
import com.fairtix.users.infrastructure.UserRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Tag(name = "Payments", description = "Payment processing")
@RestController
@PreAuthorize("isAuthenticated()")
public class PaymentController {

  private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

  /** Attempts to create the order for a succeeded charge while the cap lock is busy. */
  private static final int STRIPE_ORDER_ATTEMPTS = 3;

  private final OrderService orderService;
  private final PaymentRecordRepository paymentRecordRepository;
  private final UserRepository userRepository;
  private final SeatHoldRepository seatHoldRepository;
  private final QueueService queueService;
  private final StripePaymentService stripePaymentService;
  private final OrganizationRepository organizationRepository;
  private final OrgSalesCapService salesCapService;
  private final SeatHoldService seatHoldService;
  private final AuditService auditService;
  private final VerifiedEmailGuard verifiedEmailGuard;

  @Autowired(required = false)
  private PaymentSimulationService paymentSimulationService;

  @Value("${fairtix.payment.allow-simulated-outcome:false}")
  private boolean allowSimulatedOutcome;

  @Value("${stripe.enabled:false}")
  private boolean stripeEnabled;

  public PaymentController(OrderService orderService,
      PaymentRecordRepository paymentRecordRepository,
      UserRepository userRepository,
      SeatHoldRepository seatHoldRepository,
      QueueService queueService,
      StripePaymentService stripePaymentService,
      OrganizationRepository organizationRepository,
      OrgSalesCapService salesCapService,
      SeatHoldService seatHoldService,
      AuditService auditService,
      VerifiedEmailGuard verifiedEmailGuard) {
    this.verifiedEmailGuard = verifiedEmailGuard;
    this.seatHoldService = seatHoldService;
    this.auditService = auditService;
    this.orderService = orderService;
    this.paymentRecordRepository = paymentRecordRepository;
    this.userRepository = userRepository;
    this.seatHoldRepository = seatHoldRepository;
    this.queueService = queueService;
    this.stripePaymentService = stripePaymentService;
    this.organizationRepository = organizationRepository;
    this.salesCapService = salesCapService;
  }

  /**
   * Resolves the Stripe Connect context for a checkout.
   * Returns null when the holds' event has no org, or the org has no
   * connected account. Throws 400 if holds span multiple orgs.
   */
  private ConnectContext resolveConnectContext(java.util.List<UUID> holdIds, UUID userId, long amountCents) {
    Set<UUID> orgIds = holdIds.stream()
        .map(id -> seatHoldRepository.findByIdAndOwnerId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Hold not found or not owned by current user: " + id)))
        .map(h -> h.getSeat().getEvent().getOrganizationId())
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    if (orgIds.size() > 1) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Cannot checkout holds from multiple organizations in one transaction");
    }
    if (orgIds.isEmpty()) return null;
    Organization org = organizationRepository.findById(orgIds.iterator().next()).orElse(null);
    if (org == null || org.getStripeConnectAccountId() == null) return null;
    Plan plan = org.getPlan() == null ? Plan.FREE : org.getPlan();
    long feeCents = (amountCents * plan.getPlatformFeeBps()) / 10_000L;
    return new ConnectContext(
        org.getId().toString(),
        org.getStripeConnectAccountId(),
        feeCents,
        org.getName());
  }

  /**
   * Creates the order for a charge that has already succeeded. If the purchase-cap
   * lock is busy (another request from the same user for the same event is still
   * running), nothing was written and the attempt is simply repeated: the customer
   * has paid, so a "try again" answer is the last resort, not the first. Each
   * attempt is a transaction of its own and waits up to the lock's wait limit.
   */
  private Order createStripeOrder(UUID userId, java.util.List<UUID> holdIds, String paymentIntentId) {
    for (int attempt = 1; ; attempt++) {
      try {
        return orderService.createOrderWithStripePayment(userId, holdIds, paymentIntentId);
      } catch (LockBusyException ex) {
        if (attempt == STRIPE_ORDER_ATTEMPTS) {
          throw ex;
        }
      }
    }
  }

  /**
   * Puts a succeeded charge whose order was not created on record, after the
   * order's rollback, so it can be refunded (refund-on-failure is not automated
   * yet). The runbook check {@code stripe_charges_without_order} reads both actions.
   *
   * <ul>
   *   <li>The intent already has a payment record: another request with the same
   *       intent created the order (a double submit that lost the race). Nothing is
   *       owed, so nothing is written.
   *   <li>The purchase-cap lock stayed busy: {@code STRIPE_ORDER_BUSY}. The holds
   *       are still confirmed and the answer is a retryable 409, so the customer's
   *       next attempt can still create the order. This charge needs a refund only
   *       if no attempt ever does.
   *   <li>Anything else: {@code STRIPE_ORDER_FAILED}.
   * </ul>
   */
  private void auditStripeOrderNotCreated(UUID userId, java.util.List<UUID> holdIds,
      String paymentIntentId, RuntimeException ex) {
    if (paymentRecordRepository.findByTransactionId(paymentIntentId).isPresent()) {
      return;
    }
    boolean busy = ex instanceof LockBusyException;
    String details = (busy
        ? "Charge succeeded, order not created yet (purchase-cap lock busy; a retry can complete it): "
        : "Charge succeeded but order creation failed: ")
        + "stripe_intent=" + paymentIntentId
        + ", holdIds=" + holdIds + ", error=" + ex.getClass().getSimpleName();
    auditService.log(userId, busy ? "STRIPE_ORDER_BUSY" : "STRIPE_ORDER_FAILED", "PAYMENT", null,
        details);
  }

  @Operation(summary = "Create Stripe PaymentIntent",
      description = "Calculates total for the given holds and returns a Stripe client secret. "
          + "Only available when stripe.enabled=true.")
  @ApiResponse(responseCode = "200", description = "Client secret returned")
  @ApiResponse(responseCode = "501", description = "Stripe not enabled")
  @PostMapping("/api/payments/intent")
  public PaymentIntentResponse createPaymentIntent(
      @AuthenticationPrincipal CustomUserPrincipal principal,
      @Valid @RequestBody PaymentIntentRequest request) {

    // Before the hold extension: an unverified account must not keep seats alive.
    verifiedEmailGuard.requireVerified(principal.getUserId(), "purchasing tickets");

    if (!stripeEnabled) {
      throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
          "Stripe is not enabled in this environment");
    }

    // Keeps the CONFIRMED-hold sweep from freeing these seats while the customer pays.
    seatHoldService.extendConfirmedHoldsForPayment(request.holdIds(), principal.getUserId());

    // Before any charge: checkout validates the purchase cap after the payment has
    // succeeded, so an order that is already over the cap is refused here instead.
    orderService.assertWithinPurchaseCaps(principal.getUserId(), request.holdIds());

    BigDecimal total = request.holdIds().stream()
        .map(id -> seatHoldRepository.findByIdAndOwnerId(id, principal.getUserId())
            .map(h -> h.getSeat().getPrice())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Hold not found or not owned by current user: " + id)))
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    long amountCents = total.multiply(BigDecimal.valueOf(100)).longValue();
    ConnectContext connect = resolveConnectContext(
        request.holdIds(), principal.getUserId(), amountCents);
    if (connect != null) {
      salesCapService.checkCanCharge(UUID.fromString(connect.organizationId()), amountCents);
    }
    String clientSecret = stripePaymentService.createPaymentIntent(amountCents, "usd", connect,
        StripeIdempotencyKeys.onlineIntent(principal.getUserId(), request.holdIds(), amountCents,
            connect));
    return new PaymentIntentResponse(clientSecret);
  }

  @Operation(summary = "Create order with payment",
      description = "When Stripe is enabled: verifies the PaymentIntent and issues tickets. "
          + "When disabled: runs simulated payment processing.")
  @ApiResponse(responseCode = "201", description = "Payment succeeded, order completed")
  @ApiResponse(responseCode = "402", description = "Payment failed or cancelled")
  @ApiResponse(responseCode = "400", description = "Invalid holds or missing paymentIntentId")
  @PostMapping("/api/payments/checkout")
  @ResponseStatus(HttpStatus.CREATED)
  public PaymentResponse checkout(
      @AuthenticationPrincipal CustomUserPrincipal principal,
      @Valid @RequestBody PaymentRequest request) {

    verifiedEmailGuard.requireVerified(principal.getUserId(), "purchasing tickets");

    request.holdIds().stream()
        .flatMap(holdId -> seatHoldRepository.findById(holdId).stream())
        .map(hold -> hold.getSeat().getEvent())
        .filter(event -> event.isQueueRequired())
        .distinct()
        .forEach(event -> {
          if (!queueService.hasCheckoutClearance(event.getId(), principal.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Queue admission required. You must be admitted from the waiting room before purchasing tickets.");
          }
        });

    if (stripeEnabled) {
      String paymentIntentId = request.paymentIntentId();
      if (paymentIntentId == null || paymentIntentId.isBlank()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "paymentIntentId is required when Stripe is enabled");
      }
      BigDecimal expectedTotal = request.holdIds().stream()
          .map(id -> seatHoldRepository.findByIdAndOwnerId(id, principal.getUserId())
              .map(h -> h.getSeat().getPrice())
              .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                  "Hold not found or not owned by current user: " + id)))
          .reduce(BigDecimal.ZERO, BigDecimal::add);
      long expectedAmountCents = expectedTotal.multiply(BigDecimal.valueOf(100)).longValue();
      if (!stripePaymentService.verifyPaymentSucceeded(paymentIntentId, expectedAmountCents)) {
        throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
            "Stripe payment has not succeeded");
      }
      Order order;
      try {
        order = createStripeOrder(principal.getUserId(), request.holdIds(), paymentIntentId);
      } catch (RuntimeException ex) {
        try {
          auditStripeOrderNotCreated(principal.getUserId(), request.holdIds(), paymentIntentId, ex);
        } catch (RuntimeException auditFailure) {
          // Pool exhaustion or a database outage can fail the audit too. Keep the
          // charge traceable in the log and answer with the original error.
          log.error("Charge succeeded, order not created, and the audit row could not be written:"
              + " stripe_intent={}, holdIds={}, error={}", paymentIntentId, request.holdIds(),
              ex.getClass().getSimpleName(), auditFailure);
          ex.addSuppressed(auditFailure);
        }
        throw ex;
      }
      var record = paymentRecordRepository.findByOrderId(order.getId()).orElseThrow();
      // Record against the rolling sales cap. The cap was pre-checked at intent
      // creation, but recording happens here once the charge is verified so we
      // don't credit usage to abandoned carts.
      ConnectContext successContext = resolveConnectContext(
          request.holdIds(), principal.getUserId(), expectedAmountCents);
      if (successContext != null) {
        salesCapService.recordSale(
            UUID.fromString(successContext.organizationId()),
            expectedAmountCents,
            OrgSalesCapService.CHANNEL_ONLINE,
            paymentIntentId);
      }
      return PaymentResponse.from(record, order.getStatus());
    }

    // Simulation path
    if (paymentSimulationService == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Payment simulation is not available in this environment");
    }
    if (!allowSimulatedOutcome && request.simulatedOutcome() != null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "simulatedOutcome is not allowed in this environment");
    }

    try {
      var outcome = allowSimulatedOutcome ? request.simulatedOutcome() : null;
      Order order = orderService.createOrderWithPayment(
          principal.getUserId(), request.holdIds(), outcome);

      var record = paymentRecordRepository.findByOrderId(order.getId()).orElseThrow();
      return PaymentResponse.from(record, order.getStatus());
    } catch (PaymentFailedException ex) {
      var record = paymentRecordRepository.findByTransactionId(ex.getTransactionId())
          .orElseThrow();
      throw new PaymentProcessingException(PaymentResponse.from(record, OrderStatus.CANCELLED));
    }
  }
}
