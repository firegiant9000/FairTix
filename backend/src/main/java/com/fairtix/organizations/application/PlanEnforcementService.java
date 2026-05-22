package com.fairtix.organizations.application;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.domain.Plan;
import com.fairtix.organizations.infrastructure.OrganizationRepository;

/**
 * Stub for plan-tier enforcement. M5 billing will turn this into a real cap check.
 * Today every call returns true so existing free-tier orgs keep working; the wiring
 * exists so the call site is already in place when enforcement lands.
 */
@Service
public class PlanEnforcementService {

  private static final Logger log = LoggerFactory.getLogger(PlanEnforcementService.class);

  private final OrganizationRepository organizations;

  public PlanEnforcementService(OrganizationRepository organizations) {
    this.organizations = organizations;
  }

  public boolean checkCanIssueTicket(UUID organizationId) {
    if (organizationId == null) return true;
    Organization org = organizations.findById(organizationId).orElse(null);
    if (org == null) return true;

    Plan plan = org.getPlan();
    Integer remaining = org.getTicketCreditsRemaining();
    if (plan != null && !plan.isUnlimited() && remaining != null && remaining <= 0) {
      log.info("Plan cap would block ticket issuance for org={} plan={} (not enforced yet)",
          organizationId, plan);
    }
    return true;
  }
}
