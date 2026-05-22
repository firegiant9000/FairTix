package com.fairtix.organizations.application;

import java.util.UUID;

/**
 * Per-request holder for the active org id (resolved from X-Organization-Id header).
 */
public final class OrgContext {

  private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

  private OrgContext() {}

  public static void set(UUID orgId) {
    CURRENT.set(orgId);
  }

  public static UUID get() {
    return CURRENT.get();
  }

  public static void clear() {
    CURRENT.remove();
  }
}
