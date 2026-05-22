package com.fairtix.branding.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.fairtix.branding.domain.EventSlugHistory;

@Repository
public interface EventSlugHistoryRepository extends JpaRepository<EventSlugHistory, UUID> {
  Optional<EventSlugHistory> findByOrganizationIdAndOldSlug(UUID organizationId, String oldSlug);
}
