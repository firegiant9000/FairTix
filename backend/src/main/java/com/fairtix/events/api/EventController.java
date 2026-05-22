package com.fairtix.events.api;

import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.fairtix.audit.application.AuditService;
import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.events.application.EventService;
import com.fairtix.organizations.application.PublicEndpoint;
import com.fairtix.events.domain.Event;
import com.fairtix.events.domain.EventStatus;
import com.fairtix.events.dto.CancelEventRequest;
import com.fairtix.events.dto.CreateEventRequest;
import com.fairtix.events.dto.UpdateEventRequest;
import com.fairtix.events.dto.EventResponse;
import com.fairtix.events.dto.NearbyEventResponse;
import com.fairtix.venues.application.GeoSearchService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;

import java.util.UUID;

/**
 * CRUD operations and lifecycle transitions for events.
 *
 * <p>Read endpoints are public; create, update, delete, and lifecycle transitions require the ADMIN role.
 * Update and delete also enforce organizer ownership.
 */
@Tag(name = "Events", description = "Event management")
@RestController
@RequestMapping("/api/events")
@PublicEndpoint("M2-01 deferred: mutation handlers still use @PreAuthorize(hasRole('ADMIN')); "
    + "follow-up PR migrates each to @OrgScoped with EVENTS_WRITE / EVENTS_PUBLISH / etc.")
public class EventController {

    private final EventService service;
    private final AuditService auditService;
    private final GeoSearchService geoSearchService;

    public EventController(EventService service, AuditService auditService, GeoSearchService geoSearchService) {
        this.service = service;
        this.auditService = auditService;
        this.geoSearchService = geoSearchService;
    }

    @Operation(summary = "Create an event", description = "Admin-only. Creates a new event owned by the caller.")
    @ApiResponse(responseCode = "201", description = "Event created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Not an admin")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse createEvent(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody CreateEventRequest request) {
        Event event = service.createEvent(
                request.title(),
                request.startTime(),
                request.venueId(),
                principal.getUserId(),
                request.queueRequired() != null && request.queueRequired(),
                request.queueCapacity(),
                request.maxTicketsPerUser());
        auditService.log(principal.getUserId(), "CREATE", "EVENT", event.getId(),
                "Created event: " + event.getTitle());
        return EventResponse.from(event);
    }

    @Operation(summary = "Search events",
            description = "Public. Returns a paginated list of events, optionally filtered. " +
                    "Non-admin callers only see PUBLISHED and ACTIVE events unless a status filter is provided " +
                    "(in which case ADMIN role is required).")
    @ApiResponse(responseCode = "200", description = "Page of matching events")
    @SecurityRequirements
    @PermitAll
    @GetMapping
    public Page<EventResponse> search(
            @Parameter(description = "Filter by venue name") @RequestParam(required = false) String venueName,
            @Parameter(description = "Filter by title (contains)") @RequestParam(required = false) String title,
            @Parameter(description = "Filter by performer name (contains)") @RequestParam(required = false) String performerName,
            @Parameter(description = "Only future events") @RequestParam(required = false) Boolean upcoming,
            @Parameter(description = "Filter by lifecycle status (ADMIN-only)") @RequestParam(required = false) EventStatus status,
            @Parameter(description = "Page number (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (max 100)") @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal CustomUserPrincipal principal) {

        boolean adminView = principal != null && principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        EventStatus effectiveStatus = adminView ? status : null;
        Page<Event> events = service.search(venueName, title, performerName, upcoming, effectiveStatus, adminView,
                PageRequest.of(page, Math.min(size, 100)));
        return events.map(EventResponse::from);
    }

