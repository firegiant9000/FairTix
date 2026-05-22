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
import com.fairtix.branding.application.BrandingService;
import com.fairtix.branding.dto.BrandingDtos.BrandingResponse;
import com.fairtix.branding.dto.BrandingDtos.UpdateBrandingRequest;
import com.fairtix.organizations.application.OrgScoped;
import com.fairtix.organizations.domain.OrgPermission;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Per-org branding (M2-19). Owners and admins may read; only SETTINGS_WRITE
 * holders may update. The unauthenticated public-facing branding view lives on
 * {@link com.fairtix.branding.api.PublicBrandingController} so the surface here
 * is purely organizer-side.
 */
@Tag(name = "Organization Branding")
@RestController
@RequestMapping("/api/organizations/{orgId}/branding")
public class BrandingController {

  private final BrandingService service;

  public BrandingController(BrandingService service) {
    this.service = service;
  }

  @Operation(summary = "Get the organization's branding settings")
  @GetMapping
  @OrgScoped(OrgPermission.EVENTS_READ)
  public BrandingResponse get(@PathVariable UUID orgId) {
    return BrandingResponse.from(service.get(orgId));
  }

  @Operation(summary = "Update the organization's branding settings")
  @PatchMapping
  @OrgScoped(OrgPermission.SETTINGS_WRITE)
  public BrandingResponse update(@PathVariable UUID orgId,
                                 @RequestBody UpdateBrandingRequest req,
                                 @AuthenticationPrincipal CustomUserPrincipal principal) {
    return BrandingResponse.from(service.update(orgId, req, principal.getUserId()));
  }
}
