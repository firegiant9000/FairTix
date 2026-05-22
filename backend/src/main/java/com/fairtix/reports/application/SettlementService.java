package com.fairtix.reports.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.domain.Plan;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.reports.domain.EventSettlement;
import com.fairtix.reports.domain.SplitType;
import com.fairtix.reports.dto.ReportDtos.BoxOfficeMethodRow;
import com.fairtix.reports.dto.ReportDtos.CompReasonRow;
import com.fairtix.reports.dto.ReportDtos.DosReport;
import com.fairtix.reports.dto.ReportDtos.SettlementConfigRequest;
import com.fairtix.reports.dto.ReportDtos.SettlementConfigResponse;
import com.fairtix.reports.dto.ReportDtos.SettlementReport;
import com.fairtix.reports.dto.ReportDtos.TierCount;
import com.fairtix.reports.infrastructure.EventSettlementRepository;
import com.fairtix.reports.infrastructure.ReportQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * M2-15 + M2-16. Computes DOS and settlement reports for a single event.
 *
 * The DOS report freezes at "post-doors-open"; we generate it lazily on
 * request and it always reflects the latest data. The settlement report
 * additionally classifies refunds as pre-show vs post-show using
 * {@code event.start_time + 24h} as the cutoff per the implementation plan.
 */
@Service
@Transactional(readOnly = true)
public class SettlementService {

  // Roadmap §2F: post-show refund window is 24h after event start
  private static final long POST_SHOW_REFUND_WINDOW_HOURS = 24;

  private final ReportQueryRepository queries;
  private final EventRepository events;
  private final OrganizationRepository organizations;
  private final EventSettlementRepository settlements;
  private final AuditService audit;

  public SettlementService(ReportQueryRepository queries,
                           EventRepository events,
                           OrganizationRepository organizations,
                           EventSettlementRepository settlements,
                           AuditService audit) {
    this.queries = queries;
    this.events = events;
    this.organizations = organizations;
    this.settlements = settlements;
    this.audit = audit;
  }

  // ---------------------------------------------------------------- DOS

