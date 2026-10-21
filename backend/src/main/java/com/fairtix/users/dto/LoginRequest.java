package com.fairtix.users.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Login credentials")
public record LoginRequest(
        @Schema(description = "User email address", example = "user@example.com")
        @NotBlank @Email @Size(max = 254)
        String email,
        @Schema(description = "User password", example = "password123")
        @NotBlank @Size(max = 72)
        String password,
        @Schema(description = "Google reCAPTCHA token", example = "03AFcWeA...")
        @Size(max = 4096)
        String recaptchaToken) {
}
