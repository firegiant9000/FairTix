package com.fairtix.branding.api;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.branding.application.EventPageService;
import com.fairtix.branding.dto.EventPageDtos.EventPageResponse;
import com.fairtix.branding.dto.EventPageDtos.UpdateEventPageRequest;
import com.fairtix.organizations.application.OrgScoped;
import com.fairtix.organizations.domain.OrgPermission;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Organizer-facing event-page customization endpoints (M2-20). The unauthenticated
 * public view (with the rendered HTML) lives on PublicEventPageController.
 */
@Tag(name = "Event page")
@RestController
@RequestMapping("/api/organizations/{orgId}/events/{eventId}/page")
public class EventPageController {

  private final EventPageService service;

  public EventPageController(EventPageService service) {
    this.service = service;
  }

  @Operation(summary = "Read the customizable page fields for an event")
  @GetMapping
  @OrgScoped(OrgPermission.EVENTS_READ)
  public EventPageResponse get(@PathVariable UUID orgId, @PathVariable UUID eventId) {
    return service.getForOrganizer(eventId);
  }

  @Operation(summary = "Update the customizable page fields for an event")
  @PatchMapping
  @OrgScoped(OrgPermission.EVENTS_WRITE)
  public EventPageResponse update(@PathVariable UUID orgId,
                                  @PathVariable UUID eventId,
                                  @RequestBody UpdateEventPageRequest req,
                                  @AuthenticationPrincipal CustomUserPrincipal principal) {
    return service.update(orgId, eventId, req, principal.getUserId());
  }
}
