package com.fairtix.payments.dto;

import com.stripe.model.Payout;

import java.time.Instant;

public record ConnectPayoutResponse(
    String id,
    long amount,
    String currency,
    String status,
    String failureCode,
    String failureMessage,
    Instant arrivalDate,
    Instant created
) {
  public static ConnectPayoutResponse from(Payout p) {
    return new ConnectPayoutResponse(
        p.getId(),
        p.getAmount() == null ? 0L : p.getAmount(),
        p.getCurrency(),
        p.getStatus(),
        p.getFailureCode(),
        p.getFailureMessage(),
        p.getArrivalDate() == null ? null : Instant.ofEpochSecond(p.getArrivalDate()),
        p.getCreated() == null ? null : Instant.ofEpochSecond(p.getCreated())
    );
  }
}
