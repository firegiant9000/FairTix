package com.fairtix.reports.api;

import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.organizations.application.OrgScoped;
import com.fairtix.organizations.domain.OrgPermission;
import com.fairtix.reports.application.PayoutReportService;
import com.fairtix.reports.application.ReportRenderer;
import com.fairtix.reports.application.SettlementService;
import com.fairtix.reports.application.TaxReportService;
import com.fairtix.reports.dto.ReportDtos.DosReport;
import com.fairtix.reports.dto.ReportDtos.PayoutRow;
import com.fairtix.reports.dto.ReportDtos.SettlementConfigRequest;
import com.fairtix.reports.dto.ReportDtos.SettlementConfigResponse;
import com.fairtix.reports.dto.ReportDtos.SettlementReport;
import com.fairtix.reports.dto.ReportDtos.TaxThreshold;
import com.fairtix.reports.dto.ReportDtos.TaxYearExport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Phase 2F (M2-15..M2-18): Day-of-show, settlement, payout, and tax helper reports.
 *
 * All endpoints are org-scoped. JSON variants drive the dashboards; CSV
 * variants are accountant-friendly exports; HTML variants are browser
 * print-to-PDF stand-ins per the project's "no new dependencies" rule.
 */
@Tag(name = "Reports", description = "Settlement and tax reports")
@RestController
@RequestMapping("/api/organizations/{orgId}/reports")
public class ReportsController {

  private final SettlementService settlements;
  private final PayoutReportService payouts;
  private final TaxReportService tax;

  public ReportsController(SettlementService settlements,
                           PayoutReportService payouts,
                           TaxReportService tax) {
    this.settlements = settlements;
    this.payouts = payouts;
    this.tax = tax;
  }

  // -------------------------------------------------------------- DOS

  @Operation(summary = "Day-of-show report for an event (JSON)")
  @GetMapping("/events/{eventId}/dos")
  @OrgScoped(OrgPermission.REPORTS_READ)
  public DosReport dos(@PathVariable UUID orgId, @PathVariable UUID eventId) {
    return settlements.dosReport(orgId, eventId);
  }

