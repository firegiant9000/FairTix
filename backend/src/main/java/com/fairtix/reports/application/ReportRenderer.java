package com.fairtix.reports.application;

import com.fairtix.reports.dto.ReportDtos.BoxOfficeMethodRow;
import com.fairtix.reports.dto.ReportDtos.CompReasonRow;
import com.fairtix.reports.dto.ReportDtos.DosReport;
import com.fairtix.reports.dto.ReportDtos.PayoutEventRollup;
import com.fairtix.reports.dto.ReportDtos.PayoutRow;
import com.fairtix.reports.dto.ReportDtos.SettlementReport;
import com.fairtix.reports.dto.ReportDtos.TaxEventRow;
import com.fairtix.reports.dto.ReportDtos.TaxYearExport;
import com.fairtix.reports.dto.ReportDtos.TierCount;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Plain CSV / HTML emitters. Both formats must reconcile to the same numbers;
 * we deliberately share fields off the DTO rather than re-computing in either
 * code path. Browser print-to-PDF on the HTML output stands in for a real
 * PDF pipeline per the "no new dependencies" project rule.
 */
public final class ReportRenderer {

  private ReportRenderer() {}

  private static final DateTimeFormatter EVENT_DTF =
      DateTimeFormatter.ofPattern("EEE, MMM d yyyy 'at' h:mm a").withZone(ZoneOffset.UTC);

  // -------------------------------------------------------------- DOS CSV

  public static String dosCsv(DosReport r) {
    StringBuilder sb = new StringBuilder(1024);
    sb.append("section,key,value\n");
    sb.append(row("Event", "Title", r.eventTitle()));
    sb.append(row("Event", "Start", r.eventStartTime().toString()));
    sb.append(row("Event", "Venue", r.venueName()));
    sb.append(row("Event", "Generated", r.generatedAt().toString()));
    for (TierCount t : r.ticketBreakdown()) {
      sb.append(row("Tickets", t.kind() + " count", String.valueOf(t.count())));
      sb.append(row("Tickets", t.kind() + " gross", money(t.gross())));
    }
    for (CompReasonRow c : r.compReasons()) {
      sb.append(row("Comps", c.reason(), String.valueOf(c.count())));
    }
    sb.append(row("Holds", "Unclaimed at report time", String.valueOf(r.heldCount())));
    for (BoxOfficeMethodRow m : r.boxOffice()) {
      sb.append(row("Box office", m.method() + " sales", String.valueOf(m.sales())));
      sb.append(row("Box office", m.method() + " amount", money(m.amount())));
      sb.append(row("Box office", m.method() + " seats", String.valueOf(m.seats())));
    }
    sb.append(row("Revenue", "Gross (PAID face values)", money(r.gross())));
    sb.append(row("Revenue", "Add-ons", money(r.addOnRevenue())));
    sb.append(row("Revenue", "Sales tax collected", money(r.salesTaxCollected())));
    sb.append(row("Revenue", "Refunds (pre-show)", money(r.preShowRefunds())));
    sb.append(row("Fees", "Platform fee", money(r.platformFee())));
    sb.append(row("Fees", "Stripe processing fee", money(r.stripeProcessingFee())));
    sb.append(row("Total", "Net to venue", money(r.net())));
    return sb.toString();
  }

  // -------------------------------------------------------------- Settlement CSV

  public static String settlementCsv(SettlementReport s) {
    StringBuilder sb = new StringBuilder(2048);
    sb.append(dosCsv(s.dosSnapshot()));
    sb.append(row("Settlement", "Post-show refunds", money(s.postShowRefunds())));
    sb.append(row("Settlement", "Split type", s.splitType() == null ? "(none)" : s.splitType().name()));
    sb.append(row("Settlement", "Artist pct", s.artistPct() == null ? "" : s.artistPct().toPlainString()));
    sb.append(row("Settlement", "Venue take off top", money(s.venueTakeOffTop())));
    sb.append(row("Settlement", "Artist payout", money(s.artistPayout())));
    sb.append(row("Settlement", "Venue retention", money(s.venueRetention())));
    sb.append(row("Settlement", "Finalized", String.valueOf(s.finalized())));
    if (s.finalizedAt() != null) {
      sb.append(row("Settlement", "Finalized at", s.finalizedAt().toString()));
    }
    if (s.notes() != null) sb.append(row("Settlement", "Notes", s.notes()));
    return sb.toString();
  }

