package com.fairtix.refunds.application;

import com.fairtix.refunds.domain.RefundRequest;
import com.fairtix.refunds.infrastructure.RefundRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Red-commit stub: delegates to today's in-transaction behaviour. */
@Service
public class RefundExecutionService {

  private final RefundService refundService;
  private final RefundRepository refundRepository;

  public RefundExecutionService(RefundService refundService, RefundRepository refundRepository) {
    this.refundService = refundService;
    this.refundRepository = refundRepository;
  }

  public RefundRequest request(UUID userId, UUID orderId, String reason) {
    return refundService.requestRefund(userId, orderId, reason);
  }

  public RefundRequest review(UUID adminId, UUID refundId, boolean approved, String notes) {
    return refundService.reviewRefund(adminId, refundId, approved, notes);
  }

  public RefundRequest retry(UUID adminId, UUID refundId) {
    RefundRequest refund = refundRepository.findById(refundId).orElseThrow();
    refundService.processRefund(refund, adminId);
    return refund;
  }
}
