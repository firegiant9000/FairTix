package com.fairtix.payments.application;

import com.fairtix.organizations.domain.Plan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure-Java guards on the bits of Connect routing that don't need a live
 * Stripe call: plan→bps mapping and statement-descriptor sanitization.
 *
 * If the bps mapping or sanitizer regresses, organizers either pay the wrong
 * platform fee or get a 400 from Stripe on every PaymentIntent — both
 * silently catastrophic. Worth a unit test.
 */
class StripeConnectFeeMathTest {

  @Test
  void planMapsToBpsPerRoadmap() {
    // Roadmap §2C: Free 250 bps, Pro 150, Scale 100, Enterprise 0
    assertEquals(250, Plan.FREE.getPlatformFeeBps());
    assertEquals(150, Plan.PRO.getPlatformFeeBps());
    assertEquals(100, Plan.SCALE.getPlatformFeeBps());
    assertEquals(0,   Plan.ENTERPRISE.getPlatformFeeBps());
  }

  @Test
  void feeMathIsCentsPrecise() {
    // $100.00 → 10000 cents → Free (250 bps) → $2.50 fee
    long amount = 10_000L;
    long fee = (amount * Plan.FREE.getPlatformFeeBps()) / 10_000L;
    assertEquals(250L, fee);

    // $9.99 → 999 cents → Pro (150 bps) → 14 cents (integer trunc, not float)
    fee = (999L * Plan.PRO.getPlatformFeeBps()) / 10_000L;
    assertEquals(14L, fee);
  }

  @Test
  void statementDescriptorStripsForbiddenChars() {
    assertEquals("Blue Note NYC",
        StripePaymentService.sanitizeStatementDescriptor("Blue \"Note\" NYC"));
    assertEquals("My Venue",
        StripePaymentService.sanitizeStatementDescriptor("My  *Venue*"));
  }

  @Test
  void statementDescriptorTruncatesTo22Chars() {
    String result = StripePaymentService.sanitizeStatementDescriptor(
        "An Extremely Long Venue Name That Wont Fit");
    assertEquals(22, result.length());
    assertEquals("An Extremely Long Venu", result);
  }

  @Test
  void statementDescriptorReturnsNullWhenEmpty() {
    assertNull(StripePaymentService.sanitizeStatementDescriptor(null));
    assertNull(StripePaymentService.sanitizeStatementDescriptor(""));
    assertNull(StripePaymentService.sanitizeStatementDescriptor("  "));
    assertNull(StripePaymentService.sanitizeStatementDescriptor("***"));
  }
}
