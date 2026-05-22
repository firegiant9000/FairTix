package com.fairtix.organizations.scheduler;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.domain.Plan;
import com.fairtix.organizations.infrastructure.OrganizationRepository;

/**
 * Resets monthly ticket-credit allocations on the first of each month.
 * Wired today but no-op in effect: existing orgs default to FREE and enforcement
 * is off (see PlanEnforcementService). M5 billing flips both on together.
 */
@Component
public class PlanCreditResetScheduler {

  private static final Logger log = LoggerFactory.getLogger(PlanCreditResetScheduler.class);

  private final OrganizationRepository organizations;

  public PlanCreditResetScheduler(OrganizationRepository organizations) {
    this.organizations = organizations;
  }

  @Scheduled(cron = "${plan.credit.reset.cron:0 0 0 * * *}")
  @Transactional
  public void resetMonthlyCredits() {
    MDC.put("requestId", "sched-resetMonthlyCredits-" + UUID.randomUUID());
    try {
      LocalDate today = LocalDate.now(ZoneOffset.UTC);
      if (today.getDayOfMonth() != 1) return;

      List<Organization> all = organizations.findAll();
      int updated = 0;
      for (Organization org : all) {
        Plan plan = org.getPlan();
        if (plan == null || plan.isUnlimited()) continue;
        org.setTicketCreditsRemaining(plan.getMonthlyTicketCap());
        org.setTicketCreditsResetAt(Instant.now());
        updated++;
      }
      log.info("Monthly plan credit reset: {} orgs updated", updated);
    } finally {
      MDC.remove("requestId");
    }
  }
}
