package com.fairtix.payments.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.payments.domain.PaymentRecord;
import com.fairtix.payments.domain.PaymentStatus;
import com.fairtix.payments.infrastructure.PaymentRecordRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Profile("!prod")
public class PaymentSimulationService {

  private final PaymentRecordRepository paymentRecordRepository;
  private final AuditService auditService;

  public PaymentSimulationService(PaymentRecordRepository paymentRecordRepository,
      AuditService auditService) {
    this.paymentRecordRepository = paymentRecordRepository;
    this.auditService = auditService;
  }

  /**
   * Simulates a payment. If simulatedOutcome is null, randomly picks a result
   * weighted 80% success, 15% failure, 5% cancelled.
   */
  public PaymentRecord processPayment(UUID orderId, UUID userId, BigDecimal amount,
      String currency, PaymentStatus simulatedOutcome) {

    PaymentStatus outcome = simulatedOutcome != null ? simulatedOutcome : randomOutcome();
    String transactionId = "sim_" + UUID.randomUUID().toString().replace("-", "");

    String failureReason = null;
    if (outcome == PaymentStatus.FAILURE) {
      failureReason = "Simulated payment declined";
    } else if (outcome == PaymentStatus.CANCELLED) {
      failureReason = "Payment cancelled by user";
    }

    PaymentRecord record = new PaymentRecord(
        orderId, userId, amount, currency, outcome, transactionId, failureReason);

    PaymentRecord saved = paymentRecordRepository.save(record);

    if (outcome == PaymentStatus.SUCCESS) {
      auditService.logInCurrentTransaction(userId, "PAYMENT_PROCESSED", "PAYMENT", saved.getId(), null);
    } else {
      auditService.logInCurrentTransaction(userId, "PAYMENT_FAILED", "PAYMENT", saved.getId(), "outcome=" + outcome);
    }

    return saved;
  }

  private PaymentStatus randomOutcome() {
    int roll = ThreadLocalRandom.current().nextInt(100);
    if (roll < 80) return PaymentStatus.SUCCESS;
    if (roll < 95) return PaymentStatus.FAILURE;
    return PaymentStatus.CANCELLED;
  }
}
