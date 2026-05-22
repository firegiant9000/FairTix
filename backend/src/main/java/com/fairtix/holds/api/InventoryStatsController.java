package com.fairtix.holds.api;

import com.fairtix.holds.application.InventoryStatsService;
import com.fairtix.holds.dto.InventoryStatsResponse;
import com.fairtix.organizations.application.OrgScoped;
import com.fairtix.organizations.domain.OrgPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Inventory Stats", description = "Single-source-of-truth event inventory split")
@RestController
@RequestMapping("/api/organizer/events/{eventId}/inventory")
@PreAuthorize("isAuthenticated()")
public class InventoryStatsController {

  private final InventoryStatsService service;

  public InventoryStatsController(InventoryStatsService service) {
    this.service = service;
  }

  @Operation(summary = "Get sold/comped/held/cart-held/available aggregate for an event")
  @OrgScoped(OrgPermission.EVENTS_READ)
  @GetMapping
  public InventoryStatsResponse stats(@PathVariable UUID eventId) {
    return service.statsFor(eventId);
  }
}
