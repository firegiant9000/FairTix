package com.fairtix.holds.application;

public class CompLimitExceededException extends RuntimeException {
  public CompLimitExceededException(String message) {
    super(message);
  }
}
