package com.fairtix.organizations.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.fairtix.organizations.domain.OrganizationInvite;

@Repository
public interface OrganizationInviteRepository extends JpaRepository<OrganizationInvite, UUID> {
  Optional<OrganizationInvite> findByToken(String token);
  List<OrganizationInvite> findAllByOrganizationIdAndAcceptedAtIsNull(UUID organizationId);
}
