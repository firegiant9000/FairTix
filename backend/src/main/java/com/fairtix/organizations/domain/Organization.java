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
@Table(name = "organizations")
public class Organization {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, length = 255)
  private String name;

  @Column(nullable = false, unique = true, length = 100)
  private String slug;

  @Column(name = "contact_email", length = 255)
  private String contactEmail;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private OrganizationStatus status = OrganizationStatus.PENDING;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt = Instant.now();

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private Plan plan = Plan.FREE;

  @Column(name = "ticket_credits_remaining")
  private Integer ticketCreditsRemaining;

  @Column(name = "ticket_credits_reset_at")
  private Instant ticketCreditsResetAt;

  @Column(name = "stripe_customer_id", length = 64)
  private String stripeCustomerId;

  @Column(name = "stripe_subscription_id", length = 64)
  private String stripeSubscriptionId;

  protected Organization() {}

  public Organization(String name, String slug, String contactEmail) {
    this.name = name;
    this.slug = slug;
    this.contactEmail = contactEmail;
  }

  public void rename(String name) {
    this.name = name;
    this.updatedAt = Instant.now();
  }

  public void setStatus(OrganizationStatus status) {
    this.status = status;
    this.updatedAt = Instant.now();
  }

  public void setContactEmail(String contactEmail) {
    this.contactEmail = contactEmail;
    this.updatedAt = Instant.now();
  }

  public void setPlan(Plan plan) {
    this.plan = plan;
    this.updatedAt = Instant.now();
  }

  public void setTicketCreditsRemaining(Integer ticketCreditsRemaining) {
    this.ticketCreditsRemaining = ticketCreditsRemaining;
  }

  public void setTicketCreditsResetAt(Instant ticketCreditsResetAt) {
    this.ticketCreditsResetAt = ticketCreditsResetAt;
  }

  public void setStripeCustomerId(String stripeCustomerId) {
    this.stripeCustomerId = stripeCustomerId;
  }

  public void setStripeSubscriptionId(String stripeSubscriptionId) {
    this.stripeSubscriptionId = stripeSubscriptionId;
  }

  public UUID getId() { return id; }
  public String getName() { return name; }
  public String getSlug() { return slug; }
  public String getContactEmail() { return contactEmail; }
  public OrganizationStatus getStatus() { return status; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public Plan getPlan() { return plan; }
  public Integer getTicketCreditsRemaining() { return ticketCreditsRemaining; }
  public Instant getTicketCreditsResetAt() { return ticketCreditsResetAt; }
  public String getStripeCustomerId() { return stripeCustomerId; }
  public String getStripeSubscriptionId() { return stripeSubscriptionId; }
}