    @Operation(summary = "Events near a location",
            description = "Public. Returns paginated events whose venue is within the given radius. " +
                    "Only PUBLISHED and ACTIVE events are returned. Max radius is 500 km.")
    @ApiResponse(responseCode = "200", description = "Page of nearby events")
    @ApiResponse(responseCode = "400", description = "Invalid coordinates or radius exceeds 500 km")
    @SecurityRequirements
    @PermitAll
    @GetMapping("/nearby")
    public Page<NearbyEventResponse> nearby(
            @Parameter(description = "Latitude of search origin") @RequestParam double lat,
            @Parameter(description = "Longitude of search origin") @RequestParam double lon,
            @Parameter(description = "Search radius in kilometres (default 50, max 500)") @RequestParam(defaultValue = "50") double radiusKm,
            @Parameter(description = "Page number (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (max 100)") @RequestParam(defaultValue = "20") int size) {
        if (Double.isNaN(lat) || Double.isInfinite(lat) || lat < -90 || lat > 90
                || Double.isNaN(lon) || Double.isInfinite(lon) || lon < -180 || lon > 180) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "lat must be in [-90,90] and lon in [-180,180]");
        }
        if (radiusKm <= 0 || radiusKm > 500) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "radiusKm must be between 1 and 500");
        }
        return geoSearchService.findEventsNear(lat, lon, radiusKm, PageRequest.of(page, Math.min(size, 100)));
    }

    @Operation(summary = "Get event by ID", description = "Public. Returns a single event.")
    @ApiResponse(responseCode = "200", description = "Event found")
    @ApiResponse(responseCode = "404", description = "Event not found")
    @SecurityRequirements
    @PermitAll
    @GetMapping("/{id}")
    public EventResponse getEvent(@PathVariable UUID id) {
        return EventResponse.from(service.getEvent(id));
    }

    @Operation(summary = "Update an event", description = "Admin-only. Only the organizer can update.")
    @ApiResponse(responseCode = "200", description = "Event updated")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Not the organizer")
    @ApiResponse(responseCode = "404", description = "Event not found")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public EventResponse update(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateEventRequest request) {
        Event updated = service.update(id, request, principal.getUserId());
        auditService.log(principal.getUserId(), "UPDATE", "EVENT", id,
                "Updated event: " + updated.getTitle());
        return EventResponse.from(updated);
    }

    @Operation(summary = "Delete an event", description = "Admin-only. Only the organizer can delete.")
    @ApiResponse(responseCode = "204", description = "Event deleted")
    @ApiResponse(responseCode = "403", description = "Not the organizer")
    @ApiResponse(responseCode = "404", description = "Event not found")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID id) {
        service.delete(id, principal.getUserId());
        auditService.log(principal.getUserId(), "DELETE", "EVENT", id, "Deleted event");
    }

    // --- Lifecycle transition endpoints ---

    @Operation(summary = "Publish an event", description = "Admin-only. Transitions event from DRAFT to PUBLISHED.")
    @ApiResponse(responseCode = "200", description = "Event published")
    @ApiResponse(responseCode = "409", description = "Invalid state transition")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/publish")
    public EventResponse publish(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID id) {
        Event event = service.publishEvent(id, principal.getUserId());
        auditService.log(principal.getUserId(), "PUBLISH", "EVENT", id, "Published event: " + event.getTitle());
        return EventResponse.from(event);
    }

    @Operation(summary = "Activate an event", description = "Admin-only. Transitions event from PUBLISHED to ACTIVE (on sale).")
    @ApiResponse(responseCode = "200", description = "Event activated")
    @ApiResponse(responseCode = "409", description = "Invalid state transition")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/activate")
    public EventResponse activate(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID id) {
        Event event = service.activateEvent(id, principal.getUserId());
        auditService.log(principal.getUserId(), "ACTIVATE", "EVENT", id, "Activated event: " + event.getTitle());
        return EventResponse.from(event);
    }

    @Operation(summary = "Complete an event", description = "Admin-only. Transitions event from ACTIVE to COMPLETED.")
    @ApiResponse(responseCode = "200", description = "Event completed")
    @ApiResponse(responseCode = "409", description = "Invalid state transition")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/complete")
    public EventResponse complete(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID id) {
        Event event = service.completeEvent(id, principal.getUserId());
        auditService.log(principal.getUserId(), "COMPLETE", "EVENT", id, "Completed event: " + event.getTitle());
        return EventResponse.from(event);
    }

    @Operation(summary = "Cancel an event", description = "Admin-only. Cancels the event and releases all holds and tickets.")
    @ApiResponse(responseCode = "200", description = "Event cancelled")
    @ApiResponse(responseCode = "409", description = "Invalid state transition")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/cancel")
    public EventResponse cancel(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody CancelEventRequest request) {
        Event event = service.cancelEvent(id, principal.getUserId(), request.reason());
        auditService.log(principal.getUserId(), "CANCEL", "EVENT", id,
                "Cancelled event: " + event.getTitle() + " — reason: " + request.reason());
        return EventResponse.from(event);
    }

    @Operation(summary = "Archive an event", description = "Admin-only. Transitions event from COMPLETED or CANCELLED to ARCHIVED.")
    @ApiResponse(responseCode = "200", description = "Event archived")
    @ApiResponse(responseCode = "409", description = "Invalid state transition")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/archive")
    public EventResponse archive(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID id) {
        Event event = service.archiveEvent(id, principal.getUserId());
        auditService.log(principal.getUserId(), "ARCHIVE", "EVENT", id, "Archived event: " + event.getTitle());
        return EventResponse.from(event);
    }
}