  @Operation(summary = "Day-of-show report (CSV)")
  @GetMapping(value = "/events/{eventId}/dos.csv", produces = "text/csv")
  @OrgScoped(OrgPermission.REPORTS_READ)
  public ResponseEntity<String> dosCsv(@PathVariable UUID orgId, @PathVariable UUID eventId) {
    String body = ReportRenderer.dosCsv(settlements.dosReport(orgId, eventId));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"dos-" + eventId + ".csv\"")
        .contentType(MediaType.parseMediaType("text/csv"))
        .body(body);
  }

  @Operation(summary = "Day-of-show report (HTML for print-to-PDF)")
  @GetMapping(value = "/events/{eventId}/dos.html", produces = MediaType.TEXT_HTML_VALUE)
  @OrgScoped(OrgPermission.REPORTS_READ)
  public ResponseEntity<String> dosHtml(@PathVariable UUID orgId, @PathVariable UUID eventId) {
    String body = ReportRenderer.dosHtml(settlements.dosReport(orgId, eventId));
    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(body);
  }

  // -------------------------------------------------------------- Settlement

  @Operation(summary = "Settlement report for an event (JSON)")
  @GetMapping("/events/{eventId}/settlement")
  @OrgScoped(OrgPermission.REPORTS_READ)
  public SettlementReport settlement(@PathVariable UUID orgId, @PathVariable UUID eventId) {
    return settlements.settlement(orgId, eventId);
  }

  @Operation(summary = "Settlement report (CSV)")
  @GetMapping(value = "/events/{eventId}/settlement.csv", produces = "text/csv")
  @OrgScoped(OrgPermission.REPORTS_READ)
  public ResponseEntity<String> settlementCsv(@PathVariable UUID orgId, @PathVariable UUID eventId) {
    String body = ReportRenderer.settlementCsv(settlements.settlement(orgId, eventId));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"settlement-" + eventId + ".csv\"")
        .contentType(MediaType.parseMediaType("text/csv"))
        .body(body);
  }

  @Operation(summary = "Settlement report (HTML for print-to-PDF; signable)")
  @GetMapping(value = "/events/{eventId}/settlement.html", produces = MediaType.TEXT_HTML_VALUE)
  @OrgScoped(OrgPermission.REPORTS_READ)
  public ResponseEntity<String> settlementHtml(@PathVariable UUID orgId, @PathVariable UUID eventId) {
    String body = ReportRenderer.settlementHtml(settlements.settlement(orgId, eventId));
    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(body);
  }

  @Operation(summary = "Get settlement split / tax configuration for an event")
  @GetMapping("/events/{eventId}/settlement/config")
  @OrgScoped(OrgPermission.REPORTS_READ)
  public SettlementConfigResponse getConfig(@PathVariable UUID orgId, @PathVariable UUID eventId) {
    return settlements.getConfig(orgId, eventId);
  }

  @Operation(summary = "Upsert settlement split / tax configuration for an event")
  @PutMapping("/events/{eventId}/settlement/config")
  @OrgScoped(OrgPermission.SETTINGS_WRITE)
  public SettlementConfigResponse upsertConfig(@AuthenticationPrincipal CustomUserPrincipal principal,
                                               @PathVariable UUID orgId,
                                               @PathVariable UUID eventId,
                                               @RequestBody SettlementConfigRequest req) {
    return settlements.upsertConfig(orgId, eventId, principal.getUserId(), req);
  }

  @Operation(summary = "Finalize (sign off) a settlement")
  @PostMapping("/events/{eventId}/settlement/finalize")
  @OrgScoped(OrgPermission.SETTINGS_WRITE)
  public SettlementConfigResponse finalizeSettlement(@AuthenticationPrincipal CustomUserPrincipal principal,
                                                     @PathVariable UUID orgId,
                                                     @PathVariable UUID eventId) {
    return settlements.finalizeSettlement(orgId, eventId, principal.getUserId());
  }

  // -------------------------------------------------------------- Payouts (M2-17)

  @Operation(summary = "Recent payouts (default 30 days) with per-event drill-down")
  @GetMapping("/payouts")
  @OrgScoped(OrgPermission.PAYOUTS_READ)
  public List<PayoutRow> recentPayouts(@PathVariable UUID orgId,
                                       @RequestParam(defaultValue = "30") int days) {
    return payouts.recentPayouts(orgId, days);
  }

  @Operation(summary = "Payouts CSV")
  @GetMapping(value = "/payouts.csv", produces = "text/csv")
  @OrgScoped(OrgPermission.PAYOUTS_READ)
  public ResponseEntity<String> payoutsCsv(@PathVariable UUID orgId,
                                           @RequestParam(defaultValue = "30") int days) {
    String body = ReportRenderer.payoutsCsv(payouts.recentPayouts(orgId, days));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"payouts-" + LocalDate.now(ZoneOffset.UTC) + ".csv\"")
        .contentType(MediaType.parseMediaType("text/csv"))
        .body(body);
  }

  @Operation(summary = "Force-sync the latest payouts from Stripe (no-op when stripe.enabled=false)")
  @PostMapping("/payouts/sync")
  @OrgScoped(OrgPermission.PAYOUTS_READ)
  public int syncPayouts(@PathVariable UUID orgId,
                         @RequestParam(defaultValue = "25") int limit) {
    return payouts.syncRecentFromStripe(orgId, limit);
  }

  // -------------------------------------------------------------- Tax (M2-18)

  @Operation(summary = "1099-K threshold tracking + state tax summary for a calendar year")
  @GetMapping("/tax/threshold")
  @OrgScoped(OrgPermission.REPORTS_READ)
  public TaxThreshold threshold(@PathVariable UUID orgId,
                                @RequestParam(required = false) Integer year) {
    int y = year == null ? LocalDate.now(ZoneOffset.UTC).getYear() : year;
    return tax.threshold(orgId, y);
  }

  @Operation(summary = "Year-end per-event tax export")
  @GetMapping("/tax/year")
  @OrgScoped(OrgPermission.REPORTS_READ)
  public TaxYearExport yearExport(@PathVariable UUID orgId,
                                  @RequestParam(required = false) Integer year) {
    int y = year == null ? LocalDate.now(ZoneOffset.UTC).getYear() : year;
    return tax.yearlyExport(orgId, y);
  }

  @Operation(summary = "Year-end per-event tax export (CSV)")
  @GetMapping(value = "/tax/year.csv", produces = "text/csv")
  @OrgScoped(OrgPermission.REPORTS_READ)
  public ResponseEntity<String> yearExportCsv(@PathVariable UUID orgId,
                                              @RequestParam(required = false) Integer year) {
    int y = year == null ? LocalDate.now(ZoneOffset.UTC).getYear() : year;
    String body = ReportRenderer.taxYearCsv(tax.yearlyExport(orgId, y));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"tax-" + y + ".csv\"")
        .contentType(MediaType.parseMediaType("text/csv"))
        .body(body);
  }

  public record TaxConfigRequest(String state, BigDecimal defaultTaxRatePct,
                                 String taxLegalName, String ein) {}

  @Operation(summary = "Update org-level tax configuration (state, default rate, legal name, EIN)")
  @PutMapping("/tax/config")
  @OrgScoped(OrgPermission.SETTINGS_WRITE)
  public void updateTaxConfig(@PathVariable UUID orgId, @RequestBody TaxConfigRequest req) {
    tax.updateTaxConfig(orgId, req.state(), req.defaultTaxRatePct(), req.taxLegalName(), req.ein());
  }
}
