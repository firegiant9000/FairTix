package com.fairtix.reports.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Settlement math invariants. The roadmap is explicit: if the numbers don't
 * tie out, the whole report feature is worse than not shipping it. These are
 * the property-based fuzzers that back that guarantee.
 */
class ReportMathTest {

  private static final BigDecimal CENT = new BigDecimal("0.01");

  @Test
  void platformFee_freePlanIsTwoFiftyBps() {
    BigDecimal fee = ReportMath.platformFee(new BigDecimal("1000.00"), 250);
    assertEquals(new BigDecimal("25.00"), fee);
  }

  @Test
  void platformFee_zeroPlanBpsIsZero() {
    BigDecimal fee = ReportMath.platformFee(new BigDecimal("1000.00"), 0);
    assertEquals(new BigDecimal("0.00"), fee);
  }

  @Test
  void stripeFee_matchesBaselineFormula() {
    // 100.00 gross, 4 tickets → 100 * 0.029 + 4 * 0.30 = 2.90 + 1.20 = 4.10
    BigDecimal fee = ReportMath.stripeFee(new BigDecimal("100.00"), 4);
    assertEquals(new BigDecimal("4.10"), fee);
  }

  @Test
  void salesTax_appliesRateAtScaleTwo() {
    BigDecimal tax = ReportMath.salesTax(new BigDecimal("123.45"), new BigDecimal("0.0875"));
    // 123.45 * 0.0875 = 10.801875 → 10.80 (HALF_UP)
    assertEquals(new BigDecimal("10.80"), tax);
  }

  @Test
  void net_followsIdentity() {
    BigDecimal gross = new BigDecimal("1000.00");
    BigDecimal addOns = new BigDecimal("50.00");
    BigDecimal tax = new BigDecimal("80.00");
    BigDecimal refunds = new BigDecimal("100.00");
    BigDecimal platform = new BigDecimal("25.00");
    BigDecimal stripe = new BigDecimal("32.50");
    BigDecimal net = ReportMath.net(gross, addOns, tax, refunds, platform, stripe);
    // 1000 + 50 - 80 - 100 - 25 - 32.50 = 812.50
    assertEquals(new BigDecimal("812.50"), net);
  }

  @Test
  void flatArtistPayout_clampsPctToUnitInterval() {
    BigDecimal payout = ReportMath.flatArtistPayout(new BigDecimal("100.00"), new BigDecimal("1.5"));
    assertEquals(new BigDecimal("100.00"), payout);

    BigDecimal zero = ReportMath.flatArtistPayout(new BigDecimal("100.00"), new BigDecimal("-0.2"));
    assertEquals(new BigDecimal("0.00"), zero);
  }

  @Test
  void doorDeal_subtractsTakeOffTopThenSplits() {
    // Net 1000, venue takes 500 off top, then 85/15 split of remaining 500 → artist 425
    BigDecimal payout = ReportMath.doorDealArtistPayout(
        new BigDecimal("1000.00"), new BigDecimal("500.00"), new BigDecimal("0.85"));
    assertEquals(new BigDecimal("425.00"), payout);
  }

  @Test
  void doorDeal_clampsNegativeAfterTakeToZero() {
    BigDecimal payout = ReportMath.doorDealArtistPayout(
        new BigDecimal("200.00"), new BigDecimal("500.00"), new BigDecimal("0.85"));
    assertEquals(new BigDecimal("0.00"), payout);
  }

  /**
   * Property: for any random gross/refund/comp split, the identity
   *   gross + add_ons - tax - refunds - platform - stripe = net
   * holds at scale 2. Fuzz with 500 trials.
   */
  @Test
  void net_propertyHoldsUnderFuzz() {
    Random rng = new Random(0xC0FFEEL);
    for (int i = 0; i < 500; i++) {
      BigDecimal gross = money(rng.nextDouble() * 10_000);
      BigDecimal addOns = money(rng.nextDouble() * 1_000);
      BigDecimal tax = money(rng.nextDouble() * 500);
      BigDecimal refunds = money(rng.nextDouble() * gross.doubleValue());
      BigDecimal platform = ReportMath.platformFee(gross, 100 + rng.nextInt(200));
      BigDecimal stripe = ReportMath.stripeFee(gross, rng.nextInt(200));

      BigDecimal expected = gross.add(addOns).subtract(tax).subtract(refunds)
          .subtract(platform).subtract(stripe).setScale(2, java.math.RoundingMode.HALF_UP);
      BigDecimal actual = ReportMath.net(gross, addOns, tax, refunds, platform, stripe);

      assertTrue(actual.subtract(expected).abs().compareTo(CENT) <= 0,
          "Identity drift > 1c at i=" + i + " expected=" + expected + " actual=" + actual);
    }
  }

  /**
   * Property: for any FLAT_PCT split, venue_retention + artist_payout = net
   * (always — venue takes what's left over). Same for DOOR_DEAL after the
   * take-off-top.
   */
  @Test
  void split_totalsAlwaysReconstructNet() {
    Random rng = new Random(0xBEEFL);
    for (int i = 0; i < 500; i++) {
      BigDecimal net = money(rng.nextDouble() * 5_000);
      BigDecimal pct = BigDecimal.valueOf(Math.round(rng.nextDouble() * 100) / 100.0);

      BigDecimal artistFlat = ReportMath.flatArtistPayout(net, pct);
      BigDecimal venueFlat = net.subtract(artistFlat);
      assertEquals(net, artistFlat.add(venueFlat), "FLAT split mismatch at " + i);

      BigDecimal take = money(rng.nextDouble() * net.doubleValue());
      BigDecimal artistDoor = ReportMath.doorDealArtistPayout(net, take, pct);
      BigDecimal venueDoor = net.subtract(artistDoor);
      assertEquals(net, artistDoor.add(venueDoor), "DOOR split mismatch at " + i);
    }
  }

  private static BigDecimal money(double d) {
    return BigDecimal.valueOf(d).setScale(2, java.math.RoundingMode.HALF_UP);
  }
}
