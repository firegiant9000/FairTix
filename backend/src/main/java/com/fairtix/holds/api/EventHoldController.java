package com.fairtix.holds.api;

import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.holds.application.EventHoldService;
import com.fairtix.holds.domain.HoldCategory;
import com.fairtix.holds.dto.CompTicketResponse;
import com.fairtix.holds.dto.CreateEventHoldRequest;
import com.fairtix.holds.dto.EventHoldResponse;
import com.fairtix.organizations.application.OrgScoped;
import com.fairtix.organizations.domain.OrgPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Event Holds", description = "Promoter-side artist/press/house hold lists")
@RestController
@RequestMapping("/api/organizer/event-holds")
@PreAuthorize("isAuthenticated()")
public class EventHoldController {

  private final EventHoldService service;

  public EventHoldController(EventHoldService service) {
    this.service = service;
  }

  @Operation(summary = "Create one or more event holds (artist/press/house)")
  @OrgScoped(OrgPermission.HOLDS_MANAGE)
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public List<EventHoldResponse> create(
      @AuthenticationPrincipal CustomUserPrincipal principal,
      @Valid @RequestBody CreateEventHoldRequest request) {
    return service.create(request, principal.getUserId()).stream()
        .map(EventHoldResponse::from).toList();
  }

  @Operation(summary = "List active holds for an event, optionally filtered by category")
  @OrgScoped(OrgPermission.HOLDS_MANAGE)
  @GetMapping
  public List<EventHoldResponse> list(@RequestParam UUID eventId,
                                      @RequestParam(required = false) HoldCategory category) {
    return service.listActive(eventId, category).stream()
        .map(EventHoldResponse::from).toList();
  }

  @Operation(summary = "Release a single hold back to inventory")
  @OrgScoped(OrgPermission.HOLDS_MANAGE)
  @DeleteMapping("/{holdId}")
  public EventHoldResponse release(@AuthenticationPrincipal CustomUserPrincipal principal,
                                   @PathVariable UUID holdId) {
    return EventHoldResponse.from(service.release(holdId, principal.getUserId()));
  }

  @Operation(summary = "Bulk-release all active holds in a category for an event")
  @OrgScoped(OrgPermission.HOLDS_MANAGE)
  @PostMapping("/bulk-release")
  public BulkReleaseResponse bulkRelease(
      @AuthenticationPrincipal CustomUserPrincipal principal,
      @RequestParam UUID eventId,
      @RequestParam HoldCategory category) {
    int released = service.releaseAllInCategory(eventId, category, principal.getUserId());
    return new BulkReleaseResponse(eventId, category, released);
  }

  @Operation(summary = "Convert a hold into a comp ticket for a named recipient")
  @OrgScoped(OrgPermission.HOLDS_MANAGE)
  @PostMapping("/{holdId}/convert")
  public CompTicketResponse convert(
      @AuthenticationPrincipal CustomUserPrincipal principal,
      @PathVariable UUID holdId,
      @Valid @RequestBody ConvertHoldRequest body) {
    return CompTicketResponse.from(service.convertToComp(
        holdId, body.recipientName(), body.recipientEmail(),
        body.reason(), body.willCall(), principal.getUserId()));
  }

  public record BulkReleaseResponse(UUID eventId, HoldCategory category, int releasedCount) {}

  public record ConvertHoldRequest(
      @Size(max = 255) String recipientName,
      @Email @Size(max = 255) String recipientEmail,
      @Size(max = 1000) String reason,
      boolean willCall) {}
}
