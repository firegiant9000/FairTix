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

  @Column(name = "stripe_connect_account_id", length = 64)
  private String stripeConnectAccountId;

  @Column(name = "stripe_connect_country", length = 2)
  private String stripeConnectCountry;

  @Column(name = "stripe_charges_enabled", nullable = false)
  private boolean stripeChargesEnabled = false;

  @Column(name = "stripe_payouts_enabled", nullable = false)
  private boolean stripePayoutsEnabled = false;

  @Column(name = "stripe_details_submitted", nullable = false)
  private boolean stripeDetailsSubmitted = false;

  @Column(name = "stripe_disabled_reason", length = 255)
  private String stripeDisabledReason;

  @Column(name = "stripe_requirements_json", columnDefinition = "TEXT")
  private String stripeRequirementsJson;

  @Column(name = "stripe_payouts_frozen", nullable = false)
  private boolean stripePayoutsFrozen = false;

  @Column(name = "stripe_connected_at")
  private Instant stripeConnectedAt;

  // --- Tax helper (M2-18). Per-event override lives on event_settlements. ---

  @Column(name = "default_tax_rate_pct", precision = 5, scale = 4)
  private java.math.BigDecimal defaultTaxRatePct;

  @Column(name = "tax_state", length = 2)
  private String taxState;

  @Column(name = "tax_id_ein", length = 32)
  private String taxIdEin;

  @Column(name = "tax_legal_name", length = 255)
  private String taxLegalName;

  // --- Branding (M2-19) -----------------------------------------------------

  @Column(name = "logo_url", length = 1024)
  private String logoUrl;

  @Column(name = "primary_color", length = 7)
  private String primaryColor;

  @Column(name = "email_sender_name", length = 120)
  private String emailSenderName;

  @Column(name = "email_reply_to", length = 255)
  private String emailReplyTo;

  @Column(name = "dark_mode_enabled", nullable = false)
  private boolean darkModeEnabled = false;

  @Column(name = "statement_descriptor_suffix", length = 22)
  private String statementDescriptorSuffix;

  // --- Phase H: signup wizard ---
  @Column(name = "legal_name", length = 255)            private String legalName;
  @Column(name = "dba", length = 255)                   private String dba;
  @Column(name = "address_line1", length = 255)         private String addressLine1;
  @Column(name = "address_line2", length = 255)         private String addressLine2;
  @Column(name = "address_city", length = 120)          private String addressCity;
  @Column(name = "address_region", length = 120)        private String addressRegion;
  @Column(name = "address_postal_code", length = 32)    private String addressPostalCode;
  @Column(name = "address_country", length = 2)         private String addressCountry;
  @Column(name = "primary_contact_name", length = 255)  private String primaryContactName;
  @Column(name = "primary_contact_phone", length = 64)  private String primaryContactPhone;

  // EIN is PII. The application layer encrypts before write and decrypts on
  // read via EinCipher; never read this column directly outside that helper.
  @Column(name = "ein_encrypted", columnDefinition = "TEXT")
  private String einEncrypted;
  @Column(name = "referred_by", length = 255)           private String referredBy;

  // --- Phase H: approval queue ---
  @Column(name = "submitted_for_review_at")             private Instant submittedForReviewAt;
  @Column(name = "reviewed_at")                         private Instant reviewedAt;
  @Column(name = "reviewed_by_user_id")                 private UUID reviewedByUserId;
  @Column(name = "rejection_reason", columnDefinition = "TEXT")
  private String rejectionReason;

  // --- Phase H: sales caps ---
  // Null = "use tier default for org age"; non-null is an admin override
  // valid until planOverridesUntil. See OrgSalesCapService.
  @Column(name = "daily_sales_cap_cents")               private Long dailySalesCapCents;
  @Column(name = "plan_overrides_until")                private Instant planOverridesUntil;
  @Column(name = "successful_payout_cycles", nullable = false)
  private int successfulPayoutCycles = 0;
  @Column(name = "dispute_count", nullable = false)
  private int disputeCount = 0;

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

  public String getStripeConnectAccountId() { return stripeConnectAccountId; }
  public void setStripeConnectAccountId(String id) {
    this.stripeConnectAccountId = id;
    this.updatedAt = Instant.now();
  }

  public String getStripeConnectCountry() { return stripeConnectCountry; }
  public void setStripeConnectCountry(String country) { this.stripeConnectCountry = country; }

  public boolean isStripeChargesEnabled() { return stripeChargesEnabled; }
  public void setStripeChargesEnabled(boolean v) { this.stripeChargesEnabled = v; }

  public boolean isStripePayoutsEnabled() { return stripePayoutsEnabled; }
  public void setStripePayoutsEnabled(boolean v) { this.stripePayoutsEnabled = v; }

  public boolean isStripeDetailsSubmitted() { return stripeDetailsSubmitted; }
  public void setStripeDetailsSubmitted(boolean v) { this.stripeDetailsSubmitted = v; }

  public String getStripeDisabledReason() { return stripeDisabledReason; }
  public void setStripeDisabledReason(String reason) { this.stripeDisabledReason = reason; }

  public String getStripeRequirementsJson() { return stripeRequirementsJson; }
  public void setStripeRequirementsJson(String json) { this.stripeRequirementsJson = json; }

  public boolean isStripePayoutsFrozen() { return stripePayoutsFrozen; }
  public void setStripePayoutsFrozen(boolean v) {
    this.stripePayoutsFrozen = v;
    this.updatedAt = Instant.now();
  }

  public Instant getStripeConnectedAt() { return stripeConnectedAt; }
  public void setStripeConnectedAt(Instant t) { this.stripeConnectedAt = t; }

  // --- Tax accessors (M2-18) ------------------------------------------------

  public java.math.BigDecimal getDefaultTaxRatePct() { return defaultTaxRatePct; }
  public void setDefaultTaxRatePct(java.math.BigDecimal v) {
    this.defaultTaxRatePct = v;
    this.updatedAt = Instant.now();
  }

  public String getTaxState() { return taxState; }
  public void setTaxState(String v) {
    this.taxState = v;
    this.updatedAt = Instant.now();
  }

  public String getTaxIdEin() { return taxIdEin; }
  public void setTaxIdEin(String v) {
    this.taxIdEin = v;
    this.updatedAt = Instant.now();
  }

  public String getTaxLegalName() { return taxLegalName; }
  public void setTaxLegalName(String v) {
    this.taxLegalName = v;
    this.updatedAt = Instant.now();
  }

  // --- Branding accessors (M2-19) -------------------------------------------

  public String getLogoUrl() { return logoUrl; }
  public void setLogoUrl(String logoUrl) {
    this.logoUrl = logoUrl;
    this.updatedAt = Instant.now();
  }

  public String getPrimaryColor() { return primaryColor; }
  public void setPrimaryColor(String primaryColor) {
    this.primaryColor = primaryColor;
    this.updatedAt = Instant.now();
  }

  public String getEmailSenderName() { return emailSenderName; }
  public void setEmailSenderName(String emailSenderName) {
    this.emailSenderName = emailSenderName;
    this.updatedAt = Instant.now();
  }

  public String getEmailReplyTo() { return emailReplyTo; }
  public void setEmailReplyTo(String emailReplyTo) {
    this.emailReplyTo = emailReplyTo;
    this.updatedAt = Instant.now();
  }

  public boolean isDarkModeEnabled() { return darkModeEnabled; }
  public void setDarkModeEnabled(boolean darkModeEnabled) {
    this.darkModeEnabled = darkModeEnabled;
    this.updatedAt = Instant.now();
  }

  public String getStatementDescriptorSuffix() { return statementDescriptorSuffix; }
  public void setStatementDescriptorSuffix(String statementDescriptorSuffix) {
    this.statementDescriptorSuffix = statementDescriptorSuffix;
    this.updatedAt = Instant.now();
  }

  // --- Phase H accessors ---

  public String getLegalName() { return legalName; }
  public void setLegalName(String v) { this.legalName = v; this.updatedAt = Instant.now(); }

  public String getDba() { return dba; }
  public void setDba(String v) { this.dba = v; this.updatedAt = Instant.now(); }

  public String getAddressLine1() { return addressLine1; }
  public void setAddressLine1(String v) { this.addressLine1 = v; this.updatedAt = Instant.now(); }

  public String getAddressLine2() { return addressLine2; }
  public void setAddressLine2(String v) { this.addressLine2 = v; this.updatedAt = Instant.now(); }

  public String getAddressCity() { return addressCity; }
  public void setAddressCity(String v) { this.addressCity = v; this.updatedAt = Instant.now(); }

  public String getAddressRegion() { return addressRegion; }
  public void setAddressRegion(String v) { this.addressRegion = v; this.updatedAt = Instant.now(); }

  public String getAddressPostalCode() { return addressPostalCode; }
  public void setAddressPostalCode(String v) { this.addressPostalCode = v; this.updatedAt = Instant.now(); }

  public String getAddressCountry() { return addressCountry; }
  public void setAddressCountry(String v) { this.addressCountry = v; this.updatedAt = Instant.now(); }

  public String getPrimaryContactName() { return primaryContactName; }
  public void setPrimaryContactName(String v) { this.primaryContactName = v; this.updatedAt = Instant.now(); }

  public String getPrimaryContactPhone() { return primaryContactPhone; }
  public void setPrimaryContactPhone(String v) { this.primaryContactPhone = v; this.updatedAt = Instant.now(); }

  public String getEinEncrypted() { return einEncrypted; }
  public void setEinEncrypted(String v) { this.einEncrypted = v; this.updatedAt = Instant.now(); }

  public String getReferredBy() { return referredBy; }
  public void setReferredBy(String v) { this.referredBy = v; this.updatedAt = Instant.now(); }

  public Instant getSubmittedForReviewAt() { return submittedForReviewAt; }
  public void setSubmittedForReviewAt(Instant v) { this.submittedForReviewAt = v; this.updatedAt = Instant.now(); }

  public Instant getReviewedAt() { return reviewedAt; }
  public void setReviewedAt(Instant v) { this.reviewedAt = v; }

  public UUID getReviewedByUserId() { return reviewedByUserId; }
  public void setReviewedByUserId(UUID v) { this.reviewedByUserId = v; }

  public String getRejectionReason() { return rejectionReason; }
  public void setRejectionReason(String v) { this.rejectionReason = v; }

  public Long getDailySalesCapCents() { return dailySalesCapCents; }
  public void setDailySalesCapCents(Long v) { this.dailySalesCapCents = v; this.updatedAt = Instant.now(); }

  public Instant getPlanOverridesUntil() { return planOverridesUntil; }
  public void setPlanOverridesUntil(Instant v) { this.planOverridesUntil = v; }

  public int getSuccessfulPayoutCycles() { return successfulPayoutCycles; }
  public void setSuccessfulPayoutCycles(int v) { this.successfulPayoutCycles = v; this.updatedAt = Instant.now(); }
  public void incrementSuccessfulPayoutCycles() { this.successfulPayoutCycles += 1; this.updatedAt = Instant.now(); }

  public int getDisputeCount() { return disputeCount; }
  public void setDisputeCount(int v) { this.disputeCount = v; }
  public void incrementDisputeCount() { this.disputeCount += 1; this.updatedAt = Instant.now(); }
}
