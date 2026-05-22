package com.fairtix.reports.application;

import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.reports.dto.ReportDtos.TaxEventRow;
import com.fairtix.reports.dto.ReportDtos.TaxThreshold;
import com.fairtix.reports.dto.ReportDtos.TaxYearExport;
import com.fairtix.reports.infrastructure.EventSettlementRepository;
import com.fairtix.reports.infrastructure.ReportQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M2-18: 1099-K threshold tracker + per-event sales-tax rollup.
 *
 * The threshold itself is in {@link ReportMath#FEDERAL_1099K_THRESHOLD}; we
 * alert at 80% (per the implementation plan). State-level tax handling is
 * deliberately minimal — per-org default + per-event override, no TaxJar
 * lookups, no nexus tracking. Roadmap defers dynamic tax to M5+.
 */
@Service
@Transactional(readOnly = true)
public class TaxReportService {

  private static final BigDecimal ALERT_FRACTION = new BigDecimal("0.80");

  private final ReportQueryRepository queries;
  private final OrganizationRepository organizations;
  private final EventSettlementRepository settlements;

  public TaxReportService(ReportQueryRepository queries,
                          OrganizationRepository organizations,
                          EventSettlementRepository settlements) {
    this.queries = queries;
    this.organizations = organizations;
    this.settlements = settlements;
  }

  public TaxThreshold threshold(UUID orgId, int year) {
    Organization org = organizations.findById(orgId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

    Object[] row = queries.orgYtdGrossAndCount(orgId, year);
    BigDecimal gross = ReportMath.money((BigDecimal) row[0]);
    long count = ((Number) row[1]).longValue();
    BigDecimal threshold = ReportMath.FEDERAL_1099K_THRESHOLD;
    BigDecimal pct = threshold.signum() == 0 ? BigDecimal.ZERO
        : gross.divide(threshold, 4, RoundingMode.HALF_UP);
    boolean alert = pct.compareTo(ALERT_FRACTION) >= 0;

    Map<UUID, BigDecimal> overrides = new HashMap<>();
    // Only include events with non-null override
    settlements.findAll().forEach(s -> {
      if (s.getTaxRatePct() != null) overrides.put(s.getEventId(), s.getTaxRatePct());
    });

    return new TaxThreshold(
        year,
        gross,
        count,
        threshold,
        pct,
        alert,
        org.getTaxState(),
        org.getDefaultTaxRatePct(),
        org.getTaxLegalName(),
        org.getTaxIdEin() != null && !org.getTaxIdEin().isBlank(),
        overrides);
  }

  public TaxYearExport yearlyExport(UUID orgId, int year) {
    Organization org = organizations.findById(orgId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
    BigDecimal defaultRate = org.getDefaultTaxRatePct();

    List<TaxEventRow> rows = new ArrayList<>();
    BigDecimal totalGross = BigDecimal.ZERO;
    BigDecimal totalTax = BigDecimal.ZERO;

    for (Object[] r : queries.taxRowsForYear(orgId, year)) {
      UUID eventId = (UUID) r[0];
      String title = (String) r[1];
      java.time.LocalDate date = ((java.sql.Timestamp) r[2]).toInstant()
          .atZone(ZoneOffset.UTC).toLocalDate();
      BigDecimal gross = ReportMath.money((BigDecimal) r[3]);

      BigDecimal rate = settlements.findByEventId(eventId)
          .map(s -> s.getTaxRatePct())
          .filter(v -> v != null)
          .orElse(defaultRate);
      BigDecimal collected = ReportMath.salesTax(gross, rate);

      rows.add(new TaxEventRow(eventId, title, date, gross, rate, collected, org.getTaxState()));
      totalGross = totalGross.add(gross);
      totalTax = totalTax.add(collected);
    }

    return new TaxYearExport(
        year,
        org.getTaxState(),
        ReportMath.money(totalGross),
        ReportMath.money(totalTax),
        rows);
  }

  @Transactional
  public Organization updateTaxConfig(UUID orgId, String state, BigDecimal defaultRate,
                                      String legalName, String ein) {
    Organization org = organizations.findById(orgId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
    if (state != null) org.setTaxState(state.toUpperCase());
    if (defaultRate != null) {
      if (defaultRate.signum() < 0 || defaultRate.compareTo(BigDecimal.ONE) > 0) {
        throw new IllegalArgumentException("default_tax_rate_pct must be between 0 and 1");
      }
      org.setDefaultTaxRatePct(defaultRate);
    }
    if (legalName != null) org.setTaxLegalName(legalName);
    if (ein != null) {
      // EIN is PII-adjacent. Stored raw here for the report config; full encryption
      // lives in EinCipher when M2-24's signup wizard writes it. The signup-wizard
      // column (ein_encrypted) is the canonical store; this field is the
      // tax-report-side mirror for the report header and is not used for billing.
      org.setTaxIdEin(ein);
    }
    return organizations.save(org);
  }

}
