package com.fairtix.boxoffice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record CloseSessionRequest(
    @NotNull @PositiveOrZero BigDecimal closingCash,
    String varianceReason) {}
