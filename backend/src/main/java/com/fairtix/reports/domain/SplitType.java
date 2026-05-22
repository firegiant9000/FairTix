package com.fairtix.reports.domain;

/**
 * Promoter/artist split formula. M2 only supports the two common
 * configurations; anything more exotic (walkout deals, escalators) is
 * handled manually until M5 per the implementation plan.
 */
public enum SplitType {
  /** {@code artist_payout = artist_pct * net}. */
  FLAT_PCT,
  /** {@code artist_payout = artist_pct * (net - venue_take_off_top)} (clamped at 0). */
  DOOR_DEAL
}
