package com.fairtix.organizations.infrastructure;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
