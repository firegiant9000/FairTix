package com.fairtix.users.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Password strength rules live in {@code AuthService}; 72 is BCrypt's input limit. */
@Schema(description = "New user registration payload")
public record RegisterRequest(
        @Schema(description = "Email address for the new account", example = "newuser@example.com")
        @NotBlank @Email @Size(max = 254)
        String email,
        @Schema(description = "Password (plaintext, will be hashed)", example = "password123")
        @NotBlank @Size(max = 72)
        String password) {
}
