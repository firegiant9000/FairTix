package com.fairtix.organizations.domain;

public enum OrganizationStatus {
  /** Created (legacy / direct API), not gated. Existing pre-M2 orgs sit here. */
  PENDING,
  /** Signup wizard complete; awaiting platform-admin review. */
  PENDING_REVIEW,
  /** Approved — can publish events and take payments (subject to sales caps). */
  ACTIVE,
  /** Admin rejected the application; rejection_reason holds the user-facing message. */
  REJECTED,
  /** Disabled by admin (fraud, ToS violation, Stripe deauthorized, etc.). */
  SUSPENDED
}
