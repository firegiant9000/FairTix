package com.fairtix.reports.application;

import com.fairtix.reports.domain.SplitType;
import com.fairtix.reports.dto.ReportDtos.BoxOfficeMethodRow;
import com.fairtix.reports.dto.ReportDtos.CompReasonRow;
import com.fairtix.reports.dto.ReportDtos.DosReport;
import com.fairtix.reports.dto.ReportDtos.SettlementReport;
import com.fairtix.reports.dto.ReportDtos.TierCount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2-15/M2-16 reconciliation contract: the CSV export and the HTML
 * print-to-PDF surface MUST present the same monetary values. If a future
 * refactor drifts one path's formula away from the other, accountants will
 * see different totals on different reports for the same event — the kind of
 * "wrong by a dollar" bug that destroys platform trust.
 *
 * <p>The test renders both formats from one shared report DTO, extracts the
 * five canonical money fields from each, and asserts equality to the penny.
 */
class ReportRendererReconciliationTest {

  // HTML rows render as <td>label</td><td class="num">$value</td>, where
  // negative-side numbers (refunds, fees) are wrapped in parens by the renderer.
  // Allow the optional $ prefix and optional surrounding parens so the test
  // matches both the positive ("Gross") and parenthesized-negative ("Refunds")
  // rows uniformly.
  private static final Pattern HTML_ROW =
      Pattern.compile("<td>([^<]+)</td><td class=\"num\">\\$?\\(?([0-9]+\\.[0-9]{2})\\)?");

  @Test
  void dosCsvAndHtmlAgreeOnEveryMoneyField() {
    DosReport dos = sampleDos();
    String csv = ReportRenderer.dosCsv(dos);
    String html = ReportRenderer.dosHtml(dos);

    assertMoneyEqual(csv, html, "Gross (PAID face values)", money(dos.gross()));
    assertMoneyEqual(csv, html, "Add-ons", money(dos.addOnRevenue()));
    assertMoneyEqual(csv, html, "Sales tax collected", money(dos.salesTaxCollected()));
    assertMoneyEqual(csv, html, "Refunds (pre-show)", money(dos.preShowRefunds()));
    assertMoneyEqual(csv, html, "Platform fee", money(dos.platformFee()));
    assertMoneyEqual(csv, html, "Stripe processing fee", money(dos.stripeProcessingFee()));
    assertMoneyEqual(csv, html, "Net to venue", money(dos.net()));
  }

  @Test
  void settlementCsvAndHtmlAgreeOnSplitAndPayout() {
    DosReport dos = sampleDos();
    SettlementReport s = new SettlementReport(
        dos.eventId(),
        dos.eventTitle(),
        dos.eventStartTime(),
        dos.venueName(),
        Instant.parse("2026-05-22T03:00:00Z"),
        dos,
        new BigDecimal("25.00"),                 // post-show refunds
        SplitType.FLAT_PCT,
        new BigDecimal("0.7500"),
        null,
        new BigDecimal("661.50"),                // artist payout
        new BigDecimal("220.50"),                // venue retention
        false, null, null, "Test settlement");

    String csv = ReportRenderer.settlementCsv(s);
    String html = ReportRenderer.settlementHtml(s);

    assertMoneyEqual(csv, html, "Post-show refunds", money(s.postShowRefunds()));
    assertMoneyEqual(csv, html, "Artist payout", money(s.artistPayout()));
    assertMoneyEqual(csv, html, "Venue retention", money(s.venueRetention()));
  }

  // --- helpers --------------------------------------------------------------

  private static DosReport sampleDos() {
    return new DosReport(
        UUID.fromString("11111111-1111-1111-1111-111111111111"),
        "Coltrane Tribute",
        Instant.parse("2026-08-12T01:30:00Z"),
        "Blue Note",
        Instant.parse("2026-08-12T02:00:00Z"),
        List.of(
            new TierCount("PAID", 50, new BigDecimal("1000.00")),
            new TierCount("COMP", 3, BigDecimal.ZERO)),
        List.of(new CompReasonRow("Press", 2), new CompReasonRow("House", 1)),
        4,
        List.of(new BoxOfficeMethodRow("CASH", 6, new BigDecimal("120.00"), 6)),
        new BigDecimal("1000.00"),  // gross
        new BigDecimal("0.00"),     // add-ons
        new BigDecimal("80.00"),    // sales tax
        new BigDecimal("15.00"),    // pre-show refunds
        new BigDecimal("15.00"),    // platform fee
        new BigDecimal("33.50"),    // Stripe fee
        new BigDecimal("856.50"));  // net = 1000 + 0 - 80 - 15 - 15 - 33.50
  }

  private static String money(BigDecimal v) {
    return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
  }

  /** Asserts that {@code key} carries the same numeric value in CSV and HTML. */
  private static void assertMoneyEqual(String csv, String html, String key, String expected) {
    String csvValue = extractCsvValue(csv, key);
    String htmlValue = extractHtmlValue(html, key);

    assertThat(csvValue)
        .as("CSV missing or mismatched value for '%s'", key)
        .isEqualTo(expected);
    assertThat(htmlValue)
        .as("HTML missing or mismatched value for '%s' (csv had %s)", key, csvValue)
        .isEqualTo(expected);
  }

  private static String extractCsvValue(String csv, String key) {
    for (String line : csv.split("\n")) {
      // Each row is section,key,value — split on first two commas, take rest as value
      int firstComma = line.indexOf(',');
      if (firstComma < 0) continue;
      int secondComma = line.indexOf(',', firstComma + 1);
      if (secondComma < 0) continue;
      String rowKey = stripQuotes(line.substring(firstComma + 1, secondComma));
      if (rowKey.equals(key)) {
        return stripQuotes(line.substring(secondComma + 1).trim());
      }
    }
    return null;
  }

  private static String extractHtmlValue(String html, String key) {
    // Strip trailing closing-paren wrappers that HTML uses for negatives:
    // e.g. "(15.00)" in HTML maps back to "15.00" in the underlying value.
    Matcher m = HTML_ROW.matcher(html);
    while (m.find()) {
      String label = m.group(1).trim();
      String value = m.group(2);
      if (label.equals(key)) return value;
    }
    return null;
  }

  private static String stripQuotes(String s) {
    if (s == null) return null;
    if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
      return s.substring(1, s.length() - 1).replace("\"\"", "\"");
    }
    return s;
  }
}
