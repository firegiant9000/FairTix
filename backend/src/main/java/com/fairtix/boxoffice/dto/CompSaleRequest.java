package com.fairtix.boxoffice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record CompSaleRequest(
    @NotNull UUID eventId,
    @NotEmpty List<UUID> seatIds,
    String customerEmail,
    String customerName,
    @NotBlank String reason) {}
