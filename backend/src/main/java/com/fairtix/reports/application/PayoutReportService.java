package com.fairtix.reports.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.payments.application.StripeConnectService;
import com.fairtix.reports.domain.StripePayoutRecord;
import com.fairtix.reports.dto.ReportDtos.PayoutEventRollup;
import com.fairtix.reports.dto.ReportDtos.PayoutRow;
import com.fairtix.reports.infrastructure.ReportQueryRepository;
import com.fairtix.reports.infrastructure.StripePayoutRecordRepository;
import com.stripe.model.Payout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * M2-17: Stripe payouts mapped to events. We mirror Stripe payouts into
 * {@code stripe_payouts} and join each payout's arrival window to the
 * payment_records → orders → tickets → events graph to surface which events
 * funded each payout. Webhook handlers call {@link #syncPayout} on
 * {@code payout.paid} / {@code payout.failed} so the dashboard does not have
 * to round-trip Stripe per page load.
 */
@Service
@Transactional(readOnly = true)
public class PayoutReportService {

  private static final Logger log = LoggerFactory.getLogger(PayoutReportService.class);

  /** Payout window heuristic: Stripe Connect Standard 2-day rolling — match it for the join. */
  private static final int PAYOUT_LOOKBACK_DAYS = 2;

  private final StripePayoutRecordRepository payouts;
  private final ReportQueryRepository queries;
  private final StripeConnectService connectService;
  private final OrganizationRepository organizations;
  private final AuditService audit;

  public PayoutReportService(StripePayoutRecordRepository payouts,
                             ReportQueryRepository queries,
                             StripeConnectService connectService,
                             OrganizationRepository organizations,
                             AuditService audit) {
    this.payouts = payouts;
    this.queries = queries;
    this.connectService = connectService;
    this.organizations = organizations;
    this.audit = audit;
  }

  /** 30-day rolling list of payouts with per-event drill-down. */
  public List<PayoutRow> recentPayouts(UUID orgId, int days) {
    int window = Math.max(1, Math.min(days, 365));
    Instant from = Instant.now().minus(window, ChronoUnit.DAYS);

    List<StripePayoutRecord> rows = payouts
        .findAllByOrganizationIdOrderByPaidAtDescCreatedAtDesc(orgId).stream()
        .filter(p -> p.getPaidAt() == null || !p.getPaidAt().isBefore(from))
        .toList();

    List<PayoutRow> out = new ArrayList<>(rows.size());
    for (StripePayoutRecord p : rows) {
      Instant windowEnd = p.getPaidAt() != null
          ? p.getPaidAt()
          : (p.getArrivalDate() != null
              ? p.getArrivalDate().atStartOfDay().toInstant(ZoneOffset.UTC)
              : Instant.now());
      Instant windowStart = windowEnd.minus(PAYOUT_LOOKBACK_DAYS, ChronoUnit.DAYS);

      List<PayoutEventRollup> events = new ArrayList<>();
      for (Object[] r : queries.eventsForPayoutWindow(orgId, windowStart, windowEnd)) {
        events.add(new PayoutEventRollup(
            (UUID) r[0],
            (String) r[1],
            ((java.sql.Timestamp) r[2]).toInstant(),
            ((Number) r[3]).longValue(),
            ReportMath.money((BigDecimal) r[4])));
      }
      out.add(new PayoutRow(
          p.getStripePayoutId(),
          ReportMath.money(p.getAmount()),
          p.getCurrency(),
          p.getStatus(),
          p.getArrivalDate(),
          p.getPaidAt(),
          p.getFailureCode(),
          p.getFailureMessage(),
          events));
    }
    return out;
  }

  /**
   * Webhook entry point: upsert a single payout from a Stripe event. Idempotent
   * by {@code stripe_payout_id}; subsequent updates only refresh mutable fields.
   */
  @Transactional
  public void syncPayout(UUID orgId, Payout payout) {
    Organization org = organizations.findById(orgId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));

    String stripeId = payout.getId();
    BigDecimal amount = BigDecimal.valueOf(payout.getAmount() == null ? 0L : payout.getAmount())
        .movePointLeft(2);
    String currency = payout.getCurrency() == null ? "usd" : payout.getCurrency().toLowerCase();
    String status = payout.getStatus() == null ? "unknown" : payout.getStatus();
    LocalDate arrival = payout.getArrivalDate() == null ? null
        : LocalDate.ofEpochDay(payout.getArrivalDate() / 86_400L);
    Instant paidAt = "paid".equals(status) ? Instant.now() : null;
    String failureCode = payout.getFailureCode();
    String failureMessage = payout.getFailureMessage();
    String raw = payout.toJson();

    payouts.findByStripePayoutId(stripeId).ifPresentOrElse(existing -> {
      existing.updateFrom(status, arrival, paidAt, failureCode, failureMessage, raw);
    }, () -> {
      payouts.save(new StripePayoutRecord(org.getId(), stripeId, amount, currency, status,
          arrival, paidAt, failureCode, failureMessage, raw));
    });

    if ("paid".equals(status)) {
      audit.log(null, "STRIPE_PAYOUT_PAID", "ORGANIZATION", orgId,
          "payout=" + stripeId + " amount=" + amount);
    } else if ("failed".equals(status)) {
      audit.log(null, "STRIPE_PAYOUT_FAILED", "ORGANIZATION", orgId,
          "payout=" + stripeId + " code=" + failureCode);
    }
  }

  /** Manual sync: pulls the most recent N payouts from Stripe and upserts each. */
  @Transactional
  public int syncRecentFromStripe(UUID orgId, int limit) {
    if (!connectService.isEnabled()) {
      log.debug("Stripe disabled; skipping payout sync for org {}", orgId);
      return 0;
    }
    List<Payout> stripePayouts = connectService.listPayouts(orgId, limit);
    for (Payout p : stripePayouts) {
      syncPayout(orgId, p);
    }
    return stripePayouts.size();
  }
}
