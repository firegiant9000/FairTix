package com.fairtix.auth.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Who may create an account through {@code POST /auth/register}. */
@Component
public class RegistrationPolicy {

  public enum Mode { OPEN, ALLOWLIST, DISABLED }

  /** {@code preVerified}: the operator vetted this exact address (allowlist mode). */
  public record Decision(boolean allowed, boolean preVerified) {}

  private final Mode mode;

  public RegistrationPolicy(
      @Value("${fairtix.registration.mode:disabled}") String mode,
      @Value("${fairtix.registration.allowlist:}") String allowlist) {
    this.mode = Mode.OPEN; // TODO stub (intended red)
  }

  public Mode mode() {
    return mode;
  }

  public Decision check(String email) {
    return new Decision(true, false);
  }
}