  // -------------------------------------------------------------- DOS HTML

  public static String dosHtml(DosReport r) {
    StringBuilder sb = new StringBuilder(4096);
    sb.append(htmlHead("Day-of-show report — " + r.eventTitle()));
    sb.append("<section class=\"page\">");
    sb.append("<h1>Day-of-show report</h1>");
    sb.append("<div class=\"meta\">").append(escape(r.eventTitle())).append("</div>");
    sb.append("<div class=\"meta\">")
      .append(escape(r.venueName())).append(" &middot; ")
      .append(EVENT_DTF.format(r.eventStartTime())).append("</div>");
    sb.append("<div class=\"meta small\">Generated ").append(r.generatedAt()).append("</div>");

    sb.append("<h2>Tickets</h2><table>");
    sb.append("<tr><th>Kind</th><th class=\"num\">Count</th><th class=\"num\">Gross</th></tr>");
    for (TierCount t : r.ticketBreakdown()) {
      sb.append("<tr><td>").append(escape(t.kind())).append("</td>")
        .append("<td class=\"num\">").append(t.count()).append("</td>")
        .append("<td class=\"num\">$").append(money(t.gross())).append("</td></tr>");
    }
    sb.append("</table>");

    if (!r.compReasons().isEmpty()) {
      sb.append("<h2>Comps by reason</h2><table>");
      for (CompReasonRow c : r.compReasons()) {
        sb.append("<tr><td>").append(escape(c.reason())).append("</td>")
          .append("<td class=\"num\">").append(c.count()).append("</td></tr>");
      }
      sb.append("</table>");
    }

    if (!r.boxOffice().isEmpty()) {
      sb.append("<h2>Box office</h2><table>");
      sb.append("<tr><th>Method</th><th class=\"num\">Sales</th><th class=\"num\">Amount</th><th class=\"num\">Seats</th></tr>");
      for (BoxOfficeMethodRow m : r.boxOffice()) {
        sb.append("<tr><td>").append(escape(m.method())).append("</td>")
          .append("<td class=\"num\">").append(m.sales()).append("</td>")
          .append("<td class=\"num\">$").append(money(m.amount())).append("</td>")
          .append("<td class=\"num\">").append(m.seats()).append("</td></tr>");
      }
      sb.append("</table>");
    }

    sb.append("<h2>Revenue</h2><table>");
    sb.append(htmlRow("Gross (PAID face values)", money(r.gross())));
    sb.append(htmlRow("Add-ons", money(r.addOnRevenue())));
    sb.append(htmlRow("Sales tax collected", money(r.salesTaxCollected())));
    sb.append(htmlRow("Refunds (pre-show)", "(" + money(r.preShowRefunds()) + ")"));
    sb.append(htmlRow("Platform fee", "(" + money(r.platformFee()) + ")"));
    sb.append(htmlRow("Stripe processing fee", "(" + money(r.stripeProcessingFee()) + ")"));
    sb.append("<tr class=\"total\"><td>Net to venue</td><td class=\"num\">$")
      .append(money(r.net())).append("</td></tr>");
    sb.append("</table>");
    sb.append("</section></body></html>");
    return sb.toString();
  }

  // -------------------------------------------------------------- Settlement HTML

