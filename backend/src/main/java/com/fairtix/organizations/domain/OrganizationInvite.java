package com.fairtix.organizations.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organization_invites")
public class OrganizationInvite {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(nullable = false, length = 255)
  private String email;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private OrgRole role;

  @Column(nullable = false, unique = true, length = 64)
  private String token;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "accepted_at")
  private Instant acceptedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt = Instant.now();

  @Column(name = "created_by")
  private UUID createdBy;

  protected OrganizationInvite() {}

  public OrganizationInvite(UUID organizationId, String email, OrgRole role, String token,
                            Instant expiresAt, UUID createdBy) {
    this.organizationId = organizationId;
    this.email = email;
    this.role = role;
    this.token = token;
    this.expiresAt = expiresAt;
    this.createdBy = createdBy;
  }

  public void accept() {
    this.acceptedAt = Instant.now();
  }

  public boolean isExpired() {
    return Instant.now().isAfter(expiresAt);
  }

  public boolean isAccepted() {
    return acceptedAt != null;
  }

  public UUID getId() { return id; }
  public UUID getOrganizationId() { return organizationId; }
  public String getEmail() { return email; }
  public OrgRole getRole() { return role; }
  public String getToken() { return token; }
  public Instant getExpiresAt() { return expiresAt; }
  public Instant getAcceptedAt() { return acceptedAt; }
  public Instant getCreatedAt() { return createdAt; }
  public UUID getCreatedBy() { return createdBy; }
}
