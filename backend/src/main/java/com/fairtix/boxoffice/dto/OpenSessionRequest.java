package com.fairtix.boxoffice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record OpenSessionRequest(
    @NotNull @PositiveOrZero BigDecimal openingCash) {}
