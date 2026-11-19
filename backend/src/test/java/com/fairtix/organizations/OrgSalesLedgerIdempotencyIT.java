package com.fairtix.organizations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fairtix.organizations.application.OrgSalesCapService;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrgSalesLedgerRepository;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.support.PostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #291: {@code recordSale} counts a sale once per (channel, source_id), against the real
 * V50 constraint. A repeat must not abort the caller's transaction either: the box-office
 * paths record the sale inside theirs.
 */
class OrgSalesLedgerIdempotencyIT extends PostgresIT {

  @Autowired private OrgSalesCapService salesCapService;
  @Autowired private OrgSalesLedgerRepository ledger;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  private UUID orgId;

  @BeforeEach
  void setUp() {
    orgId = organizationRepository.save(
        new Organization("Ledger Org", "ledger-" + UUID.randomUUID(), "ledger@test")).getId();
  }

  @Test
  void sameSaleTwice_isCountedOnce() {
    assertThat(salesCapService.recordSale(orgId, 5_000L, OrgSalesCapService.CHANNEL_ONLINE, "pi_twice"))
        .isTrue();
    assertThat(salesCapService.recordSale(orgId, 5_000L, OrgSalesCapService.CHANNEL_ONLINE, "pi_twice"))
        .isFalse();

    assertThat(rows("pi_twice")).isEqualTo(1);
    assertThat(ledger.sumSince(orgId, Instant.now().minusSeconds(3600))).isEqualTo(5_000L);
  }

  @Test
  void repeatInsideAnOuterTransaction_doesNotAbortIt() {
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      salesCapService.recordSale(orgId, 5_000L, OrgSalesCapService.CHANNEL_BOX_OFFICE, "sale-1");
      salesCapService.recordSale(orgId, 5_000L, OrgSalesCapService.CHANNEL_BOX_OFFICE, "sale-1");
      // A failed INSERT would leave the Postgres transaction aborted, so this read would fail.
      assertThat(ledger.sumSince(orgId, Instant.now().minusSeconds(3600))).isEqualTo(5_000L);
    });

    assertThat(rows("sale-1")).isEqualTo(1);
  }

  @Test
  void sameSourceOnAnotherChannel_isASeparateSale() {
    salesCapService.recordSale(orgId, 5_000L, OrgSalesCapService.CHANNEL_ONLINE, "shared-id");
    salesCapService.recordSale(orgId, 2_000L, OrgSalesCapService.CHANNEL_BOX_OFFICE, "shared-id");

    assertThat(rows("shared-id")).isEqualTo(2);
  }

  @Test
  void entriesWithoutSourceId_areNotDeduplicated() {
    salesCapService.recordSale(orgId, 5_000L, OrgSalesCapService.CHANNEL_BOX_OFFICE, null);
    salesCapService.recordSale(orgId, 5_000L, OrgSalesCapService.CHANNEL_BOX_OFFICE, null);

    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM org_sales_ledger WHERE source_id IS NULL", Integer.class))
        .isEqualTo(2);
  }

  private int rows(String sourceId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM org_sales_ledger WHERE source_id = ?", Integer.class, sourceId);
  }
}
