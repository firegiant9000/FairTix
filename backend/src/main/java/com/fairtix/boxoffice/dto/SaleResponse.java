package com.fairtix.boxoffice.dto;

import com.fairtix.boxoffice.domain.BoxOfficeSale;
import com.fairtix.boxoffice.domain.SaleMethod;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SaleResponse(
    UUID id,
    UUID sessionId,
    UUID eventId,
    UUID orderId,
    SaleMethod method,
    BigDecimal amount,
    int seatCount,
    String customerEmail,
    String customerName,
    String compReason,
    String stripePaymentIntentId,
    Instant createdAt) {

  public static SaleResponse from(BoxOfficeSale s) {
    return new SaleResponse(s.getId(), s.getSessionId(), s.getEventId(), s.getOrderId(),
        s.getMethod(), s.getAmount(), s.getSeatCount(), s.getCustomerEmail(),
        s.getCustomerName(), s.getCompReason(), s.getStripePaymentIntentId(), s.getCreatedAt());
  }
}
