package com.fairtix.organizations.domain;

import java.math.BigDecimal;

/**
 * Plan tier with monthly ticket caps and per-ticket platform fee.
 * Caps and fees are scaffolding — enforcement lands with M5 billing.
 * A null cap means unlimited.
 */
public enum Plan {
  FREE(200, new BigDecimal("0.025")),
  PRO(null, new BigDecimal("0.015")),
  SCALE(null, new BigDecimal("0.010")),
  ENTERPRISE(null, null);

  private final Integer monthlyTicketCap;
  private final BigDecimal perTicketFee;

  Plan(Integer monthlyTicketCap, BigDecimal perTicketFee) {
    this.monthlyTicketCap = monthlyTicketCap;
    this.perTicketFee = perTicketFee;
  }

  public Integer getMonthlyTicketCap() { return monthlyTicketCap; }
  public BigDecimal getPerTicketFee() { return perTicketFee; }
  public boolean isUnlimited() { return monthlyTicketCap == null; }
}
