package com.fairtix.organizations.application;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fairtix.organizations.domain.OrgSalesLedgerEntry;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.organizations.infrastructure.OrgSalesLedgerRepository;

/**
 * Rolling 24-hour sales cap enforcement for new orgs (M2-25).
 *
 * <p>Tiers, applied in order:
 * <ol>
 *   <li>Admin override (organization.dailySalesCapCents non-null and
 *       planOverridesUntil in the future): use the override verbatim.</li>
 *   <li>{@code disputeCount > 0}: hold at the $10k tier until reviewed.</li>
 *   <li>{@code successfulPayoutCycles >= 3}: unlimited.</li>
 *   <li>{@code successfulPayoutCycles >= 1}: $10k/day.</li>
 *   <li>Otherwise (new org, first 30 days or zero successful cycles): $1k/day.</li>
 * </ol>
 *
 * <p>The cap window is a rolling 24h read off the org_sales_ledger table.
 * Both online checkout and box-office sales feed the ledger so the cap is
 * honest across channels.
 */
@Service
public class OrgSalesCapService {

  private static final Logger log = LoggerFactory.getLogger(OrgSalesCapService.class);

  public static final long TIER_NEW_CENTS       = 100_000L;     // $1,000
  public static final long TIER_ESTABLISHED_CENTS = 1_000_000L; // $10,000
  public static final long UNLIMITED = -1L;

  public static final String CHANNEL_ONLINE     = "ONLINE";
  public static final String CHANNEL_BOX_OFFICE = "BOX_OFFICE";

  private final OrganizationRepository organizations;
  private final OrgSalesLedgerRepository ledger;

  public OrgSalesCapService(OrganizationRepository organizations,
                            OrgSalesLedgerRepository ledger) {
    this.organizations = organizations;
    this.ledger = ledger;
  }

  /**
   * Throws {@link SalesCapExceededException} if charging {@code amountCents}
   * to this org right now would cross the rolling-24h cap. No-op when the org
   * has unlimited cap or the org id is null (events without an org).
   */
  public void checkCanCharge(UUID organizationId, long amountCents) {
    if (organizationId == null) return;
    Organization org = organizations.findById(organizationId).orElse(null);
    if (org == null) return;

    long capCents = resolveCapCents(org);
    if (capCents == UNLIMITED) return;

    long used = ledger.sumSince(organizationId, Instant.now().minus(Duration.ofHours(24)));
    if (used + amountCents > capCents) {
      log.warn("Sales cap would be exceeded for org={} cap={} used={} requested={}",
          organizationId, capCents, used, amountCents);
      throw new SalesCapExceededException(capCents, used, amountCents);
    }
  }

  /** Records a successful sale against the rolling cap. */
  @Transactional
  public void recordSale(UUID organizationId, long amountCents, String channel, String sourceId) {
    if (organizationId == null || amountCents <= 0) return;
    ledger.save(new OrgSalesLedgerEntry(organizationId, amountCents, channel, sourceId));
  }

  /** Visible for tests and the dashboard widget. */
  public long resolveCapCents(Organization org) {
    if (org.getDailySalesCapCents() != null
        && org.getPlanOverridesUntil() != null
        && org.getPlanOverridesUntil().isAfter(Instant.now())) {
      return org.getDailySalesCapCents();
    }
    if (org.getDisputeCount() > 0) return TIER_ESTABLISHED_CENTS;
    int cycles = org.getSuccessfulPayoutCycles();
    if (cycles >= 3) return UNLIMITED;
    if (cycles >= 1) return TIER_ESTABLISHED_CENTS;
    return TIER_NEW_CENTS;
  }
}
