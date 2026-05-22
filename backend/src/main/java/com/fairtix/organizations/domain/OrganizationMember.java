package com.fairtix.organizations.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organization_members",
    uniqueConstraints = @UniqueConstraint(columnNames = {"organization_id", "user_id"}))
public class OrganizationMember {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private OrgRole role;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt = Instant.now();

  protected OrganizationMember() {}

  public OrganizationMember(UUID organizationId, UUID userId, OrgRole role) {
    this.organizationId = organizationId;
    this.userId = userId;
    this.role = role;
  }

  public void setRole(OrgRole role) {
    this.role = role;
  }

  public UUID getId() { return id; }
  public UUID getOrganizationId() { return organizationId; }
  public UUID getUserId() { return userId; }
  public OrgRole getRole() { return role; }
  public Instant getCreatedAt() { return createdAt; }
}
