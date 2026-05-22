package com.fairtix.reports.dto;

import com.fairtix.reports.domain.SplitType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Container for the M2-15..M2-18 report DTOs. Records are immutable;
 * monetary fields use BigDecimal with scale 2 — the report renderers and the
 * CSV exporter both rely on the values arriving at the canonical scale, so
 * services normalize there.
 */
public final class ReportDtos {

  private ReportDtos() {}

  // ---- DOS / Settlement common shape ----

  public record TierCount(String kind, long count, BigDecimal gross) {}

  public record CompReasonRow(String reason, long count) {}

  public record BoxOfficeMethodRow(String method, long sales, BigDecimal amount, long seats) {}

  /**
   * Day-of-show report (M2-15). Generated post-doors-open. The {@code net}
   * field below is the canonical "what the venue actually keeps" number; the
   * settlement layer adds artist split on top.
   */
  public record DosReport(
      UUID eventId,
      String eventTitle,
      Instant eventStartTime,
      String venueName,
      Instant generatedAt,
      List<TierCount> ticketBreakdown,    // kinds: PAID, COMP, HOLD_*
      List<CompReasonRow> compReasons,
      long heldCount,
      List<BoxOfficeMethodRow> boxOffice, // CASH/CARD/COMP from box-office sales
      BigDecimal gross,                   // sum of PAID ticket face values, non-cancelled
      BigDecimal addOnRevenue,            // 0 until add-ons land in M3
      BigDecimal salesTaxCollected,
      BigDecimal preShowRefunds,
      BigDecimal platformFee,             // organization plan bps * gross
      BigDecimal stripeProcessingFee,     // 2.9% + $0.30/ticket baseline (recompute from BalanceTx in M5)
      BigDecimal net) {                   // gross + add-ons - tax - refunds - fees
  }

  /** Settlement report (M2-16). Post-show + 24h refund window. */
  public record SettlementReport(
      UUID eventId,
      String eventTitle,
      Instant eventStartTime,
      String venueName,
      Instant generatedAt,
      DosReport dosSnapshot,
      BigDecimal postShowRefunds,
      SplitType splitType,
      BigDecimal artistPct,
      BigDecimal venueTakeOffTop,
      BigDecimal artistPayout,
      BigDecimal venueRetention,
      boolean finalized,
      Instant finalizedAt,
      UUID finalizedByUserId,
      String notes) {}

  public record SettlementConfigRequest(
      SplitType splitType,
      BigDecimal artistPct,
      BigDecimal venueTakeOffTop,
      BigDecimal taxRatePct,
      String notes) {}

  public record SettlementConfigResponse(
      UUID eventId,
      SplitType splitType,
      BigDecimal artistPct,
      BigDecimal venueTakeOffTop,
      BigDecimal taxRatePct,
      String notes,
      boolean finalized,
      Instant finalizedAt) {}

  // ---- Payout report (M2-17) ----

  public record PayoutEventRollup(
      UUID eventId,
      String title,
      Instant startTime,
      long ticketsSold,
      BigDecimal grossContributed) {}

  public record PayoutRow(
      String stripePayoutId,
      BigDecimal amount,
      String currency,
      String status,
      LocalDate arrivalDate,
      Instant paidAt,
      String failureCode,
      String failureMessage,
      List<PayoutEventRollup> events) {}

  // ---- Tax helper (M2-18) ----

  public record TaxThreshold(
      int year,
      BigDecimal ytdGross,
      long ytdTransactions,
      BigDecimal threshold,
      BigDecimal pctOfThreshold,
      boolean alert,                  // true at 80%+
      String state,
      BigDecimal defaultTaxRatePct,
      String taxLegalName,
      boolean einOnFile,
      Map<UUID, BigDecimal> eventTaxOverrides) {}

  public record TaxEventRow(
      UUID eventId,
      String title,
      LocalDate eventDate,
      BigDecimal gross,
      BigDecimal taxRatePct,
      BigDecimal taxCollected,
      String state) {}

  public record TaxYearExport(
      int year,
      String state,
      BigDecimal totalGross,
      BigDecimal totalTaxCollected,
      List<TaxEventRow> rows) {}
}