  public static String settlementHtml(SettlementReport s) {
    StringBuilder sb = new StringBuilder(8192);
    sb.append(htmlHead("Settlement — " + s.eventTitle()));
    sb.append("<section class=\"page\">");
    sb.append("<h1>Settlement report</h1>");
    sb.append("<div class=\"meta\">").append(escape(s.eventTitle())).append("</div>");
    sb.append("<div class=\"meta\">").append(escape(s.venueName())).append(" &middot; ")
      .append(EVENT_DTF.format(s.eventStartTime())).append("</div>");
    sb.append("<div class=\"meta small\">Generated ").append(s.generatedAt()).append("</div>");

    sb.append("<h2>Revenue summary</h2><table>");
    DosReport d = s.dosSnapshot();
    sb.append(htmlRow("Gross (PAID face values)", money(d.gross())));
    sb.append(htmlRow("Add-ons", money(d.addOnRevenue())));
    sb.append(htmlRow("Sales tax collected", money(d.salesTaxCollected())));
    sb.append(htmlRow("Refunds (pre-show)", "(" + money(d.preShowRefunds()) + ")"));
    // Label must match the CSV key ("Post-show refunds") so reconciliation
    // tooling and accountants see the same row name on both surfaces.
    sb.append(htmlRow("Post-show refunds", "(" + money(s.postShowRefunds()) + ")"));
    sb.append(htmlRow("Platform fee", "(" + money(d.platformFee()) + ")"));
    sb.append(htmlRow("Stripe processing fee", "(" + money(d.stripeProcessingFee()) + ")"));
    BigDecimal settlementNet = d.net().subtract(s.postShowRefunds());
    sb.append("<tr class=\"total\"><td>Net (settlement)</td><td class=\"num\">$")
      .append(money(settlementNet)).append("</td></tr>");
    sb.append("</table>");

    sb.append("<h2>Split</h2><table>");
    sb.append(htmlRow("Type", s.splitType() == null ? "(not configured)" : s.splitType().name()));
    if (s.artistPct() != null) {
      sb.append(htmlRow("Artist pct", s.artistPct().multiply(BigDecimal.valueOf(100)).toPlainString() + "%"));
    }
    if (s.venueTakeOffTop() != null) {
      sb.append(htmlRow("Venue off the top", money(s.venueTakeOffTop())));
    }
    sb.append("<tr><td>Artist payout</td><td class=\"num\">$").append(money(s.artistPayout())).append("</td></tr>");
    sb.append("<tr class=\"total\"><td>Venue retention</td><td class=\"num\">$")
      .append(money(s.venueRetention())).append("</td></tr>");
    sb.append("</table>");

    if (s.notes() != null && !s.notes().isBlank()) {
      sb.append("<h2>Notes</h2><p>").append(escape(s.notes())).append("</p>");
    }

    sb.append("<h2>Sign-off</h2>");
    if (s.finalized()) {
      sb.append("<div class=\"finalized\">Finalized at ").append(s.finalizedAt())
        .append(" by user ").append(s.finalizedByUserId()).append("</div>");
    } else {
      sb.append("<div>Pending sign-off.</div>");
    }
    sb.append("<div class=\"signature\">");
    sb.append("<div class=\"sigbox\"><div class=\"sigline\"></div>Venue representative</div>");
    sb.append("<div class=\"sigbox\"><div class=\"sigline\"></div>Artist / agent</div>");
    sb.append("</div>");
    sb.append("</section></body></html>");
    return sb.toString();
  }

  // -------------------------------------------------------------- Payouts CSV

