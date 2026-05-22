package com.fairtix.organizations.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Append-only ledger of successful sales per organization, fed by both the
 * online checkout path and the box-office path. The rolling-24h cap check in
 * {@link com.fairtix.organizations.application.OrgSalesCapService} reads only
 * this table; do not source cap math from {@code payment_records} or
 * {@code box_office_sales} or the two views drift.
 */
@Entity
@Table(name = "org_sales_ledger")
public class OrgSalesLedgerEntry {

  @Id
  @Column(nullable = false)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "amount_cents", nullable = false)
  private long amountCents;

  @Column(nullable = false, length = 16)
  private String channel;

  @Column(name = "source_id", length = 128)
  private String sourceId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt = Instant.now();

  protected OrgSalesLedgerEntry() {}

  public OrgSalesLedgerEntry(UUID organizationId, long amountCents, String channel, String sourceId) {
    this.id = UUID.randomUUID();
    this.organizationId = organizationId;
    this.amountCents = amountCents;
    this.channel = channel;
    this.sourceId = sourceId;
  }

  public UUID getId() { return id; }
  public UUID getOrganizationId() { return organizationId; }
  public long getAmountCents() { return amountCents; }
  public String getChannel() { return channel; }
  public String getSourceId() { return sourceId; }
  public Instant getCreatedAt() { return createdAt; }
}
