package com.fairtix.holds.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record IssueCompRequest(
    @NotNull UUID eventId,
    @NotEmpty List<UUID> seatIds,
    @Size(max = 255) String recipientName,
    @Email @Size(max = 255) String recipientEmail,
    @Size(max = 1000) String reason,
    boolean willCall) {
}
