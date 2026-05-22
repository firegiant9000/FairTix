package com.fairtix.organizations.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.fairtix.organizations.domain.OrgRole;
import com.fairtix.organizations.domain.OrganizationMember;

@Repository
public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, UUID> {
  List<OrganizationMember> findAllByUserId(UUID userId);
  List<OrganizationMember> findAllByOrganizationId(UUID organizationId);
  Optional<OrganizationMember> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);
  long countByOrganizationIdAndRole(UUID organizationId, OrgRole role);
}
