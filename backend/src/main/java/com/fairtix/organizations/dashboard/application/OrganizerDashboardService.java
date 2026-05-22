package com.fairtix.organizations.dashboard.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.organizations.dashboard.dto.OrganizerDashboardDtos.AttendeePage;
import com.fairtix.organizations.dashboard.dto.OrganizerDashboardDtos.AttendeeRow;
import com.fairtix.organizations.dashboard.dto.OrganizerDashboardDtos.DashboardOverview;
import com.fairtix.organizations.dashboard.dto.OrganizerDashboardDtos.EventFinancials;
import com.fairtix.organizations.dashboard.dto.OrganizerDashboardDtos.EventInventory;
import com.fairtix.organizations.dashboard.dto.OrganizerDashboardDtos.OrganizerEventRow;
import com.fairtix.organizations.dashboard.dto.OrganizerDashboardDtos.OrganizerEventSummary;
import com.fairtix.organizations.dashboard.dto.OrganizerDashboardDtos.RefundQueue;
import com.fairtix.organizations.dashboard.dto.OrganizerDashboardDtos.TodayShow;
import com.fairtix.organizations.dashboard.dto.OrganizerDashboardDtos.VelocityPoint;
import com.fairtix.organizations.dashboard.dto.OrganizerDashboardDtos.WeekRevenue;
import com.fairtix.organizations.dashboard.infrastructure.DashboardQueryRepository;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;

@Service
@Transactional(readOnly = true)
public class OrganizerDashboardService {

  /** Stripe baseline: 2.9% of gross + $0.30 per ticket. Used for organizer payout estimates. */
  private static final BigDecimal STRIPE_PCT = new BigDecimal("0.029");
  private static final BigDecimal STRIPE_PER_TICKET = new BigDecimal("0.30");

  /** TTL for the per-org overview cache. Dashboard widgets do not need sub-second freshness. */
  private static final Duration OVERVIEW_TTL = Duration.ofSeconds(30);

  private final DashboardQueryRepository queries;
  private final EventRepository events;
  private final OrganizationRepository organizations;

  /** Per-org overview cache: each entry expires {@code OVERVIEW_TTL} after build. */
  private final ConcurrentHashMap<UUID, CachedOverview> overviewCache = new ConcurrentHashMap<>();

  public OrganizerDashboardService(DashboardQueryRepository queries,
                                   EventRepository events,
                                   OrganizationRepository organizations) {
    this.queries = queries;
    this.events = events;
    this.organizations = organizations;
  }

  // -- Overview ----------------------------------------------------------

  public DashboardOverview overview(UUID orgId) {
    Instant now = Instant.now();
    CachedOverview cached = overviewCache.get(orgId);
    if (cached != null && cached.expiresAt.isAfter(now)) {
      return cached.value;
    }
    DashboardOverview fresh = buildOverview(orgId, now);
    overviewCache.put(orgId, new CachedOverview(fresh, now.plus(OVERVIEW_TTL)));
    return fresh;
  }

  /** Drop the cached overview for one org. Call after writes that materially change widgets. */
  public void invalidateOverview(UUID orgId) {
    overviewCache.remove(orgId);
  }

  private DashboardOverview buildOverview(UUID orgId, Instant now) {
    Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
    Instant endOfDay = startOfDay.plus(1, ChronoUnit.DAYS);
    Instant weekAgo = now.minus(7, ChronoUnit.DAYS);
    Instant twoWeeksAgo = now.minus(14, ChronoUnit.DAYS);

    List<TodayShow> todayShows = buildTodayShows(orgId, startOfDay, endOfDay);

    WeekRevenue revenue = new WeekRevenue(
        queries.grossRevenueBetween(orgId, weekAgo, now),
        queries.grossRevenueBetween(orgId, twoWeeksAgo, weekAgo),
        queries.ticketsSoldBetween(orgId, weekAgo, now),
        queries.ticketsSoldBetween(orgId, twoWeeksAgo, weekAgo));

    RefundQueue refundQueue = new RefundQueue(
        queries.pendingRefundCount(orgId),
        queries.oldestPendingRefund(orgId));

    return new DashboardOverview(
        todayShows,
        revenue,
        refundQueue,
        queries.recentSales(orgId, 20),
        queries.topEventsByVelocity(orgId, weekAgo, 5));
  }

