package com.fairtix.users.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** No {@code @Email}, as on {@link LoginRequest}: older accounts must be able to recover. */
public record ForgotPasswordRequest(@NotBlank @Size(max = 254) String email) {}
