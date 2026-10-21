package com.fairtix.refunds.application;

import com.fairtix.payments.application.StripePaymentService;
import com.fairtix.refunds.application.RefundService.FinaliseResult;
import com.fairtix.refunds.application.RefundService.RefundPlan;
import com.fairtix.refunds.domain.RefundRequest;
import com.fairtix.refunds.domain.RefundStatus;
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

  public RefundExecutionService(RefundService refundService,
      StripePaymentService stripePaymentService) {
    this.refundService = refundService;
    this.stripePaymentService = stripePaymentService;
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
    } catch (RefundProviderException ex) {
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
        refundService.recordStripeFailure(refundId, actorId, ex.getMessage());
        throw new RefundProviderException("The payment provider could not process this refund. "
            + "It stays approved and can be retried.", ex);
      }
    }
    FinaliseResult result = refundService.finalise(refundId, stripeRefundId, actorId);
    if (result.applied()) {
      refundService.sendOutcomeEmail(result.refund());
    }
    return result.refund();
  }
}