  private List<TodayShow> buildTodayShows(UUID orgId, Instant from, Instant to) {
    List<Event> todays = events.findOrgEventsBetween(orgId, from, to);
    if (todays.isEmpty()) return List.of();

    // Single query for sold/capacity for the day's events.
    Map<UUID, long[]> stats = new HashMap<>();
    for (Object[] row : queries.eventInventoryRollup(orgId, from, to)) {
      stats.put((UUID) row[0],
          new long[]{((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
    }

    List<TodayShow> out = new ArrayList<>(todays.size());
    for (Event e : todays) {
      long[] s = stats.getOrDefault(e.getId(), new long[]{0L, 0L});
      out.add(new TodayShow(
          e.getId(),
          e.getTitle(),
          e.getStartTime(),
          e.getVenue() != null ? e.getVenue().getName() : null,
          e.getStatus(),
          s[0],
          s[1]));
    }
    return out;
  }

  // -- Cross-event list --------------------------------------------------

  public List<OrganizerEventRow> listEventsForOrg(UUID orgId) {
    List<Event> orgEvents = events.findAllByOrganizationIdOrderByStartTimeDesc(orgId);
    List<OrganizerEventRow> out = new ArrayList<>(orgEvents.size());
    for (Event e : orgEvents) {
      long sold = queries.ticketsSoldForEvent(e.getId());
      long capacity = queries.countSeats(e.getId());
      BigDecimal gross = queries.grossForEvent(e.getId());
      out.add(new OrganizerEventRow(
          e.getId(),
          e.getTitle(),
          e.getStartTime(),
          e.getVenue() != null ? e.getVenue().getName() : null,
          e.getStatus(),
          sold,
          capacity,
          gross));
    }
    return out;
  }

  // -- Per-event summary -------------------------------------------------

  public OrganizerEventSummary eventSummary(UUID orgId, UUID eventId) {
    Event e = events.findById(eventId)
        .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));
    if (e.getOrganizationId() == null || !e.getOrganizationId().equals(orgId)) {
      throw new ResourceNotFoundException("Event not found in organization");
    }

    EventInventory inventory = buildInventory(eventId);
    EventFinancials financials = buildFinancials(orgId, inventory.sold(), queries.grossForEvent(eventId));

    return new OrganizerEventSummary(
        e.getId(),
        e.getTitle(),
        e.getStartTime(),
        e.getVenue() != null ? e.getVenue().getName() : null,
        e.getStatus(),
        inventory,
        financials,
        queries.refundsPendingForEvent(eventId),
        queries.refundsCompletedForEvent(eventId));
  }

  private EventInventory buildInventory(UUID eventId) {
    long available = 0, held = 0, sold = 0, capacity = 0;
    for (Object[] row : queries.seatStatusBreakdown(eventId)) {
      String status = (String) row[0];
      long count = ((Number) row[1]).longValue();
      capacity += count;
      switch (status) {
        case "AVAILABLE" -> available = count;
        case "HELD"      -> held = count;
        case "SOLD", "BOOKED" -> sold += count;
        default -> { /* unknown — ignored */ }
      }
    }
    // Comps land with M2-12; expose the column now so the dashboard
    // can render the four-way split without a follow-up frontend change.
    long comped = 0;
    return new EventInventory(available, held, sold, comped, capacity);
  }

  private EventFinancials buildFinancials(UUID orgId, long ticketsSold, BigDecimal gross) {
    Organization org = organizations.findById(orgId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));
    int feeBps = org.getPlan() == null ? 0 : org.getPlan().getPlatformFeeBps();

    BigDecimal platformFee = gross
        .multiply(BigDecimal.valueOf(feeBps))
        .divide(BigDecimal.valueOf(10_000), 2, RoundingMode.HALF_UP);

    BigDecimal stripeFee = gross.multiply(STRIPE_PCT)
        .add(STRIPE_PER_TICKET.multiply(BigDecimal.valueOf(ticketsSold)))
        .setScale(2, RoundingMode.HALF_UP);

    BigDecimal payout = gross.subtract(platformFee).subtract(stripeFee)
        .setScale(2, RoundingMode.HALF_UP);

    return new EventFinancials(gross.setScale(2, RoundingMode.HALF_UP),
        feeBps, platformFee, stripeFee, payout);
  }

  // -- Velocity ----------------------------------------------------------

  public List<VelocityPoint> velocity(UUID orgId, UUID eventId, int days) {
    requireEventInOrg(orgId, eventId);
    Instant since = Instant.now().minus(Math.max(1, days), ChronoUnit.DAYS);
    return queries.velocity(eventId, since);
  }

  // -- Attendees ---------------------------------------------------------

  public AttendeePage attendees(UUID orgId, UUID eventId, String search, int page, int size) {
    requireEventInOrg(orgId, eventId);
    int safeSize = Math.min(Math.max(size, 1), 200);
    int safePage = Math.max(page, 0);
    long total = queries.countAttendees(eventId, search);
    List<AttendeeRow> rows = queries.attendees(eventId, search, safePage, safeSize);
    return new AttendeePage(rows, total, safePage, safeSize);
  }

  /** Streams all attendees for an event into a CSV body. */
  public String attendeesCsv(UUID orgId, UUID eventId) {
    requireEventInOrg(orgId, eventId);
    // For CSV we expose every attendee — there is no pagination in the export.
    long total = queries.countAttendees(eventId, null);
    List<AttendeeRow> rows = queries.attendees(eventId, null, 0, (int) Math.min(total, 100_000));
    StringBuilder sb = new StringBuilder(rows.size() * 64);
    sb.append("ticket_id,order_id,email,section,row,seat,price,status,issued_at\n");
    for (AttendeeRow r : rows) {
      sb.append(r.ticketId()).append(',')
        .append(r.orderId()).append(',')
        .append(csvEscape(r.buyerEmail())).append(',')
        .append(csvEscape(r.seatSection())).append(',')
        .append(csvEscape(r.seatRow())).append(',')
        .append(csvEscape(r.seatNumber())).append(',')
        .append(r.price() == null ? "" : r.price().toPlainString()).append(',')
        .append(csvEscape(r.status())).append(',')
        .append(r.issuedAt()).append('\n');
    }
    return sb.toString();
  }

  private void requireEventInOrg(UUID orgId, UUID eventId) {
    Event e = events.findById(eventId)
        .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));
    if (e.getOrganizationId() == null || !e.getOrganizationId().equals(orgId)) {
      throw new ResourceNotFoundException("Event not found in organization");
    }
  }

  private static String csvEscape(String s) {
    if (s == null) return "";
    boolean needsQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
        || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
    if (!needsQuote) return s;
    return '"' + s.replace("\"", "\"\"") + '"';
  }

  private record CachedOverview(DashboardOverview value, Instant expiresAt) { }
}
