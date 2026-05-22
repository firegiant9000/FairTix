package com.fairtix.organizations.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.organizations.application.OrgScoped;
import com.fairtix.organizations.application.OrganizationService;
import com.fairtix.organizations.domain.OrgPermission;
import com.fairtix.organizations.domain.OrganizationMember;
import com.fairtix.organizations.dto.OrganizationDtos.AcceptInviteRequest;
import com.fairtix.organizations.dto.OrganizationDtos.CreateOrganizationRequest;
import com.fairtix.organizations.dto.OrganizationDtos.InviteMemberRequest;
import com.fairtix.organizations.dto.OrganizationDtos.MemberResponse;
import com.fairtix.organizations.dto.OrganizationDtos.OrganizationResponse;
import com.fairtix.organizations.dto.OrganizationDtos.UpdateMemberRoleRequest;

@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {

  private final OrganizationService service;

  public OrganizationController(OrganizationService service) {
    this.service = service;
  }

  @PostMapping
  @PreAuthorize("isAuthenticated()")
  public OrganizationResponse create(@RequestBody CreateOrganizationRequest req,
                                     @AuthenticationPrincipal CustomUserPrincipal principal) {
    return OrganizationResponse.from(
        service.createOrganization(req.name(), req.contactEmail(), principal.getUserId()));
  }

  @GetMapping("/mine")
  @PreAuthorize("isAuthenticated()")
  public List<OrganizationResponse> mine(@AuthenticationPrincipal CustomUserPrincipal principal) {
    return service.listMembershipsForUser(principal.getUserId()).stream()
        .map(m -> OrganizationResponse.from(service.get(m.getOrganizationId())))
        .toList();
  }

  @GetMapping("/{orgId}")
  @OrgScoped(OrgPermission.EVENTS_READ)
  public OrganizationResponse get(@PathVariable UUID orgId) {
    return OrganizationResponse.from(service.get(orgId));
  }

  @GetMapping("/{orgId}/members")
  @OrgScoped(OrgPermission.TEAM_READ)
  public List<MemberResponse> members(@PathVariable UUID orgId) {
    return service.listMembers(orgId).stream().map(MemberResponse::from).toList();
  }

  @PostMapping("/{orgId}/invites")
  @OrgScoped(OrgPermission.TEAM_WRITE)
  public ResponseEntity<String> invite(@PathVariable UUID orgId,
                                       @RequestBody InviteMemberRequest req,
                                       @AuthenticationPrincipal CustomUserPrincipal principal) {
    var invite = service.invite(orgId, req.email(), req.role(), principal.getUserId());
    return ResponseEntity.ok(invite.getToken());
  }

  @PostMapping("/invites/accept")
  @PreAuthorize("isAuthenticated()")
  public MemberResponse accept(@RequestBody AcceptInviteRequest req,
                               @AuthenticationPrincipal CustomUserPrincipal principal) {
    OrganizationMember member = service.acceptInvite(req.token(), principal.getUserId());
    return MemberResponse.from(member);
  }

  @PatchMapping("/{orgId}/members/{userId}")
  @OrgScoped(OrgPermission.TEAM_WRITE)
  public MemberResponse updateRole(@PathVariable UUID orgId, @PathVariable UUID userId,
                                   @RequestBody UpdateMemberRoleRequest req,
                                   @AuthenticationPrincipal CustomUserPrincipal principal) {
    return MemberResponse.from(
        service.updateMemberRole(orgId, userId, req.role(), principal.getUserId()));
  }

  @DeleteMapping("/{orgId}/members/{userId}")
  @OrgScoped(OrgPermission.TEAM_WRITE)
  public ResponseEntity<Void> remove(@PathVariable UUID orgId, @PathVariable UUID userId,
                                     @AuthenticationPrincipal CustomUserPrincipal principal) {
    service.removeMember(orgId, userId, principal.getUserId());
    return ResponseEntity.noContent().build();
  }
}
