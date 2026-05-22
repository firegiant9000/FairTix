package com.fairtix.events.infrastructure;

import com.fairtix.events.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID>,
    JpaSpecificationExecutor<Event> {

  Optional<Event> findByOrganizationIdAndSlug(UUID organizationId, String slug);

  boolean existsByOrganizationIdAndSlug(UUID organizationId, String slug);

  long countByStartTimeAfter(Instant now);

  boolean existsByVenue_Id(UUID venueId);

  @Query("SELECT e.venue.name, COUNT(e) FROM Event e GROUP BY e.venue.name")
  List<Object[]> countByVenueGrouped();

  List<Event> findAllByOrganizationIdOrderByStartTimeDesc(UUID organizationId);

  @Query("SELECT e FROM Event e WHERE e.organizationId = :orgId "
      + "AND e.startTime >= :from AND e.startTime < :to "
      + "ORDER BY e.startTime ASC")
  List<Event> findOrgEventsBetween(@org.springframework.data.repository.query.Param("orgId") UUID orgId,
                                   @org.springframework.data.repository.query.Param("from") Instant from,
                                   @org.springframework.data.repository.query.Param("to") Instant to);
}
