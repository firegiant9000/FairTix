package com.fairtix.branding.api;

import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.fairtix.branding.application.PublicBrandingService;
import com.fairtix.branding.dto.EventPageDtos.PublicEventSummary;
import com.fairtix.events.domain.Event;
import com.fairtix.organizations.application.PublicEndpoint;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;

/**
 * SEO scaffolding (M2-21):
 *
 * <ul>
 *   <li>{@code GET /sitemap.xml} — single sitemap of all public events. For
 *       multi-tenant scaling this should split into per-org sitemaps; deferred
 *       to M3 since current scale is &lt; 10k events.</li>
 *   <li>{@code GET /robots.txt} — minimal allow-all; disallow {@code /api/}
 *       and the organizer surface.</li>
 *   <li>{@code GET /api/public/organizations/{orgSlug}/events/{eventSlug}/jsonld}
 *       — schema.org Event JSON-LD payload. The storefront can either inline
 *       this server-side or fetch it client-side and inject a
 *       &lt;script type="application/ld+json"&gt; tag.</li>
 * </ul>
 */
@RestController
@PublicEndpoint("Public SEO surface; no auth.")
public class SeoController {

  private final OrganizationRepository organizations;
  private final PublicBrandingService publicBranding;
  private final String baseUrl;

  public SeoController(OrganizationRepository organizations,
                       PublicBrandingService publicBranding,
                       @Value("${fairtix.public-base-url:https://tickets.fairtix.io}") String baseUrl) {
    this.organizations = organizations;
    this.publicBranding = publicBranding;
    this.baseUrl = trimTrailingSlash(baseUrl);
  }

  @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
  public String robots() {
    return "User-agent: *\n"
        + "Allow: /\n"
        + "Disallow: /api/\n"
        + "Disallow: /organizer/\n"
        + "Disallow: /admin/\n"
        + "Sitemap: " + baseUrl + "/sitemap.xml\n";
  }

  @GetMapping(value = "/sitemap.xml", produces = "application/xml")
  public ResponseEntity<String> sitemap() {
    StringBuilder xml = new StringBuilder(8192);
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
    xml.append("  <url><loc>").append(baseUrl).append("/</loc></url>\n");
    for (Organization org : organizations.findAll()) {
      xml.append("  <url><loc>").append(baseUrl).append("/o/").append(xmlEscape(org.getSlug()))
          .append("</loc></url>\n");
      for (PublicEventSummary ev : publicBranding.listPublicEvents(org.getId())) {
        if (ev.slug() == null) continue;
        xml.append("  <url><loc>").append(baseUrl)
            .append("/o/").append(xmlEscape(org.getSlug()))
            .append("/e/").append(xmlEscape(ev.slug()))
            .append("</loc></url>\n");
      }
    }
    xml.append("</urlset>\n");
    return ResponseEntity.ok()
        .header("Cache-Control", "public, max-age=3600")
        .body(xml.toString());
  }

  /**
   * Open Graph + Twitter card metadata for an event page. The storefront SPA
   * fetches this on render and injects the values into &lt;head&gt; via
   * react-helmet so social-media scrapers see a proper preview card when the
   * event link is shared. Returning JSON (rather than a server-rendered HTML
   * shell) avoids dual-source-of-truth between SSR and SPA routes.
   */
  @GetMapping(value = "/api/public/organizations/{orgSlug}/events/{eventSlug}/og",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, Object> eventOgCard(@PathVariable String orgSlug,
                                         @PathVariable String eventSlug) {
    Organization org = publicBranding.requireOrgBySlug(orgSlug);
    Event ev = publicBranding.requireEventByOrgAndSlug(org.getId(), eventSlug);
    String url = baseUrl + "/o/" + org.getSlug() + "/e/" + ev.getSlug();
    String description = ev.getSeoDescription() != null && !ev.getSeoDescription().isBlank()
        ? ev.getSeoDescription()
        : (ev.getVenue() != null
            ? ev.getTitle() + " at " + nullToEmpty(ev.getVenue().getName())
            : ev.getTitle());

    Map<String, Object> doc = new java.util.LinkedHashMap<>();
    doc.put("title", ev.getTitle());
    doc.put("description", description);
    doc.put("url", url);
    doc.put("og:title", ev.getTitle());
    doc.put("og:description", description);
    doc.put("og:url", url);
    doc.put("og:type", "event");
    doc.put("og:site_name", org.getName());
    doc.put("og:image", ev.getHeroImageUrl() == null ? "" : ev.getHeroImageUrl());
    doc.put("twitter:card", ev.getHeroImageUrl() == null ? "summary" : "summary_large_image");
    doc.put("twitter:title", ev.getTitle());
    doc.put("twitter:description", description);
    doc.put("twitter:image", ev.getHeroImageUrl() == null ? "" : ev.getHeroImageUrl());
    return doc;
  }

  @GetMapping(value = "/api/public/organizations/{orgSlug}/events/{eventSlug}/jsonld",
      produces = "application/ld+json")
  public Map<String, Object> eventJsonLd(@PathVariable String orgSlug,
                                         @PathVariable String eventSlug) {
    Organization org = publicBranding.requireOrgBySlug(orgSlug);
    Event ev = publicBranding.requireEventByOrgAndSlug(org.getId(), eventSlug);
    String startTime = DateTimeFormatter.ISO_INSTANT.format(ev.getStartTime());
    String url = baseUrl + "/o/" + org.getSlug() + "/e/" + ev.getSlug();

    Map<String, Object> location = new java.util.LinkedHashMap<>();
    location.put("@type", "Place");
    if (ev.getVenue() != null) {
      location.put("name", nullToEmpty(ev.getVenue().getName()));
      location.put("address", nullToEmpty(ev.getVenue().getCity()));
    } else {
      location.put("name", "");
    }

    Map<String, Object> organizer = new java.util.LinkedHashMap<>();
    organizer.put("@type", "Organization");
    organizer.put("name", org.getName());
    organizer.put("url", baseUrl + "/o/" + org.getSlug());

    Map<String, Object> doc = new java.util.LinkedHashMap<>();
    doc.put("@context", "https://schema.org");
    doc.put("@type", "Event");
    doc.put("name", ev.getTitle());
    doc.put("startDate", startTime);
    doc.put("eventStatus", "https://schema.org/EventScheduled");
    doc.put("eventAttendanceMode", "https://schema.org/OfflineEventAttendanceMode");
    doc.put("url", url);
    doc.put("image", ev.getHeroImageUrl() == null ? "" : ev.getHeroImageUrl());
    doc.put("description", ev.getSeoDescription() == null ? "" : ev.getSeoDescription());
    doc.put("location", location);
    doc.put("organizer", organizer);
    return doc;
  }

  private static String xmlEscape(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;");
  }

  private static String nullToEmpty(String s) { return s == null ? "" : s; }

  private static String trimTrailingSlash(String s) {
    return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}
