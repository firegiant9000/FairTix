package com.fairtix.organizations.infrastructure;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.fairtix.organizations.domain.OrgSalesLedgerEntry;

@Repository
public interface OrgSalesLedgerRepository extends JpaRepository<OrgSalesLedgerEntry, UUID> {

  @Query("""
      select coalesce(sum(e.amountCents), 0)
        from OrgSalesLedgerEntry e
       where e.organizationId = :orgId
         and e.createdAt >= :since
      """)
  long sumSince(@Param("orgId") UUID orgId, @Param("since") Instant since);

  /**
   * Inserts the entry unless one already exists for the same (channel, source_id), and returns
   * the number of rows inserted (0 or 1). ON CONFLICT rather than catching the constraint
   * violation: a failed INSERT aborts the whole Postgres transaction, and the box-office paths
   * record the sale inside theirs.
   */
  @Modifying
  @Query(value = """
      INSERT INTO org_sales_ledger (id, organization_id, amount_cents, channel, source_id, created_at)
      VALUES (:id, :orgId, :amountCents, :channel, :sourceId, :createdAt)
      ON CONFLICT (channel, source_id) DO NOTHING
      """, nativeQuery = true)
  int insertIfAbsent(@Param("id") UUID id, @Param("orgId") UUID orgId,
      @Param("amountCents") long amountCents, @Param("channel") String channel,
      @Param("sourceId") String sourceId, @Param("createdAt") Instant createdAt);
}
