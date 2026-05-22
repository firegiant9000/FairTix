package com.fairtix.boxoffice.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmCardSaleRequest(
    @NotBlank String paymentIntentId) {}
