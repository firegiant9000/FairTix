package com.fairtix.branding.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fairtix.branding.dto.EventPageDtos.PublicEventSummary;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.events.domain.Event;
import com.fairtix.events.domain.EventStatus;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;

/**
 * Read-only helpers shared by every unauthenticated public endpoint in this
 * package: slug → org/event lookups, current-vs-historical slug detection, and
 * filtered listings for embed/sitemap consumers.
 */
@Service
@Transactional(readOnly = true)
public class PublicBrandingService {

  private final OrganizationRepository organizations;
  private final EventRepository events;

  public PublicBrandingService(OrganizationRepository organizations, EventRepository events) {
    this.organizations = organizations;
    this.events = events;
  }

  public Organization requireOrgBySlug(String orgSlug) {
    return organizations.findBySlug(orgSlug)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
  }

  public Event requireEventByOrgAndSlug(UUID orgId, String slug) {
    return events.findByOrganizationIdAndSlug(orgId, slug)
        .orElseThrow(() -> new ResourceNotFoundException("Event not found"));
  }

  public Optional<Event> findEventByOrgAndSlug(UUID orgId, String slug) {
    if (slug == null) return Optional.empty();
    return events.findByOrganizationIdAndSlug(orgId, slug);
  }

  public Event requireEventById(UUID eventId) {
    return events.findById(eventId)
        .orElseThrow(() -> new ResourceNotFoundException("Event not found"));
  }

  /**
   * Public listings show only events that audiences can actually buy into:
   * PUBLISHED, ACTIVE, or upcoming COMPLETED rows are excluded so embeds don't
   * advertise something nobody can act on.
   */
  public List<PublicEventSummary> listPublicEvents(UUID orgId) {
    return events.findAllByOrganizationIdOrderByStartTimeDesc(orgId).stream()
        .filter(e -> e.getStatus() == EventStatus.PUBLISHED || e.getStatus() == EventStatus.ACTIVE)
        .map(PublicBrandingService::summarize)
        .toList();
  }

  private static PublicEventSummary summarize(Event e) {
    return new PublicEventSummary(
        e.getId(),
        e.getSlug(),
        e.getTitle(),
        e.getStartTime(),
        e.getHeroImageUrl(),
        e.getVenue() != null ? e.getVenue().getName() : null,
        e.getVenue() != null ? e.getVenue().getCity() : null);
  }
}
