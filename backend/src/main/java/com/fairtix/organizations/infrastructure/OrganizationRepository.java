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
   * Sets the payout-frozen flag by itself. With {@code @DynamicUpdate} on Organization, other
   * writers' saves leave this column alone unless they changed it, so a concurrent save of an
   * older copy can't put the flag back to false.
   */
  @Modifying
  @Query("UPDATE Organization o SET o.stripePayoutsFrozen = true, o.updatedAt = :now WHERE o.id = :id")
  int freezePayouts(@Param("id") UUID id, @Param("now") Instant now);

  /**
   * Clears the payout-frozen flag by itself, only if it is set, so the caller can tell from the
   * row count whether anything changed (an unfrozen org gives 0 and needs no audit). Clears the
   * persistence context so a later read in the same transaction sees the new value.
   */
  @Modifying(clearAutomatically = true)
  @Query("UPDATE Organization o SET o.stripePayoutsFrozen = false, o.updatedAt = :now "
      + "WHERE o.id = :id AND o.stripePayoutsFrozen = true")
  int clearPayoutsFreeze(@Param("id") UUID id, @Param("now") Instant now);

  /**
   * Adds one to {@code dispute_count} in SQL, so concurrent disputes can't lose an increment
   * and a save of an older copy can't write the count back (see {@link #freezePayouts}).
   */
  @Modifying
  @Query("UPDATE Organization o SET o.disputeCount = o.disputeCount + 1, o.updatedAt = :now WHERE o.id = :id")
  int incrementDisputeCount(@Param("id") UUID id, @Param("now") Instant now);
}
