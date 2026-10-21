package com.fairtix.refunds.application;

import com.fairtix.payments.application.StripePaymentService;
import com.fairtix.refunds.application.RefundService.FinaliseResult;
import com.fairtix.refunds.application.RefundService.RefundPlan;
import com.fairtix.refunds.domain.RefundRequest;
import com.fairtix.refunds.domain.RefundStatus;
import com.fairtix.refunds.infrastructure.RefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Moves refund money with no DB transaction open (exposure gate (b), RM:344).
 *
 * <p>Deliberately not {@code @Transactional}. Each step is its own short
 * transaction in {@link RefundService}: the approval commits first, then the
 * Stripe call runs keyed by the refund id, then {@link RefundService#finalise}
 * applies the DB side once. If that last commit fails, the refund stays
 * {@code APPROVED} and {@link #retry} replays the same Stripe key, so Stripe
 * returns the original refund instead of paying twice.
 */
@Service
public class RefundExecutionService {

  private static final Logger log = LoggerFactory.getLogger(RefundExecutionService.class);

  private final RefundService refundService;
  private final StripePaymentService stripePaymentService;
  private final RefundRepository refundRepository;

  public RefundExecutionService(RefundService refundService,
      StripePaymentService stripePaymentService, RefundRepository refundRepository) {
    this.refundService = refundService;
    this.stripePaymentService = stripePaymentService;
    this.refundRepository = refundRepository;
  }

  /**
   * A customer's refund request. An auto-approved request is executed straight
   * away; if Stripe fails, the request still succeeded and is returned
   * {@code APPROVED} for an admin to retry.
   */
  public RefundRequest request(UUID userId, UUID orderId, String reason) {
    RefundRequest refund = refundService.requestRefund(userId, orderId, reason);
    if (refund.getStatus() != RefundStatus.APPROVED) {
      return refund;
    }
    try {
      return execute(refund.getId(), userId);
    } catch (RefundProviderException | RefundFinaliseException ex) {
      // Already audited; the request itself succeeded and an admin can retry.
      return refund;
    }
  }

  /** An admin's decision; an approval is executed straight away. */
  public RefundRequest review(UUID adminId, UUID refundId, boolean approved, String notes) {
    RefundRequest refund = refundService.reviewRefund(adminId, refundId, approved, notes);
    return approved ? execute(refundId, adminId) : refund;
  }

  /** Re-drives an approved refund whose money movement or DB side failed. */
  public RefundRequest retry(UUID adminId, UUID refundId) {
    return execute(refundId, adminId);
  }

  /**
   * Runs every approved refund on a cancelled event, each in its own short
   * transactions, after the cancellation has committed. A failure is logged and
   * audited and leaves that refund {@code APPROVED} for {@link #retry}; the
   * others still run.
   */
  public void executeAllForEvent(UUID eventId, UUID actorId) {
    for (UUID refundId : refundRepository.findExecutableIdsForEvent(eventId)) {
      try {
        execute(refundId, actorId);
      } catch (RefundProviderException | RefundFinaliseException ex) {
        log.error("Cancellation refund {} for event {} not executed: {}",
            refundId, eventId, ex.getMessage());
      } catch (RuntimeException ex) {
        // Failed before Stripe (e.g. the 180-day window): audit it so it can be found.
        log.error("Cancellation refund {} for event {} not executed: {}",
            refundId, eventId, ex.getMessage());
        auditQuietly(() -> refundService.recordFailure(refundId, actorId, "REFUND_EXECUTION_FAILED",
            "Cancellation refund for event " + eventId + " not executed: " + ex.getMessage()));
      }
    }
  }

  RefundRequest execute(UUID refundId, UUID actorId) {
    RefundPlan plan = refundService.prepare(refundId);
    String stripeRefundId = null;
    if (plan.viaStripe()) {
      try {
        stripeRefundId = stripePaymentService.createRefund(
            plan.paymentIntentId(), plan.amountCents(), plan.reason(), refundId).getId();
      } catch (RuntimeException ex) {
        log.error("Stripe refund failed for refund {} (order {}): {}",
            refundId, plan.orderId(), ex.getMessage());
        auditQuietly(() -> refundService.recordFailure(refundId, actorId, "REFUND_STRIPE_FAILED",
            "Stripe refund call failed: " + ex.getMessage()));
        throw new RefundProviderException("The payment provider could not process this refund. "
            + "It stays approved and can be retried.", ex);
      }
    }
    String movedRefundId = stripeRefundId;
    FinaliseResult result;
    try {
      result = refundService.finalise(refundId, stripeRefundId, actorId);
    } catch (RuntimeException ex) {
      // The money may already have moved: record which Stripe refund, so the
      // request can be found and retried even after the webhook completes it.
      log.error("Refund {} finalise failed after stripeRefundId={}: {}",
          refundId, stripeRefundId, ex.getMessage());
      auditQuietly(() -> refundService.recordFailure(refundId, actorId, "REFUND_FINALISE_FAILED",
          "stripeRefundId=" + movedRefundId + " finalise failed: " + ex.getMessage()));
      throw new RefundFinaliseException("Refund bookkeeping failed after the provider call: "
          + ex.getMessage() + ". It can be retried.", ex);
    }
    if (result.applied()) {
      refundService.sendOutcomeEmail(result.refund());
    }
    return result.refund();
  }

  /** A failed audit write must never hide the original failure. */
  private static void auditQuietly(Runnable audit) {
    try {
      audit.run();
    } catch (RuntimeException auditFailure) {
      log.error("Could not record refund failure audit: {}", auditFailure.getMessage());
    }
  }
}
