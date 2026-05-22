package com.fairtix.organizations.dashboard.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fairtix.organizations.application.OrgScoped;
import com.fairtix.organizations.dashboard.application.OrganizerDashboardService;
import com.fairtix.organizations.dashboard.dto.OrganizerDashboardDtos.AttendeePage;
import com.fairtix.organizations.dashboard.dto.OrganizerDashboardDtos.DashboardOverview;
import com.fairtix.organizations.dashboard.dto.OrganizerDashboardDtos.OrganizerEventRow;
import com.fairtix.organizations.dashboard.dto.OrganizerDashboardDtos.OrganizerEventSummary;
import com.fairtix.organizations.dashboard.dto.OrganizerDashboardDtos.VelocityPoint;
import com.fairtix.organizations.domain.OrgPermission;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Organizer dashboard surface (M2-03..M2-05). All endpoints are org-scoped via
 * the {@code orgId} path variable; the {@link OrgScoped} interceptor enforces
 * the caller's role-based permissions.
 */
@Tag(name = "Organizer Dashboard")
@RestController
@RequestMapping("/api/organizations/{orgId}")
public class OrganizerDashboardController {

  private final OrganizerDashboardService service;

  public OrganizerDashboardController(OrganizerDashboardService service) {
    this.service = service;
  }

  @Operation(summary = "Dashboard overview widgets")
  @GetMapping("/dashboard/overview")
  @OrgScoped(OrgPermission.SALES_READ)
  public DashboardOverview overview(@PathVariable UUID orgId) {
    return service.overview(orgId);
  }

  @Operation(summary = "List events for the organization with sold/capacity/gross")
  @GetMapping("/events")
  @OrgScoped(OrgPermission.EVENTS_READ)
  public List<OrganizerEventRow> events(@PathVariable UUID orgId) {
    return service.listEventsForOrg(orgId);
  }

  @Operation(summary = "Per-event summary: inventory, revenue, fees, payout estimate")
  @GetMapping("/events/{eventId}/summary")
  @OrgScoped(OrgPermission.EVENTS_READ)
  public OrganizerEventSummary eventSummary(@PathVariable UUID orgId, @PathVariable UUID eventId) {
    return service.eventSummary(orgId, eventId);
  }

  @Operation(summary = "Sales velocity time series for an event")
  @GetMapping("/events/{eventId}/velocity")
  @OrgScoped(OrgPermission.SALES_READ)
  public List<VelocityPoint> velocity(@PathVariable UUID orgId,
                                      @PathVariable UUID eventId,
                                      @RequestParam(defaultValue = "14") int days) {
    return service.velocity(orgId, eventId, days);
  }

  @Operation(summary = "Paginated attendee list for an event")
  @GetMapping("/events/{eventId}/attendees")
  @OrgScoped(OrgPermission.ATTENDEES_READ)
  public AttendeePage attendees(@PathVariable UUID orgId,
                                @PathVariable UUID eventId,
                                @RequestParam(required = false) String q,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "50") int size) {
    return service.attendees(orgId, eventId, q, page, size);
  }

  @Operation(summary = "CSV export of attendees for an event")
  @GetMapping(value = "/events/{eventId}/attendees.csv", produces = "text/csv")
  @OrgScoped(OrgPermission.ATTENDEES_READ)
  public ResponseEntity<String> attendeesCsv(@PathVariable UUID orgId,
                                             @PathVariable UUID eventId) {
    String body = service.attendeesCsv(orgId, eventId);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"attendees-" + eventId + ".csv\"")
        .contentType(MediaType.parseMediaType("text/csv"))
        .body(body);
  }
}
