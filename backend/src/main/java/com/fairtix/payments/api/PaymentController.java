package com.fairtix.payments.api;

import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.inventory.infrastructure.SeatHoldRepository;
import com.fairtix.orders.application.OrderService;
import com.fairtix.orders.domain.Order;
import com.fairtix.orders.domain.OrderStatus;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.domain.Plan;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.payments.application.PaymentFailedException;
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

  private final OrderService orderService;
  private final PaymentRecordRepository paymentRecordRepository;
  private final UserRepository userRepository;
  private final SeatHoldRepository seatHoldRepository;
  private final QueueService queueService;
  private final StripePaymentService stripePaymentService;
  private final OrganizationRepository organizationRepository;

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
      OrganizationRepository organizationRepository) {
    this.orderService = orderService;
    this.paymentRecordRepository = paymentRecordRepository;
    this.userRepository = userRepository;
    this.seatHoldRepository = seatHoldRepository;
    this.queueService = queueService;
    this.stripePaymentService = stripePaymentService;
    this.organizationRepository = organizationRepository;
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

  @Operation(summary = "Create Stripe PaymentIntent",
      description = "Calculates total for the given holds and returns a Stripe client secret. "
          + "Only available when stripe.enabled=true.")
  @ApiResponse(responseCode = "200", description = "Client secret returned")
  @ApiResponse(responseCode = "501", description = "Stripe not enabled")
  @PostMapping("/api/payments/intent")
  public PaymentIntentResponse createPaymentIntent(
      @AuthenticationPrincipal CustomUserPrincipal principal,
      @Valid @RequestBody PaymentIntentRequest request) {

    if (!stripeEnabled) {
      throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
          "Stripe is not enabled in this environment");
    }

    BigDecimal total = request.holdIds().stream()
        .map(id -> seatHoldRepository.findByIdAndOwnerId(id, principal.getUserId())
            .map(h -> h.getSeat().getPrice())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Hold not found or not owned by current user: " + id)))
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    long amountCents = total.multiply(BigDecimal.valueOf(100)).longValue();
    ConnectContext connect = resolveConnectContext(
        request.holdIds(), principal.getUserId(), amountCents);
    String clientSecret = stripePaymentService.createPaymentIntent(amountCents, "usd", connect);
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

    userRepository.findById(principal.getUserId()).ifPresent(user -> {
      if (!user.isEmailVerified()) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
            "Email address not verified. Please verify your email before purchasing tickets.");
      }
    });

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
      Order order = orderService.createOrderWithStripePayment(
          principal.getUserId(), request.holdIds(), paymentIntentId);
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
