package com.fairtix.organizations.application;

/**
 * Thrown when a new-org sales cap would be breached by a pending charge.
 * Mapped to HTTP 429 by GlobalExceptionHandler.
 */
public class SalesCapExceededException extends RuntimeException {

  private final long capCents;
  private final long usedCents;
  private final long requestedCents;

  public SalesCapExceededException(long capCents, long usedCents, long requestedCents) {
    super("Daily sales cap would be exceeded. Contact support to raise the limit.");
    this.capCents = capCents;
    this.usedCents = usedCents;
    this.requestedCents = requestedCents;
  }

  public long getCapCents()       { return capCents; }
  public long getUsedCents()      { return usedCents; }
  public long getRequestedCents() { return requestedCents; }
}
