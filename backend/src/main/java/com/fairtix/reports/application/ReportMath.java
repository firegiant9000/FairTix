package com.fairtix.reports.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Settlement math primitives — pure functions. Extracted so that the
 * reconciliation tests can hammer the formulas with property-based fuzzing
 * without spinning up the JPA context.
 *
 * Every helper returns a BigDecimal at scale 2 with {@link RoundingMode#HALF_UP}.
 * The invariant the report renderers depend on:
 * <pre>
 *   net = gross + add_ons - sales_tax - refunds - platform_fee - stripe_fee
 * </pre>
 */
public final class ReportMath {

  private ReportMath() {}

  /** Stripe baseline (2.9% of gross + $0.30 per ticket). M5 will recompute from BalanceTransaction. */
  public static final BigDecimal STRIPE_PCT = new BigDecimal("0.029");
  public static final BigDecimal STRIPE_PER_TICKET = new BigDecimal("0.30");

  /** 1099-K federal threshold. The number Congress moves every other year — currently $5,000 for 2025+. */
  public static final BigDecimal FEDERAL_1099K_THRESHOLD = new BigDecimal("5000.00");

  public static BigDecimal money(BigDecimal v) {
    return v == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                     : v.setScale(2, RoundingMode.HALF_UP);
  }

  public static BigDecimal platformFee(BigDecimal gross, int planBps) {
    if (gross == null || gross.signum() <= 0 || planBps <= 0) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    return gross
        .multiply(BigDecimal.valueOf(planBps))
        .divide(BigDecimal.valueOf(10_000), 2, RoundingMode.HALF_UP);
  }

  public static BigDecimal stripeFee(BigDecimal gross, long ticketsSold) {
    if (gross == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    BigDecimal pct = gross.multiply(STRIPE_PCT);
    BigDecimal per = STRIPE_PER_TICKET.multiply(BigDecimal.valueOf(Math.max(ticketsSold, 0)));
    return pct.add(per).setScale(2, RoundingMode.HALF_UP);
  }

  public static BigDecimal salesTax(BigDecimal gross, BigDecimal taxRatePct) {
    if (gross == null || taxRatePct == null || taxRatePct.signum() <= 0) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    return gross.multiply(taxRatePct).setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * Net to the venue, before any artist split. Identity:
   * {@code gross + add_ons - tax - refunds - platform_fee - stripe_fee}.
   */
  public static BigDecimal net(BigDecimal gross, BigDecimal addOns, BigDecimal tax,
                               BigDecimal refunds, BigDecimal platformFee, BigDecimal stripeFee) {
    return money(gross)
        .add(money(addOns))
        .subtract(money(tax))
        .subtract(money(refunds))
        .subtract(money(platformFee))
        .subtract(money(stripeFee))
        .setScale(2, RoundingMode.HALF_UP);
  }

  /** Flat percent split: artist takes {@code pct * net}; clamps inputs to safe ranges. */
  public static BigDecimal flatArtistPayout(BigDecimal net, BigDecimal artistPct) {
    if (net == null || artistPct == null || artistPct.signum() <= 0) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    BigDecimal clamped = artistPct.min(BigDecimal.ONE).max(BigDecimal.ZERO);
    return net.multiply(clamped).setScale(2, RoundingMode.HALF_UP);
  }

  /** Door deal: venue takes {@code venueTakeOffTop} off the net first, then artist gets {@code pct} of the remainder. */
  public static BigDecimal doorDealArtistPayout(BigDecimal net, BigDecimal venueTakeOffTop, BigDecimal artistPct) {
    if (net == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    BigDecimal take = venueTakeOffTop == null ? BigDecimal.ZERO : venueTakeOffTop;
    BigDecimal after = net.subtract(take);
    if (after.signum() < 0) after = BigDecimal.ZERO;
    return flatArtistPayout(after, artistPct);
  }
}
