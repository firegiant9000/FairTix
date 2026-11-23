package com.fairtix.organizations.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.fairtix.organizations.domain.Organization;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
  Optional<Organization> findBySlug(String slug);
  boolean existsBySlug(String slug);
  Optional<Organization> findByStripeConnectAccountId(String accountId);

  /**
   * Sets the payout-frozen flag by itself. A full-entity save() would write every column, so
   * a concurrent account.updated save of an older copy could put the flag back to false.
   */
  @Modifying
  @Query("UPDATE Organization o SET o.stripePayoutsFrozen = true, o.updatedAt = :now WHERE o.id = :id")
  int freezePayouts(@Param("id") UUID id, @Param("now") Instant now);
}
