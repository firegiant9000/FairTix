package com.fairtix.reports.scheduler;

import com.fairtix.audit.application.AuditService;
import com.fairtix.audit.infrastructure.AuditLogRepository;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.reports.application.TaxReportService;
import com.fairtix.reports.dto.ReportDtos.TaxThreshold;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * M2-18: daily scan that audit-logs the first time each year an organization
 * crosses the federal 1099-K reporting threshold's 80% line — and again at
 * 100%. The audit row is the durable record; an ops dashboard or a Slack
 * webhook (M1-deferred) reads it for paging. We do not directly email
 * organizers from this scheduler because we have no organizer-side
 * NotificationCategory yet; that lands when the ops-alert webhook lands.
 *
 * <p>Dedupe is by audit row: each (action, orgId, calendar-year) tuple is
 * emitted at most once. The scheduler runs daily at 02:15 UTC by default
 * (interleaved with the fraud risk-score decay job at 02:00).
 */
@Component
public class TaxThresholdAlertScheduler {

  private static final Logger log = LoggerFactory.getLogger(TaxThresholdAlertScheduler.class);

  /** Synthetic system actor for audit attribution. Matches the value used by
   * other automated schedulers (e.g. HoldReleaseScheduler). */
  private static final UUID SYSTEM_USER = UUID.fromString("00000000-0000-0000-0000-000000000000");

  private static final String ACTION_80 = "TAX_THRESHOLD_80_ALERTED";
  private static final String ACTION_100 = "TAX_THRESHOLD_100_ALERTED";

  private final OrganizationRepository organizations;
  private final TaxReportService taxService;
  private final AuditService auditService;
  private final AuditLogRepository auditRepo;

  public TaxThresholdAlertScheduler(OrganizationRepository organizations,
                                    TaxReportService taxService,
                                    AuditService auditService,
                                    AuditLogRepository auditRepo) {
    this.organizations = organizations;
    this.taxService = taxService;
    this.auditService = auditService;
    this.auditRepo = auditRepo;
  }

  @Scheduled(cron = "${reports.tax.threshold-alert-cron:0 15 2 * * *}")
  public void run() {
    int year = LocalDate.now(ZoneOffset.UTC).getYear();
    Instant yearStart = LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
    int evaluated = 0;
    int alerted80 = 0;
    int alerted100 = 0;

    for (Organization org : organizations.findAll()) {
      // Only evaluate orgs that have opted into tax tracking — saves N queries
      // per night on orgs that don't collect tax through the platform.
      if (org.getTaxState() == null || org.getTaxState().isBlank()) continue;

      try {
        TaxThreshold t = taxService.threshold(org.getId(), year);
        evaluated++;
        if (!t.alert()) continue;

        // 100% crossing fires its own alert (separate dedupe bucket).
        boolean past100 = t.pctOfThreshold() != null
            && t.pctOfThreshold().compareTo(java.math.BigDecimal.ONE) >= 0;

        if (past100 && !alreadyAlertedThisYear(ACTION_100, org.getId(), yearStart)) {
          auditService.log(SYSTEM_USER, ACTION_100, "ORGANIZATION", org.getId(),
              "ytdGross=" + t.ytdGross() + " threshold=" + t.threshold());
          log.warn("1099-K threshold 100% crossed orgId={} ytdGross={}",
              org.getId(), t.ytdGross());
          alerted100++;
        } else if (!past100 && !alreadyAlertedThisYear(ACTION_80, org.getId(), yearStart)) {
          auditService.log(SYSTEM_USER, ACTION_80, "ORGANIZATION", org.getId(),
              "ytdGross=" + t.ytdGross() + " threshold=" + t.threshold());
          log.warn("1099-K threshold 80% crossed orgId={} ytdGross={}",
              org.getId(), t.ytdGross());
          alerted80++;
        }
      } catch (RuntimeException e) {
        log.warn("Threshold check failed for org {}: {}", org.getId(), e.getMessage());
      }
    }

    if (alerted80 > 0 || alerted100 > 0) {
      log.info("Tax threshold sweep: evaluated={} new80={} new100={}",
          evaluated, alerted80, alerted100);
    }
  }

  private boolean alreadyAlertedThisYear(String action, UUID orgId, Instant yearStart) {
    return auditRepo.countByActionAndResourceIdAndCreatedAtAfter(action, orgId, yearStart) > 0;
  }
}
