package com.fairtix.branding.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "org_custom_domains")
public class OrgCustomDomain {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(nullable = false, length = 255)
  private String hostname;

  @Column(name = "verification_token", nullable = false, length = 64)
  private String verificationToken;

  @Column(name = "verified_at")
  private Instant verifiedAt;

  @Column(nullable = false)
  private boolean active = false;

  @Column(name = "last_health_check_at")
  private Instant lastHealthCheckAt;

  @Column(name = "last_health_ok")
  private Boolean lastHealthOk;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt = Instant.now();

  protected OrgCustomDomain() {}

  public OrgCustomDomain(UUID organizationId, String hostname, String verificationToken) {
    this.organizationId = organizationId;
    this.hostname = hostname;
    this.verificationToken = verificationToken;
  }

  public UUID getId() { return id; }
  public UUID getOrganizationId() { return organizationId; }
  public String getHostname() { return hostname; }
  public String getVerificationToken() { return verificationToken; }
  public Instant getVerifiedAt() { return verifiedAt; }
  public boolean isActive() { return active; }
  public Instant getLastHealthCheckAt() { return lastHealthCheckAt; }
  public Boolean getLastHealthOk() { return lastHealthOk; }
  public Instant getCreatedAt() { return createdAt; }

  public void markVerified() {
    this.verifiedAt = Instant.now();
    this.active = true;
  }

  public void deactivate() {
    this.active = false;
  }

  public void recordHealthCheck(boolean ok) {
    this.lastHealthCheckAt = Instant.now();
    this.lastHealthOk = ok;
  }
}
