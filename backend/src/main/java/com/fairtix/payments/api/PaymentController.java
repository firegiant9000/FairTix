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
import com.fairtix.payments.application.OnlinePaymentIntentService;
import com.fairtix.payments.application.PaymentFailedException;
import com.fairtix.payments.application.PaymentIntentNotOwnedException;
import com.fairtix.payments.application.PaymentRefundedException;
import com.fairtix.payments.application.PaymentSimulationService;
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
  private final OnlinePaymentIntentService onlinePaymentIntentService;

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
      VerifiedEmailGuard verifiedEmailGuard,
      OnlinePaymentIntentService onlinePaymentIntentService) {
    this.onlinePaymentIntentService = onlinePaymentIntentService;
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
   *   <li>Verification returned false: {@code STRIPE_ORDER_FAILED} with
   *       {@code error=PaymentNotVerified}. The client submits only after Stripe
   *       reports the intent succeeded, so this is almost always an amount
   *       mismatch (a seat price changed, or the holds changed after the intent was
   *       created) and the customer has paid. A direct API call with an intent that
   *       never succeeded lands here too; the runbook checks the intent in Stripe
   *       before refunding.
   *   <li>A paid intent with no owner, past the grace window for intents created
   *       before owner binding: {@code STRIPE_ORDER_FAILED} with
   *       {@code error=PaymentIntentUnbound}. Checkout answers 409
   *       {@code PAYMENT_NOT_USABLE}, and no later attempt can use this intent.
   *   <li>Anything else: {@code STRIPE_ORDER_FAILED}.
   * </ul>
   */
  private void auditStripeOrderNotCreated(UUID userId, java.util.List<UUID> holdIds,
      String paymentIntentId, RuntimeException ex) {
    if (paymentRecordRepository.findByTransactionId(paymentIntentId).isPresent()) {
      return;
    }
    boolean busy = ex instanceof LockBusyException;
    String summary;
    String error;
    if (busy) {
      summary = "Charge succeeded, order not created yet (purchase-cap lock busy; a retry can complete it): ";
      error = ex.getClass().getSimpleName();
    } else if (ex instanceof PaymentIntentNotOwnedException) {
      summary = "Charge succeeded, but the intent predates owner binding and is past its grace"
          + " window, so no checkout can complete it; order not created: ";
      error = "PaymentIntentUnbound";
    } else if (ex instanceof PaymentNotVerifiedException) {
      summary = "Payment not verified (amount_received differs from the holds' total,"
          + " or the intent has not succeeded); order not created: ";
      error = "PaymentNotVerified";
    } else {
      summary = "Charge succeeded but order creation failed: ";
      error = ex.getClass().getSimpleName();
    }
    String details = summary
        + "stripe_intent=" + paymentIntentId
        + ", holdIds=" + holdIds + ", error=" + error;
    auditService.log(userId, busy ? "STRIPE_ORDER_BUSY" : "STRIPE_ORDER_FAILED", "PAYMENT", null,
        details);
  }

  /**
   * {@link #auditStripeOrderNotCreated}, never throwing: pool exhaustion or a
   * database outage can fail the audit too. The charge then stays traceable in
   * the log, and the caller answers with the original error.
   */
  private void auditStripeOrderNotCreatedSafely(UUID userId, java.util.List<UUID> holdIds,
      String paymentIntentId, RuntimeException ex) {
    try {
      auditStripeOrderNotCreated(userId, holdIds, paymentIntentId, ex);
    } catch (RuntimeException auditFailure) {
      String what = ex instanceof PaymentNotVerifiedException
          ? "Payment not verified (check the intent in Stripe),"
          : "Charge succeeded,";
      log.error("{} order not created, and the audit row could not be written:"
          + " stripe_intent={}, holdIds={}, error={}", what, paymentIntentId, holdIds,
          ex.getClass().getSimpleName(), auditFailure);
      ex.addSuppressed(auditFailure);
    }
  }

  /**
   * Ends the intent's ACTIVE {@code online_payment_intents} row once its order exists, so the
   * retention job can delete it. Never throws: the order is already committed and the customer
   * has paid, so a failure here is logged and the checkout still succeeds. A row left ACTIVE is
   * harmless (its holds are consumed) and only costs storage.
   */
  private void completeOnlineIntentSafely(String paymentIntentId) {
    try {
      onlinePaymentIntentService.markCompleted(paymentIntentId);
    } catch (RuntimeException ex) {
      log.warn("Order created, but its online intent row could not be ended: stripe_intent={}",
          paymentIntentId, ex);
    }
  }

  /**
   * Records a completed online sale against the org's rolling sales cap. The cap was
   * pre-checked at intent creation; the sale is recorded only once the order exists, so
   * abandoned carts don't count. Never throws: the order is committed and the customer has
   * paid. A failure is logged at ERROR and audited as {@code SALES_LEDGER_WRITE_FAILED} so the
   * ledger can be reconciled, because a resubmit is answered by the replay path, which does not
   * record the sale. If the audit fails too, the ERROR log is the only trace.
   */
  private void recordOnlineSaleSafely(UUID userId, java.util.List<UUID> holdIds,
      boolean contextResolved, ConnectContext resolvedContext, UUID orderId,
      long amountCents, String paymentIntentId) {
    String orgId = null;
    try {
      // Resolved here only when the lookup before the order failed.
      ConnectContext context = contextResolved
          ? resolvedContext
          : resolveConnectContext(holdIds, userId, amountCents);
      if (context == null) {
        return;
      }
      orgId = context.organizationId();
      salesCapService.recordSale(UUID.fromString(orgId), amountCents,
          OrgSalesCapService.CHANNEL_ONLINE, paymentIntentId);
    } catch (RuntimeException ex) {
      String details = "Order created, sale not recorded against the sales cap:"
          + " stripe_intent=" + paymentIntentId + ", orgId=" + orgId
          + ", amountCents=" + amountCents + ", error=" + ex.getClass().getSimpleName();
      log.error("{} (orderId={})", details, orderId, ex);
      try {
        auditService.log(userId, "SALES_LEDGER_WRITE_FAILED", "ORDER", orderId, details);
      } catch (RuntimeException auditFailure) {
        log.error("The SALES_LEDGER_WRITE_FAILED audit row could not be written:"
            + " stripe_intent={}, orderId={}", paymentIntentId, orderId, auditFailure);
      }
    }
  }

  /** Verification returned false; answered as 402 like before. */
  private static final class PaymentNotVerifiedException extends ResponseStatusException {
    PaymentNotVerifiedException() {
      super(HttpStatus.PAYMENT_REQUIRED, "Stripe payment has not succeeded");
    }
  }

  /**
   * Answers a checkout whose intent already has an order: the first response was
   * lost (dropped connection, gateway timeout) and the customer pressed Complete
   * Order again. Without this, every resubmit fails on the holds the order already
   * released and writes a false {@code STRIPE_ORDER_FAILED} row.
   *
   * <p>Returns null when the intent has no payment record yet. Returns the existing
   * order when the record belongs to this user and the order covers exactly the
   * requested holds. Any other match is refused with a 409 that names neither the
   * order nor its owner.
   */
  private PaymentResponse replayStripeCheckout(UUID userId, java.util.List<UUID> holdIds,
      String paymentIntentId) {
    if (paymentIntentId == null || paymentIntentId.isBlank()) {
      return null;
    }
    var record = paymentRecordRepository.findByTransactionId(paymentIntentId).orElse(null);
    if (record == null) {
      return null;
    }
    Order order = userId.equals(record.getUserId())
        ? orderService.findOrderForHolds(record.getOrderId(), holdIds).orElse(null)
        : null;
    if (order == null) {
      log.warn("Checkout refused: stripe_intent={} already paid for a different order"
          + " (requesting user {})", paymentIntentId, userId);
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "This payment has already been used for a different order");
    }
    if (order.getStatus() == OrderStatus.REFUNDED) {
      // An order refunded through FairTix gets the client's refunded screen, not a
      // confirmation. The replay does not call Stripe: a refund or dispute made
      // only at Stripe leaves the order COMPLETED, and the order (and its tickets)
      // does exist, so it is returned. The charge.refunded webhook alerts on such
      // refunds (REFUND_ON_LIVE_ORDER).
      throw new PaymentRefundedException(paymentIntentId, "order refunded");
    }
    return PaymentResponse.from(record, order.getStatus());
  }

  @Operation(summary = "Create Stripe PaymentIntent",
      description = "Calculates total for the given holds and returns a Stripe client secret. "
          + "Only available when stripe.enabled=true.")
  @ApiResponse(responseCode = "200", description = "Client secret returned")
  @ApiResponse(responseCode = "409", description = "PAYMENT_RETRY_CONFLICT: try again")
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

    // The hold-set hash de-duplicates ids but the amount sums them, so reject duplicates.
    if (request.holdIds().stream().distinct().count() != request.holdIds().size()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate hold ids");
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
    String clientSecret = onlinePaymentIntentService.clientSecretFor(
        principal.getUserId(), request.holdIds(), amountCents, connect);
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

    // Before the queue check: creating the order completes the queue entry, so a
    // resubmit after a lost response would otherwise be refused there.
    if (stripeEnabled) {
      PaymentResponse replay = replayStripeCheckout(
          principal.getUserId(), request.holdIds(), request.paymentIntentId());
      if (replay != null) {
        return replay;
      }
    }

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
      // Throws PaymentIntentNotOwnedException (409) for an intent that wasn't created
      // for this user, before any order.
      boolean verified;
      try {
        verified = stripePaymentService.verifyPaymentSucceeded(
            paymentIntentId, expectedAmountCents, principal.getUserId());
      } catch (PaymentIntentNotOwnedException notOwned) {
        // Only a paid pre-deploy intent past the grace window goes on record: it has no
        // owner, so no checkout can ever complete it, and its charge needs a refund.
        // Another user's or channel's intent is not this caller's charge to report.
        if (PaymentIntentNotOwnedException.REASON_UNBOUND.equals(notOwned.getReason())
            && notOwned.isPaid()) {
          auditStripeOrderNotCreatedSafely(
              principal.getUserId(), request.holdIds(), paymentIntentId, notOwned);
        }
        throw notOwned;
      }
      if (!verified) {
        // Almost always a paid charge whose amount no longer matches the holds, so
        // it goes on record for stripe_charges_without_order like a failed order.
        var notVerified = new PaymentNotVerifiedException();
        auditStripeOrderNotCreatedSafely(
            principal.getUserId(), request.holdIds(), paymentIntentId, notVerified);
        throw notVerified;
      }
      Order order;
      ConnectContext saleContext = null;
      boolean saleContextResolved = false;
      try {
        // Before the order: holds from more than one org are refused (400) while no order
        // exists yet, so the charge goes on record for a refund like any failed order.
        // Any other lookup failure must not cost the customer their order: the context is
        // resolved again after the order commits.
        try {
          saleContext = resolveConnectContext(
              request.holdIds(), principal.getUserId(), expectedAmountCents);
          saleContextResolved = true;
        } catch (ResponseStatusException refused) {
          throw refused;
        } catch (RuntimeException lookupFailure) {
          log.warn("Sales-cap context lookup failed before the order; retrying after it:"
              + " stripe_intent={}, error={}", paymentIntentId,
              lookupFailure.getClass().getSimpleName());
        }
        order = createStripeOrder(principal.getUserId(), request.holdIds(), paymentIntentId);
      } catch (RuntimeException ex) {
        auditStripeOrderNotCreatedSafely(
            principal.getUserId(), request.holdIds(), paymentIntentId, ex);
        // A concurrent submit with the same intent may have created the order while
        // this one failed on the released holds or the unique transaction id. If the
        // lookup itself fails (or refuses), keep the original failure on record.
        PaymentResponse replay;
        try {
          replay = replayStripeCheckout(principal.getUserId(), request.holdIds(), paymentIntentId);
        } catch (RuntimeException replayFailure) {
          if (replayFailure instanceof ResponseStatusException
              || replayFailure instanceof PaymentRefundedException) {
            // A deliberate answer about this intent (other user, other holds, refunded).
            // Expected on double submits, so no stack trace.
            log.warn("Checkout failed and the replay refused the intent:"
                + " stripe_intent={}, error={}, replay={}", paymentIntentId,
                ex.getClass().getSimpleName(), replayFailure.getClass().getSimpleName());
            replayFailure.addSuppressed(ex);
            throw replayFailure;
          }
          log.warn("Checkout failed and the replay lookup failed:"
              + " stripe_intent={}, error={}", paymentIntentId,
              ex.getClass().getSimpleName(), replayFailure);
          ex.addSuppressed(replayFailure);
          throw ex;
        }
        if (replay != null) {
          return replay;
        }
        throw ex;
      }
      // The order has committed. Before the sales-cap write, so a failure there can't skip it.
      completeOnlineIntentSafely(paymentIntentId);
      recordOnlineSaleSafely(principal.getUserId(), request.holdIds(), saleContextResolved,
          saleContext, order.getId(), expectedAmountCents, paymentIntentId);
      var record = paymentRecordRepository.findByOrderId(order.getId()).orElseThrow();
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
