package com.fairtix.branding.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.fairtix.branding.domain.OrgCustomDomain;

@Repository
public interface OrgCustomDomainRepository extends JpaRepository<OrgCustomDomain, UUID> {

  @Query("SELECT d FROM OrgCustomDomain d WHERE LOWER(d.hostname) = LOWER(:host)")
  Optional<OrgCustomDomain> findByHostname(@Param("host") String hostname);

  List<OrgCustomDomain> findAllByOrganizationId(UUID organizationId);

  boolean existsByHostnameIgnoreCase(String hostname);
}
