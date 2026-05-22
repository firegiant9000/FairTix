package com.fairtix.branding.api;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fairtix.branding.application.EventPageService;
import com.fairtix.branding.application.PublicBrandingService;
import com.fairtix.branding.domain.EventSlugHistory;
import com.fairtix.branding.dto.BrandingDtos.BrandingResponse;
import com.fairtix.branding.dto.EventPageDtos.EventPageResponse;
import com.fairtix.branding.dto.EventPageDtos.PublicEventSummary;
import com.fairtix.branding.infrastructure.EventSlugHistoryRepository;
import com.fairtix.events.domain.Event;
import com.fairtix.organizations.application.PublicEndpoint;
import com.fairtix.organizations.domain.Organization;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Unauthenticated public endpoints powering the storefront, SEO scaffolding,
 * and the embed widget. All read-only.
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code GET /api/public/organizations/{orgSlug}/branding}</li>
 *   <li>{@code GET /api/public/organizations/{orgSlug}/events} — embed listing</li>
 *   <li>{@code GET /api/public/organizations/{orgSlug}/events/{eventSlug}}
 *       — 200 OK with the page, or 301 to the current slug if the URL is historical.</li>
 * </ul>
 */
@Tag(name = "Public storefront")
@RestController
@RequestMapping("/api/public/organizations/{orgSlug}")
@PublicEndpoint("Storefront read endpoints — no auth, read-only.")
public class PublicBrandingController {

  private final PublicBrandingService publicBranding;
  private final EventPageService eventPageService;
  private final EventSlugHistoryRepository slugHistory;

  public PublicBrandingController(PublicBrandingService publicBranding,
                                  EventPageService eventPageService,
                                  EventSlugHistoryRepository slugHistory) {
    this.publicBranding = publicBranding;
    this.eventPageService = eventPageService;
    this.slugHistory = slugHistory;
  }

  @GetMapping("/branding")
  public BrandingResponse branding(@PathVariable String orgSlug) {
    Organization org = publicBranding.requireOrgBySlug(orgSlug);
    return BrandingResponse.from(org);
  }

  @GetMapping("/events")
  public List<PublicEventSummary> events(@PathVariable String orgSlug) {
    Organization org = publicBranding.requireOrgBySlug(orgSlug);
    return publicBranding.listPublicEvents(org.getId());
  }

  @GetMapping("/events/{eventSlug}")
  public ResponseEntity<EventPageResponse> event(@PathVariable String orgSlug,
                                                 @PathVariable String eventSlug) {
    Organization org = publicBranding.requireOrgBySlug(orgSlug);
    Optional<Event> current = publicBranding.findEventByOrgAndSlug(org.getId(), eventSlug);
    if (current.isPresent()) {
      return ResponseEntity.ok(eventPageService.toResponse(current.get()));
    }
    // Historical slug: 301 to the current canonical URL.
    EventSlugHistory hist = slugHistory.findByOrganizationIdAndOldSlug(org.getId(), eventSlug)
        .orElseThrow(() -> new com.fairtix.common.ResourceNotFoundException("Event not found"));
    Event ev = publicBranding.requireEventById(hist.getEventId());
    String currentSlug = ev.getSlug() == null ? hist.getOldSlug() : ev.getSlug();
    return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
        .location(URI.create("/api/public/organizations/" + orgSlug + "/events/" + currentSlug))
        .build();
  }
}
