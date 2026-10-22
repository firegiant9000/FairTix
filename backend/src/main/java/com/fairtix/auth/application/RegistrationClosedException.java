package com.fairtix.auth.application;

/** Registration is disabled, or the address isn't on the allowlist. Maps to 403. */
public class RegistrationClosedException extends RuntimeException {
  public RegistrationClosedException() {
    super("Registration is closed.");
  }
}
