package com.fairtix.branding.application;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fairtix.audit.application.AuditService;
import com.fairtix.branding.domain.AgeRestriction;
import com.fairtix.branding.domain.EventSlugHistory;
import com.fairtix.branding.dto.EventPageDtos.EventPageResponse;
import com.fairtix.branding.dto.EventPageDtos.UpdateEventPageRequest;
import com.fairtix.branding.infrastructure.EventSlugHistoryRepository;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;

/**
 * Manages the per-event marketing-page fields (M2-20). Lives outside
 * {@link com.fairtix.events.application.EventService} so changes to a heavy
 * shared service don't reverberate through unrelated tests, and so the
 * sanitizer + slug-history concerns stay co-located with branding.
 */
@Service
public class EventPageService {

  private static final int MAX_DESCRIPTION_BYTES = 16_384;
  private static final int MAX_ACCESSIBILITY_TAGS = 12;

  private final EventRepository events;
  private final EventSlugHistoryRepository slugHistory;
  private final AuditService auditService;

  public EventPageService(EventRepository events,
                          EventSlugHistoryRepository slugHistory,
                          AuditService auditService) {
    this.events = events;
    this.slugHistory = slugHistory;
    this.auditService = auditService;
  }

  public EventPageResponse getForOrganizer(UUID eventId) {
    Event event = events.findById(eventId)
        .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));
    return toResponse(event);
  }

  @Transactional
  public EventPageResponse update(UUID orgId, UUID eventId,
                                  UpdateEventPageRequest req, UUID actorUserId) {
    Event event = events.findById(eventId)
        .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));
    if (event.getOrganizationId() == null || !event.getOrganizationId().equals(orgId)) {
      throw new ResourceNotFoundException("Event not found in this organization");
    }

    if (req.slug() != null) {
      String normalized = Slugs.slugify(req.slug());
      Slugs.requireValid(normalized);
      if (!normalized.equals(event.getSlug())) {
        if (events.existsByOrganizationIdAndSlug(orgId, normalized)) {
          throw new IllegalArgumentException("Slug already in use within this organization");
        }
        if (event.getSlug() != null && !event.getSlug().isBlank()) {
          slugHistory.save(new EventSlugHistory(eventId, orgId, event.getSlug()));
        }
        event.setSlug(normalized);
      }
    }
    if (req.heroImageUrl() != null) {
      event.setHeroImageUrl(BrandingValidator.normalizeUrl(req.heroImageUrl()));
    }
    if (req.descriptionMarkdown() != null) {
      String md = req.descriptionMarkdown();
      if (md.length() > MAX_DESCRIPTION_BYTES) {
        throw new IllegalArgumentException(
            "descriptionMarkdown exceeds " + MAX_DESCRIPTION_BYTES + " chars");
      }
      event.setDescriptionMarkdown(md.isBlank() ? null : md);
    }
    if (req.doorsOpenTime() != null) {
      // doorsOpenTime must not be after the event start.
      if (event.getStartTime() != null && req.doorsOpenTime().isAfter(event.getStartTime())) {
        throw new IllegalArgumentException("doorsOpenTime cannot be after the event start");
      }
      event.setDoorsOpenTime(req.doorsOpenTime());
    }
    if (req.setTimes() != null) {
      event.setSetTimes(req.setTimes().isBlank() ? null : req.setTimes());
    }
    if (req.ageRestriction() != null) {
      String value = req.ageRestriction().trim();
      if (value.isEmpty()) {
        event.setAgeRestriction(null);
      } else {
        // Validate against enum so callers get a clear error.
        AgeRestriction.valueOf(value);
        event.setAgeRestriction(value);
      }
    }
    if (req.accessibilityInfo() != null) {
      event.setAccessibilityInfo(req.accessibilityInfo().isBlank() ? null : req.accessibilityInfo());
    }
    if (req.accessibilityTags() != null) {
      if (req.accessibilityTags().size() > MAX_ACCESSIBILITY_TAGS) {
        throw new IllegalArgumentException("Too many accessibility tags");
      }
      // Persist as comma-separated; cheaper than a side table for free-form tags
      // that don't drive a query path.
      String joined = req.accessibilityTags().stream()
          .map(String::trim).filter(s -> !s.isEmpty())
          .map(s -> s.replaceAll("[,\\n\\r]", "-"))
          .collect(Collectors.joining(","));
      event.setAccessibilityTags(joined.isEmpty() ? null : joined);
    }
    if (req.parkingInfo() != null) {
      event.setParkingInfo(req.parkingInfo().isBlank() ? null : req.parkingInfo());
    }
    if (req.transitInfo() != null) {
      event.setTransitInfo(req.transitInfo().isBlank() ? null : req.transitInfo());
    }
    if (req.seoDescription() != null) {
      String desc = req.seoDescription().trim();
      if (desc.length() > 320) {
        throw new IllegalArgumentException("seoDescription must be 320 chars or fewer");
      }
      event.setSeoDescription(desc.isEmpty() ? null : desc);
    }

    auditService.log(actorUserId, "EVENT_PAGE_UPDATED", "EVENT", eventId,
        "Event page updated for event " + eventId);
    return toResponse(event);
  }

  public EventPageResponse toResponse(Event event) {
    String html = MarkdownRenderer.renderToHtml(event.getDescriptionMarkdown());
    return EventPageResponse.from(event, html, parseTags(event.getAccessibilityTags()));
  }

  public static List<String> parseTags(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }
}
