package com.fairtix.fraud.application;

/**
 * Step-up verification can't be offered because reCAPTCHA is off or has no
 * secret. The gate stays closed (D4): nobody is marked verified without a check.
 */
public class StepUpUnavailableException extends RuntimeException {

  public StepUpUnavailableException() {
    super("Step-up verification is unavailable. Please contact support.");
  }
}
