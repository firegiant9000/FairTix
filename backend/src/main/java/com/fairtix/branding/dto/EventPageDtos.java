package com.fairtix.branding.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fairtix.events.domain.Event;

/**
 * DTOs for per-event page customization (M2-20) and the public read views used
 * by the storefront, SEO scaffolding, and the embed widget.
 */
public final class EventPageDtos {
  private EventPageDtos() {}

  public record UpdateEventPageRequest(
      String slug,
      String heroImageUrl,
      String descriptionMarkdown,
      Instant doorsOpenTime,
      String setTimes,
      String ageRestriction,
      String accessibilityInfo,
      List<String> accessibilityTags,
      String parkingInfo,
      String transitInfo,
      String seoDescription) {}

  public record EventPageResponse(
      UUID id,
      String slug,
      String title,
      String heroImageUrl,
      String descriptionMarkdown,
      String descriptionHtml,
      Instant startTime,
      Instant doorsOpenTime,
      String setTimes,
      String ageRestriction,
      String accessibilityInfo,
      List<String> accessibilityTags,
      String parkingInfo,
      String transitInfo,
      String seoDescription) {

    public static EventPageResponse from(Event e, String renderedHtml, List<String> tags) {
      return new EventPageResponse(
          e.getId(),
          e.getSlug(),
          e.getTitle(),
          e.getHeroImageUrl(),
          e.getDescriptionMarkdown(),
          renderedHtml,
          e.getStartTime(),
          e.getDoorsOpenTime(),
          e.getSetTimes(),
          e.getAgeRestriction(),
          e.getAccessibilityInfo(),
          tags,
          e.getParkingInfo(),
          e.getTransitInfo(),
          e.getSeoDescription());
    }
  }

  /** Slim summary used by sitemap.xml and embed-widget listings. */
  public record PublicEventSummary(
      UUID id,
      String slug,
      String title,
      Instant startTime,
      String heroImageUrl,
      String venueName,
      String venueCity) {}
}
