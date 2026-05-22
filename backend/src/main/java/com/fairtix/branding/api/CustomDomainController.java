package com.fairtix.branding.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.branding.application.CustomDomainService;
import com.fairtix.branding.dto.CustomDomainDtos.AddCustomDomainRequest;
import com.fairtix.branding.dto.CustomDomainDtos.CustomDomainResponse;
import com.fairtix.organizations.application.OrgScoped;
import com.fairtix.organizations.domain.OrgPermission;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Custom Domains")
@RestController
@RequestMapping("/api/organizations/{orgId}/custom-domains")
public class CustomDomainController {

  private final CustomDomainService service;
  private final CustomDomainService.DnsTxtResolver resolver;

  public CustomDomainController(CustomDomainService service, CustomDomainService.DnsTxtResolver resolver) {
    this.service = service;
    this.resolver = resolver;
  }

  @Operation(summary = "List custom domains attached to the organization")
  @GetMapping
  @OrgScoped(OrgPermission.EVENTS_READ)
  public List<CustomDomainResponse> list(@PathVariable UUID orgId) {
    return service.list(orgId).stream().map(CustomDomainResponse::from).toList();
  }

  @Operation(summary = "Attach a new custom domain (Pro+ tier). Returns the TXT verification record.")
  @PostMapping
  @OrgScoped(OrgPermission.SETTINGS_WRITE)
  public CustomDomainResponse add(@PathVariable UUID orgId,
                                  @RequestBody AddCustomDomainRequest req,
                                  @AuthenticationPrincipal CustomUserPrincipal principal) {
    return CustomDomainResponse.from(service.add(orgId, req.hostname(), principal.getUserId()));
  }

  @Operation(summary = "Verify the TXT record now; activates the domain if it matches.")
  @PostMapping("/{domainId}/verify")
  @OrgScoped(OrgPermission.SETTINGS_WRITE)
  public CustomDomainResponse verify(@PathVariable UUID orgId,
                                     @PathVariable UUID domainId,
                                     @AuthenticationPrincipal CustomUserPrincipal principal) {
    return CustomDomainResponse.from(
        service.verify(orgId, domainId, resolver, principal.getUserId()));
  }

  @Operation(summary = "Remove a custom domain")
  @DeleteMapping("/{domainId}")
  @OrgScoped(OrgPermission.SETTINGS_WRITE)
  public ResponseEntity<Void> delete(@PathVariable UUID orgId,
                                     @PathVariable UUID domainId,
                                     @AuthenticationPrincipal CustomUserPrincipal principal) {
    service.delete(orgId, domainId, principal.getUserId());
    return ResponseEntity.noContent().build();
  }
}
