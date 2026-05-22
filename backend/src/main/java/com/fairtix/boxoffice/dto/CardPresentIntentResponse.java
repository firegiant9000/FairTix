package com.fairtix.boxoffice.dto;

public record CardPresentIntentResponse(
    String paymentIntentId,
    String clientSecret,
    String connectedAccountId,
    long amountCents,
    long applicationFeeCents) {}
