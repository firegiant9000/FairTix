package com.fairtix.users.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * No {@code @Email} here: accounts registered before format checks existed must
 * still be able to sign in. {@link RegisterRequest} enforces the format.
 */
@Schema(description = "Login credentials")
public record LoginRequest(
        @Schema(description = "User email address", example = "user@example.com")
        @NotBlank @Size(max = 254)
        String email,
        @Schema(description = "User password", example = "password123")
        @NotBlank @Size(max = 72)
        String password,
        @Schema(description = "Google reCAPTCHA token", example = "03AFcWeA...")
        @Size(max = 4096)
        String recaptchaToken) {
}
