package com.fairtix.payments.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.payments.domain.PaymentRecord;
import com.fairtix.payments.domain.PaymentStatus;
import com.fairtix.payments.infrastructure.PaymentRecordRepository;
import com.stripe.Stripe;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.RefundCollection;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.RefundListParams;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class StripePaymentService {

  private static final Logger log = LoggerFactory.getLogger(StripePaymentService.class);

  /** Stripe refund metadata key carrying our refund request id. */
  static final String REFUND_ID_METADATA = "fairtix_refund_id";

  /** PaymentIntent metadata key naming a non-online channel (box office). */
  static final String CHANNEL_METADATA = "fairtix_channel";

  /** Online PaymentIntent metadata key carrying the user it was created for. */
  static final String USER_ID_METADATA = "fairtix_user_id";

  /** {@link #isPaidBy}'s request options: fail fast rather than hold the caller's connection. */
  static final RequestOptions PAID_CHECK_OPTIONS = RequestOptions.builder()
      .setConnectTimeout(2_000)
      .setReadTimeout(5_000)
      .setMaxNetworkRetries(0)
      .build();

  @Value("${stripe.enabled:false}")
  private boolean stripeEnabled;

  @Value("${stripe.secret-key:}")
  private String secretKey;

  private final PaymentRecordRepository paymentRecordRepository;
  private final AuditService auditService;

  public StripePaymentService(PaymentRecordRepository paymentRecordRepository,
      AuditService auditService) {
    this.paymentRecordRepository = paymentRecordRepository;
    this.auditService = auditService;
  }

  @PostConstruct
  void init() {
    Stripe.apiKey = secretKey;
    if (stripeEnabled && secretKey.isBlank()) {
      log.warn("stripe.enabled=true but stripe.secret-key is blank; payment endpoints will fail at runtime");
    }
  }

  /**
   * Card-present PaymentIntent for Stripe Terminal (box-office).
   *
   * <p>Returns the full intent so the caller can hand both the id (to reference
   * later on confirmation) and the client_secret (consumed by the Terminal
   * reader through {@code collectPaymentMethod}/{@code processPayment}) to the
   * frontend. Routed through Connect when {@code connect} is non-null so the
   * organizer's connected account is charged and the platform fee is taken off
   * the top — identical to the online path so settlement math is consistent.
   *
   * <p>{@code idempotencyKey} comes from {@link StripeIdempotencyKeys#boxOfficeIntent}.
   */
  public PaymentIntent createCardPresentPaymentIntent(long amountCents, String currency,
      ConnectContext connect, String idempotencyKey) {
    try {
      PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
          .setAmount(amountCents)
          .setCurrency(currency)
          .addPaymentMethodType("card_present")
          .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.AUTOMATIC);
      if (connect != null && connect.connectedAccountId() != null) {
        builder.setOnBehalfOf(connect.connectedAccountId())
            .setTransferData(PaymentIntentCreateParams.TransferData.builder()
                .setDestination(connect.connectedAccountId())
                .build());
        if (connect.applicationFeeAmountCents() > 0) {
          builder.setApplicationFeeAmount(connect.applicationFeeAmountCents());
        }
        String suffix = sanitizeStatementDescriptor(connect.statementDescriptorSuffix());
        if (suffix != null) {
          builder.setStatementDescriptorSuffix(suffix);
        }
        builder.putMetadata("fairtix_org_id", connect.organizationId());
      }
      builder.putMetadata(CHANNEL_METADATA, "box_office");
      return PaymentIntent.create(builder.build(), keyed(idempotencyKey));
    } catch (IdempotencyException e) {
      throw retryConflict(e);
    } catch (CardException e) {
      throw new PaymentDeclinedException(
          e.getUserMessage() != null ? e.getUserMessage() : e.getMessage());
    } catch (StripeException e) {
      throw new RuntimeException("Failed to create Stripe card-present intent: " + e.getMessage(), e);
    }
  }

  /**
   * Stripe Terminal SDK requires a short-lived connection token to pair the
   * reader with our backend's Stripe account. Returns the raw {@code secret}
   * field; the SDK consumes it directly.
   */
  public String createTerminalConnectionToken(String connectedAccountId) {
    try {
      com.stripe.param.terminal.ConnectionTokenCreateParams.Builder b =
          com.stripe.param.terminal.ConnectionTokenCreateParams.builder();
      com.stripe.net.RequestOptions opts = null;
      if (connectedAccountId != null && !connectedAccountId.isBlank()) {
        opts = com.stripe.net.RequestOptions.builder()
            .setStripeAccount(connectedAccountId)
            .build();
      }
      com.stripe.model.terminal.ConnectionToken token = opts == null
          ? com.stripe.model.terminal.ConnectionToken.create(b.build())
          : com.stripe.model.terminal.ConnectionToken.create(b.build(), opts);
      return token.getSecret();
    } catch (StripeException e) {
      throw new RuntimeException("Failed to create Terminal connection token: " + e.getMessage(), e);
    }
  }

  /**
   * Where a payment's money went. {@code destinationAccountId} is the intent's
   * {@code transfer_data.destination}; {@code organizationId} is the {@code fairtix_org_id}
   * metadata FairTix set when it created the intent. Both are null for an intent that was
   * not routed through Connect.
   */
  public record ConnectRouting(String destinationAccountId, String organizationId) {}

  /**
   * Looks the intent up in Stripe and returns its Connect routing. A dispute event carries
   * only the intent's id, so this is how the platform webhook finds the organizer. Throws if
   * Stripe can't be reached, so the webhook fails and Stripe retries the event.
   */
  public ConnectRouting connectRoutingOf(String paymentIntentId) {
    try {
      PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId);
      String destination = intent.getTransferData() == null
          ? null : intent.getTransferData().getDestination();
      String orgId = intent.getMetadata() == null ? null : intent.getMetadata().get("fairtix_org_id");
      return new ConnectRouting(destination, orgId);
    } catch (StripeException e) {
      throw new RuntimeException("Failed to retrieve PaymentIntent " + paymentIntentId
          + " for its Connect routing: " + e.getMessage(), e);
    }
  }

  /**
   * Connect-aware PaymentIntent.
   *
   * When {@code connect} is non-null, the intent is routed to the connected
   * account via {@code on_behalf_of} + {@code transfer_data.destination}, and
   * the platform takes {@code application_fee_amount} cents off the top.
   * Statement descriptor suffix surfaces the org name on the customer's
   * statement (FAIRTIX*ORGNAME).
   *
   * <p>The intent records {@code userId} in {@link #USER_ID_METADATA}, so checkout
   * can confirm it belongs to the caller ({@link #verifyPaymentSucceeded(String, long, UUID)}).
   *
   * <p>{@code idempotencyKey} comes from {@link StripeIdempotencyKeys#onlineIntent}.
   */
  public StripeIntent createPaymentIntent(long amountCents, String currency, ConnectContext connect,
      UUID userId, String idempotencyKey) {
    Objects.requireNonNull(userId, "userId");
    try {
      PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
          .setAmount(amountCents)
          .setCurrency(currency)
          .putMetadata(USER_ID_METADATA, userId.toString());
      if (connect != null && connect.connectedAccountId() != null) {
        builder.setOnBehalfOf(connect.connectedAccountId())
            .setTransferData(PaymentIntentCreateParams.TransferData.builder()
                .setDestination(connect.connectedAccountId())
                .build());
        if (connect.applicationFeeAmountCents() > 0) {
          builder.setApplicationFeeAmount(connect.applicationFeeAmountCents());
        }
        String suffix = sanitizeStatementDescriptor(connect.statementDescriptorSuffix());
        if (suffix != null) {
          builder.setStatementDescriptorSuffix(suffix);
        }
        builder.putMetadata("fairtix_org_id", connect.organizationId());
      }
      PaymentIntent intent = PaymentIntent.create(builder.build(), keyed(idempotencyKey));
      return toStripeIntent(intent);
    } catch (IdempotencyException e) {
      throw retryConflict(e);
    } catch (CardException e) {
      throw new PaymentDeclinedException(
          e.getUserMessage() != null ? e.getUserMessage() : e.getMessage());
    } catch (StripeException e) {
      throw new RuntimeException("Failed to create Stripe payment intent: " + e.getMessage(), e);
    }
  }

  /** Reads an online intent's current id, status and client secret from Stripe. */
  public StripeIntent retrievePaymentIntent(String paymentIntentId) {
    try {
      return toStripeIntent(PaymentIntent.retrieve(paymentIntentId));
    } catch (StripeException e) {
      throw new RuntimeException("Failed to retrieve Stripe payment intent: " + e.getMessage(), e);
    }
  }

  /**
   * Cancels an intent as abandoned. If Stripe says it is no longer cancelable
   * ({@code payment_intent_unexpected_state}, e.g. it already succeeded), returns
   * the current intent instead so the caller can decide from its status.
   */
  public StripeIntent cancelPaymentIntent(String paymentIntentId) {
    try {
      PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId);
      PaymentIntent canceled = intent.cancel(PaymentIntentCancelParams.builder()
          .setCancellationReason(PaymentIntentCancelParams.CancellationReason.ABANDONED)
          .build());
      return toStripeIntent(canceled);
    } catch (InvalidRequestException e) {
      if ("payment_intent_unexpected_state".equals(e.getCode())) {
        return retrievePaymentIntent(paymentIntentId);
      }
      throw new RuntimeException("Failed to cancel Stripe payment intent: " + e.getMessage(), e);
    } catch (StripeException e) {
      throw new RuntimeException("Failed to cancel Stripe payment intent: " + e.getMessage(), e);
    }
  }

  private static StripeIntent toStripeIntent(PaymentIntent intent) {
    return new StripeIntent(intent.getId(), intent.getStatus(), intent.getClientSecret());
  }

  /** The parts of an online PaymentIntent that checkout needs. */
  public record StripeIntent(String id, String status, String clientSecret) {}

  // Stripe limits statement descriptor suffix to 22 chars; ASCII, no <>"'*\
  static String sanitizeStatementDescriptor(String raw) {
    if (raw == null) return null;
    String cleaned = raw.replaceAll("[<>\"'*\\\\]", "").replaceAll("\\s+", " ").trim();
    if (cleaned.isEmpty()) return null;
    return cleaned.length() > 22 ? cleaned.substring(0, 22) : cleaned;
  }

  public record ConnectContext(
      String organizationId,
      String connectedAccountId,
      long applicationFeeAmountCents,
      String statementDescriptorSuffix
  ) {}

  /**
   * True when the intent succeeded for exactly {@code expectedAmountCents}.
   *
   * <p>Stripe leaves {@code status} and {@code amount_received} unchanged after a
   * refund or dispute, so the latest charge is expanded and any refunded amount
   * (full or partial) or open dispute throws {@link PaymentRefundedException}:
   * such an intent must not become an order. This runs once, before the order is
   * written; a refund that lands during order creation is not caught here.
   *
   * <p>No owner check: for box-office (card-present) intents, which have no
   * customer account. Online checkout uses {@link #verifyPaymentSucceeded(String, long, UUID)}.
   */
  public boolean verifyPaymentSucceeded(String paymentIntentId, long expectedAmountCents) {
    return verify(paymentIntentId, expectedAmountCents, null);
  }

  /**
   * As {@link #verifyPaymentSucceeded(String, long)}, but first requires that the
   * intent was created for {@code userId}, whatever its status: otherwise throws
   * {@link PaymentIntentNotOwnedException}. An intent without
   * {@link #USER_ID_METADATA} (created before it existed) is never accepted.
   */
  public boolean verifyPaymentSucceeded(String paymentIntentId, long expectedAmountCents,
      UUID userId) {
    Objects.requireNonNull(userId, "userId");
    return verify(paymentIntentId, expectedAmountCents, userId);
  }

  /**
   * True when the intent has succeeded and its {@link #USER_ID_METADATA} is {@code userId}.
   * Checkout uses it to decide whether an attempt refused before verification leaves a charge
   * to put on record. It checks neither the amount nor refunds or disputes, so it is no
   * substitute for {@link #verifyPaymentSucceeded(String, long, UUID)}. Throws if Stripe fails.
   *
   * <p>Runs on a refusal path that a client can repeat, while the request may hold a pooled
   * database connection, so it uses short timeouts and no retries.
   */
  public boolean isPaidBy(String paymentIntentId, UUID userId) {
    Objects.requireNonNull(userId, "userId");
    try {
      PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId, PAID_CHECK_OPTIONS);
      Map<String, String> metadata = intent.getMetadata();
      return "succeeded".equals(intent.getStatus())
          && metadata != null
          && userId.toString().equals(metadata.get(USER_ID_METADATA));
    } catch (StripeException e) {
      // Including a 404: callers ask only about intents FairTix stored, so a missing one points
      // at a key or account mismatch, not at an unpaid intent.
      throw new RuntimeException("Failed to retrieve Stripe payment intent: " + e.getMessage(), e);
    }
  }

  private boolean verify(String paymentIntentId, long expectedAmountCents, UUID userId) {
    try {
      PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId,
          PaymentIntentRetrieveParams.builder().addExpand("latest_charge").build(), null);
      if (userId != null) {
        requireCreatedFor(intent, paymentIntentId, userId);
      }
      boolean succeeded = "succeeded".equals(intent.getStatus())
          && intent.getAmountReceived() == expectedAmountCents;
      if (!succeeded) {
        return false;
      }
      Charge charge = intent.getLatestChargeObject();
      if (charge == null) {
        // A succeeded intent always has a charge; null means the expand was lost.
        // Fail open (status and amount already check out) but make the gap visible.
        log.warn("Succeeded PaymentIntent {} has no expanded latest_charge; refund check skipped",
            paymentIntentId);
        return true;
      }
      if (charge.getAmountRefunded() != null && charge.getAmountRefunded() > 0) {
        throw new PaymentRefundedException(paymentIntentId, "refunded");
      }
      if (Boolean.TRUE.equals(charge.getDisputed())) {
        throw new PaymentRefundedException(paymentIntentId, "disputed");
      }
      return true;
    } catch (CardException e) {
      throw new PaymentDeclinedException(
          e.getUserMessage() != null ? e.getUserMessage() : e.getMessage());
    } catch (StripeException e) {
      throw new RuntimeException("Failed to verify Stripe payment: " + e.getMessage(), e);
    }
  }

  private void requireCreatedFor(PaymentIntent intent, String paymentIntentId, UUID userId) {
    Map<String, String> metadata = intent.getMetadata();
    String owner = metadata == null ? null : metadata.get(USER_ID_METADATA);
    boolean paid = "succeeded".equals(intent.getStatus());
    if (owner != null) {
      if (owner.equals(userId.toString())) {
        return;
      }
      throw new PaymentIntentNotOwnedException(paymentIntentId, userId, "other_user", paid);
    }
    // Box-office intents carry a channel and never a user, and are still created
    // after deploy: they are never online-checkout intents.
    if (metadata != null && metadata.containsKey(CHANNEL_METADATA)) {
      throw new PaymentIntentNotOwnedException(paymentIntentId, userId, "other_channel", paid);
    }
    // Only online intents created before USER_ID_METADATA existed get here. None is
    // accepted.
    throw new PaymentIntentNotOwnedException(paymentIntentId, userId,
        PaymentIntentNotOwnedException.REASON_UNBOUND, paid);
  }

  /**
   * Refunds {@code amountCents} of the intent for one refund request. Keyed by
   * {@link StripeIdempotencyKeys#refund}, and tagged with
   * {@code fairtix_refund_id} so a retry after Stripe's 24-hour key window
   * finds the existing refund instead of creating a second one.
   */
  public Refund createRefund(String paymentIntentId, long amountCents, String reason,
      UUID refundId) {
    try {
      // If the original intent was routed via Connect (transfer_data.destination
      // + application_fee_amount), reverse the transfer and pro-rata refund the
      // application fee — otherwise the connected account is left short. Missing
      // either flag is silently catastrophic for organizer settlements, so this
      // branch is the single source of truth.
      PaymentIntent original = PaymentIntent.retrieve(paymentIntentId);
      boolean isConnectIntent = original.getTransferData() != null
          && original.getTransferData().getDestination() != null;

      RefundCreateParams.Builder builder = RefundCreateParams.builder()
          .setPaymentIntent(paymentIntentId)
          .setAmount(amountCents);
      if (isConnectIntent) {
        builder.setReverseTransfer(true);
        if (original.getApplicationFeeAmount() != null && original.getApplicationFeeAmount() > 0) {
          builder.setRefundApplicationFee(true);
        }
      }
      if (reason != null && !reason.isBlank()) {
        builder.putMetadata("reason", reason);
      }
      builder.putMetadata(REFUND_ID_METADATA, refundId.toString());

      Refund existing = findRefundFor(paymentIntentId, refundId);
      if (existing != null) {
        log.info("createRefund: reusing Stripe refund {} for refund request {}",
            existing.getId(), refundId);
        return requireLive(existing);
      }
      return requireLive(Refund.create(builder.build(), keyed(StripeIdempotencyKeys.refund(refundId))));
    } catch (IdempotencyException e) {
      throw retryConflict(e);
    } catch (StripeException e) {
      throw new RuntimeException("Failed to create Stripe refund: " + e.getMessage(), e);
    }
  }

  private static Refund findRefundFor(String paymentIntentId, UUID refundId) throws StripeException {
    RefundCollection page = Refund.list(RefundListParams.builder()
        .setPaymentIntent(paymentIntentId)
        .setLimit(100L)
        .build());
    if (page == null || page.getData() == null) {
      return null;
    }
    String wanted = refundId.toString();
    return page.getData().stream()
        .filter(r -> r.getMetadata() != null && wanted.equals(r.getMetadata().get(REFUND_ID_METADATA)))
        .findFirst()
        .orElse(null);
  }

  /**
   * A failed or canceled Stripe refund returned no money; treat it as a failure,
   * not as the refund (a replayed key would return the same dead object).
   */
  private static Refund requireLive(Refund refund) {
    String status = refund.getStatus();
    if ("failed".equals(status) || "canceled".equals(status)) {
      throw new IllegalStateException(
          "Stripe refund " + refund.getId() + " is " + status + "; it needs manual follow-up");
    }
    return refund;
  }

  private static RequestOptions keyed(String idempotencyKey) {
    return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
  }

  private static PaymentRetryConflictException retryConflict(IdempotencyException e) {
    return new PaymentRetryConflictException(
        "Stripe rejected a retry with changed parameters: " + e.getMessage(), e);
  }

  public boolean isStripeEnabled() {
    return stripeEnabled;
  }

  /**
   * Records the payment for an online checkout, with the audit row in the order's
   * own transaction and on its connection. Online checkout runs many at a time; a
   * {@code REQUIRES_NEW} audit here needs a second pooled connection while the
   * first is held, and at twice the pool size every checkout waits for a
   * connection that none can release, after the customer has paid.
   *
   * <p>If the order rolls back, this row goes with it. The captured charge is
   * still on record: {@code PaymentController} then writes
   * {@code STRIPE_ORDER_FAILED} (or {@code STRIPE_ORDER_BUSY} for a busy purchase-cap
   * lock) with the PaymentIntent id in a transaction of its own.
   */
  public PaymentRecord recordStripePaymentInOrderTransaction(String paymentIntentId, UUID orderId,
      UUID userId, BigDecimal amount, String currency) {
    PaymentRecord saved = paymentRecordRepository.save(new PaymentRecord(
        orderId, userId, amount, currency, PaymentStatus.SUCCESS, paymentIntentId, null));
    auditService.logInCurrentTransaction(userId, "PAYMENT_PROCESSED", "PAYMENT", saved.getId(),
        "stripe_intent=" + paymentIntentId);
    return saved;
  }

  public PaymentRecord recordStripePayment(String paymentIntentId, UUID orderId, UUID userId,
      BigDecimal amount, String currency) {
    PaymentRecord record = new PaymentRecord(
        orderId, userId, amount, currency, PaymentStatus.SUCCESS, paymentIntentId, null);
    PaymentRecord saved = paymentRecordRepository.save(record);
    // REQUIRES_NEW on purpose: a captured charge stays on record even if the
    // order or box-office sale that recorded it rolls back afterwards.
    auditService.log(userId, "PAYMENT_PROCESSED", "PAYMENT", saved.getId(),
        "stripe_intent=" + paymentIntentId);
    return saved;
  }
}