  public static String payoutsCsv(java.util.List<PayoutRow> rows) {
    StringBuilder sb = new StringBuilder();
    sb.append("stripe_payout_id,amount,currency,status,arrival_date,paid_at,failure_code,failure_message,event_count\n");
    for (PayoutRow p : rows) {
      sb.append(csv(p.stripePayoutId())).append(',')
        .append(money(p.amount())).append(',')
        .append(csv(p.currency())).append(',')
        .append(csv(p.status())).append(',')
        .append(p.arrivalDate() == null ? "" : p.arrivalDate()).append(',')
        .append(p.paidAt() == null ? "" : p.paidAt()).append(',')
        .append(csv(p.failureCode())).append(',')
        .append(csv(p.failureMessage())).append(',')
        .append(p.events() == null ? 0 : p.events().size())
        .append('\n');
      if (p.events() != null) {
        for (PayoutEventRollup e : p.events()) {
          sb.append("  ,").append(csv(e.title())).append(',')
            .append(csv(e.eventId().toString())).append(',')
            .append(e.ticketsSold()).append(',')
            .append(money(e.grossContributed())).append('\n');
        }
      }
    }
    return sb.toString();
  }

  // -------------------------------------------------------------- Tax CSV

  public static String taxYearCsv(TaxYearExport x) {
    StringBuilder sb = new StringBuilder();
    sb.append("year,state,event_id,event_title,event_date,gross,tax_rate_pct,tax_collected\n");
    for (TaxEventRow r : x.rows()) {
      sb.append(x.year()).append(',')
        .append(csv(x.state())).append(',')
        .append(csv(r.eventId().toString())).append(',')
        .append(csv(r.title())).append(',')
        .append(r.eventDate() == null ? "" : r.eventDate().toString()).append(',')
        .append(money(r.gross())).append(',')
        .append(r.taxRatePct() == null ? "" : r.taxRatePct().toPlainString()).append(',')
        .append(money(r.taxCollected())).append('\n');
    }
    sb.append("TOTAL,").append(csv(x.state())).append(",,,").append(money(x.totalGross()))
      .append(",,").append(money(x.totalTaxCollected())).append('\n');
    return sb.toString();
  }

  // -------------------------------------------------------------- shared utils

  private static String row(String section, String key, String value) {
    return csv(section) + "," + csv(key) + "," + csv(value == null ? "" : value) + "\n";
  }

  private static String htmlRow(String label, String value) {
    return "<tr><td>" + escape(label) + "</td><td class=\"num\">$" + value + "</td></tr>";
  }

  private static String htmlHead(String title) {
    return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + escape(title) + "</title>"
        + "<style>"
        + " @page { size: letter; margin: 0.5in; }"
        + " body { font-family: -apple-system, system-ui, sans-serif; color: #222; margin: 0; }"
        + " .page { padding: 0.5in; }"
        + " h1 { font-size: 24pt; margin: 0 0 4pt 0; }"
        + " h2 { font-size: 14pt; margin: 24pt 0 4pt 0; border-bottom: 1pt solid #ddd; padding-bottom: 2pt; }"
        + " .meta { color: #555; font-size: 11pt; }"
        + " .meta.small { color: #999; font-size: 9pt; }"
        + " table { width: 100%; border-collapse: collapse; margin-top: 6pt; }"
        + " th, td { padding: 4pt 6pt; text-align: left; font-size: 11pt; border-bottom: 0.5pt solid #eee; }"
        + " th { background: #f5f5f5; font-weight: 600; }"
        + " td.num, th.num { text-align: right; font-variant-numeric: tabular-nums; }"
        + " tr.total td { font-weight: 700; border-top: 1pt solid #222; border-bottom: 1pt solid #222; }"
        + " .finalized { color: #1b5e20; font-weight: 600; }"
        + " .signature { display: flex; gap: 36pt; margin-top: 36pt; }"
        + " .sigbox { flex: 1; }"
        + " .sigline { border-bottom: 1pt solid #222; height: 36pt; margin-bottom: 6pt; }"
        + "</style></head><body>";
  }

  private static String money(BigDecimal v) {
    if (v == null) return "0.00";
    return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
  }

  private static String csv(String s) {
    if (s == null) return "";
    boolean needsQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0;
    if (!needsQuote) return s;
    return '"' + s.replace("\"", "\"\"") + '"';
  }

  private static String escape(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
  }
}
