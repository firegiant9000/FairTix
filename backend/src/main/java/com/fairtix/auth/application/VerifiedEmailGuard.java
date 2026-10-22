package com.fairtix.auth.application;

import com.fairtix.users.infrastructure.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Blocks actions that need a verified email address (RM:323): checkout, payment
 * intents, ticket transfers and new organizations. Reads the user row on every call,
 * so verifying takes effect without a new token. A principal with no user row is
 * refused too.
 */
@Component
public class VerifiedEmailGuard {

  private final UserRepository userRepository;

  public VerifiedEmailGuard(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /** @param action completes "Please verify your email before …", e.g. "purchasing tickets". */
  public void requireVerified(UUID userId, String action) {
    boolean verified = userRepository.findById(userId)
        .map(u -> u.isEmailVerified())
        .orElse(false);
    if (!verified) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "Email address not verified. Please verify your email before " + action + ".");
    }
  }
}
