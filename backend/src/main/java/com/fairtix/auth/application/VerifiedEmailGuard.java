package com.fairtix.auth.application;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** Blocks actions that need a verified email address. Stub (intended red). */
@Component
public class VerifiedEmailGuard {

  public void requireVerified(UUID userId, String action) {
  }
}
