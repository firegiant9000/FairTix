package com.fairtix.users.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Reset tokens are UUIDs; password strength rules live in {@code PasswordResetService}. */
public record ResetPasswordRequest(
        @NotBlank @Size(max = 64) String token,
        @NotBlank @Size(max = 72) String newPassword) {}