  public DosReport dosReport(UUID orgId, UUID eventId) {
    Event event = requireEventInOrg(orgId, eventId);
    Organization org = organizations.findById(orgId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

    // Ticket breakdown by kind
    List<TierCount> kinds = new ArrayList<>();
    long paidCount = 0;
    BigDecimal paidGross = BigDecimal.ZERO;
    long compCount = 0;
    long holdCount = 0;
    for (Object[] row : queries.ticketBreakdownByKind(eventId)) {
      String kind = (String) row[0];
      long count = ((Number) row[1]).longValue();
      BigDecimal gross = (BigDecimal) row[2];
      kinds.add(new TierCount(kind, count, ReportMath.money(gross)));
      switch (kind) {
        case "PAID" -> { paidCount += count; paidGross = paidGross.add(gross); }
        case "COMP" -> compCount += count;
        default -> {
          if (kind != null && kind.startsWith("HOLD_")) holdCount += count;
        }
      }
    }

    // Comp reasons
    List<CompReasonRow> reasons = new ArrayList<>();
    for (Object[] r : queries.compReasonBreakdown(eventId)) {
      reasons.add(new CompReasonRow((String) r[0], ((Number) r[1]).longValue()));
    }

    long unclaimedHolds = queries.activeHoldsForEvent(eventId);
    holdCount += unclaimedHolds;

    // Box office breakdown
    List<BoxOfficeMethodRow> bo = new ArrayList<>();
    for (Object[] r : queries.boxOfficeBreakdown(eventId)) {
      bo.add(new BoxOfficeMethodRow(
          (String) r[0],
          ((Number) r[1]).longValue(),
          ReportMath.money((BigDecimal) r[2]),
          ((Number) r[3]).longValue()));
    }

    // Refunds before doors / show start are pre-show
    Instant cutoff = event.getStartTime();
    BigDecimal preShowRefunds = BigDecimal.ZERO;
    for (Object[] r : queries.refundsForEvent(eventId)) {
      BigDecimal amt = (BigDecimal) r[1];
      java.sql.Timestamp ts = (java.sql.Timestamp) r[2];
      Instant when = ts == null ? Instant.EPOCH : ts.toInstant();
      if (when.isBefore(cutoff)) {
        preShowRefunds = preShowRefunds.add(amt);
      }
    }

    int planBps = org.getPlan() == null ? Plan.FREE.getPlatformFeeBps() : org.getPlan().getPlatformFeeBps();
    BigDecimal addOnRevenue = BigDecimal.ZERO; // add-ons land in M3
    BigDecimal taxRate = resolveTaxRate(orgId, eventId);
    BigDecimal salesTax = ReportMath.salesTax(paidGross, taxRate);
    BigDecimal platformFee = ReportMath.platformFee(paidGross, planBps);
    BigDecimal stripeFee = ReportMath.stripeFee(paidGross, paidCount);
    BigDecimal net = ReportMath.net(paidGross, addOnRevenue, salesTax,
        ReportMath.money(preShowRefunds), platformFee, stripeFee);

    return new DosReport(
        event.getId(),
        event.getTitle(),
        event.getStartTime(),
        event.getVenue() == null ? null : event.getVenue().getName(),
        Instant.now(),
        kinds,
        reasons,
        holdCount,
        bo,
        ReportMath.money(paidGross),
        ReportMath.money(addOnRevenue),
        salesTax,
        ReportMath.money(preShowRefunds),
        platformFee,
        stripeFee,
        net);
  }

  // ---------------------------------------------------------------- Settlement

  public SettlementReport settlement(UUID orgId, UUID eventId) {
    Event event = requireEventInOrg(orgId, eventId);
    DosReport dos = dosReport(orgId, eventId);

    Instant postShowCutoff = event.getStartTime().plus(POST_SHOW_REFUND_WINDOW_HOURS, ChronoUnit.HOURS);
    BigDecimal postShowRefunds = BigDecimal.ZERO;
    for (Object[] r : queries.refundsForEvent(eventId)) {
      BigDecimal amt = (BigDecimal) r[1];
      java.sql.Timestamp ts = (java.sql.Timestamp) r[2];
      Instant when = ts == null ? Instant.EPOCH : ts.toInstant();
      if (!when.isBefore(event.getStartTime()) && when.isBefore(postShowCutoff)) {
        postShowRefunds = postShowRefunds.add(amt);
      }
    }
    postShowRefunds = ReportMath.money(postShowRefunds);

    // Settlement-time net subtracts post-show refunds from DOS net
    BigDecimal settlementNet = dos.net().subtract(postShowRefunds);

    EventSettlement config = settlements.findByEventId(eventId).orElse(null);
    SplitType splitType = config == null ? null : config.getSplitType();
    BigDecimal artistPct = config == null ? null : config.getArtistPct();
    BigDecimal venueTakeOffTop = config == null ? null : config.getVenueTakeOffTop();

    // Switch expression forces every SplitType variant to be handled — if a
    // new enum value is added (e.g. GUARANTEE_PLUS_BACKEND) the build fails
    // here rather than silently zeroing the artist payout in prod. Per the
    // M2 plan: anything past FLAT_PCT / DOOR_DEAL is contact-support territory.
    BigDecimal artistPayout;
    if (splitType == null) {
      artistPayout = BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
    } else {
      artistPayout = switch (splitType) {
        case FLAT_PCT  -> ReportMath.flatArtistPayout(settlementNet, artistPct);
        case DOOR_DEAL -> ReportMath.doorDealArtistPayout(settlementNet, venueTakeOffTop, artistPct);
      };
    }
    BigDecimal venueRetention = settlementNet.subtract(artistPayout);

    return new SettlementReport(
        event.getId(),
        event.getTitle(),
        event.getStartTime(),
        event.getVenue() == null ? null : event.getVenue().getName(),
        Instant.now(),
        dos,
        postShowRefunds,
        splitType,
        artistPct,
        venueTakeOffTop,
        artistPayout,
        venueRetention,
        config != null && config.isFinalized(),
        config == null ? null : config.getFinalizedAt(),
        config == null ? null : config.getFinalizedByUserId(),
        config == null ? null : config.getNotes());
  }

  // ---------------------------------------------------------------- Config CRUD

  @Transactional
  public SettlementConfigResponse upsertConfig(UUID orgId, UUID eventId, UUID actorUserId,
                                               SettlementConfigRequest req) {
    requireEventInOrg(orgId, eventId);
    if (req.splitType() == SplitType.FLAT_PCT
        && (req.artistPct() == null || req.artistPct().signum() < 0)) {
      throw new IllegalArgumentException("FLAT_PCT requires a non-negative artist_pct");
    }
    if (req.splitType() == SplitType.DOOR_DEAL
        && (req.artistPct() == null || req.venueTakeOffTop() == null)) {
      throw new IllegalArgumentException("DOOR_DEAL requires both artist_pct and venue_take_off_top");
    }

    EventSettlement config = settlements.findByEventId(eventId)
        .orElseGet(() -> new EventSettlement(eventId));
    config.updateConfig(req.splitType(), req.artistPct(), req.venueTakeOffTop(),
        req.taxRatePct(), req.notes());
    EventSettlement saved = settlements.save(config);

    audit.log(actorUserId, "SETTLEMENT_CONFIG_UPDATED", "EVENT", eventId,
        "split=" + req.splitType() + " artist=" + req.artistPct() + " tax=" + req.taxRatePct());
    return toConfigResponse(saved);
  }

  @Transactional
  public SettlementConfigResponse finalizeSettlement(UUID orgId, UUID eventId, UUID actorUserId) {
    requireEventInOrg(orgId, eventId);
    EventSettlement config = settlements.findByEventId(eventId)
        .orElseThrow(() -> new ResourceNotFoundException("No settlement configured for event " + eventId));
    if (config.getSplitType() == null) {
      throw new IllegalStateException("Cannot finalize without a split configured");
    }
    config.finalize(actorUserId);
    settlements.save(config);
    audit.log(actorUserId, "SETTLEMENT_FINALIZED", "EVENT", eventId, null);
    return toConfigResponse(config);
  }

  public SettlementConfigResponse getConfig(UUID orgId, UUID eventId) {
    requireEventInOrg(orgId, eventId);
    return settlements.findByEventId(eventId)
        .map(SettlementService::toConfigResponse)
        .orElse(new SettlementConfigResponse(eventId, null, null, null, null, null, false, null));
  }

  // ---------------------------------------------------------------- Helpers

  private BigDecimal resolveTaxRate(UUID orgId, UUID eventId) {
    return settlements.findByEventId(eventId)
        .map(EventSettlement::getTaxRatePct)
        .filter(v -> v != null)
        .orElseGet(() -> organizations.findById(orgId)
            .map(Organization::getDefaultTaxRatePct)
            .orElse(BigDecimal.ZERO));
  }

  private Event requireEventInOrg(UUID orgId, UUID eventId) {
    Event e = events.findById(eventId)
        .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));
    if (e.getOrganizationId() == null || !e.getOrganizationId().equals(orgId)) {
      throw new ResourceNotFoundException("Event not found in organization");
    }
    return e;
  }

  private static SettlementConfigResponse toConfigResponse(EventSettlement c) {
    return new SettlementConfigResponse(
        c.getEventId(),
        c.getSplitType(),
        c.getArtistPct(),
        c.getVenueTakeOffTop(),
        c.getTaxRatePct(),
        c.getNotes(),
        c.isFinalized(),
        c.getFinalizedAt());
  }
}
