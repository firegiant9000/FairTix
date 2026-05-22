package com.fairtix.organizations.dashboard.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fairtix.events.domain.EventStatus;

/** DTOs for the organizer dashboard surface (M2-03..M2-05). */
public final class OrganizerDashboardDtos {

  private OrganizerDashboardDtos() {}

  // -- Overview (M2-04) ----------------------------------------------------

  public record DashboardOverview(
      List<TodayShow> todayShows,
      WeekRevenue weekRevenue,
      RefundQueue refundQueue,
      List<RecentSale> recentSales,
      List<TopEvent> topEvents) {}

  public record TodayShow(
      UUID eventId,
      String title,
      Instant startTime,
      String venueName,
      EventStatus status,
      long sold,
      long capacity) {}

  public record WeekRevenue(
      BigDecimal grossThisWeek,
      BigDecimal grossPriorWeek,
      long ticketsThisWeek,
      long ticketsPriorWeek) {}

  public record RefundQueue(long pendingCount, Instant oldestRequestedAt) {}

  public record RecentSale(
      UUID ticketId,
      UUID eventId,
      String eventTitle,
      String buyerEmail,
      String seatLabel,
      BigDecimal price,
      Instant issuedAt) {}

  public record TopEvent(
      UUID eventId,
      String title,
      Instant startTime,
      long ticketsLast7d,
      BigDecimal revenueLast7d) {}

  // -- Cross-event list (M2-03) -------------------------------------------

  public record OrganizerEventRow(
      UUID eventId,
      String title,
      Instant startTime,
      String venueName,
      EventStatus status,
      long sold,
      long capacity,
      BigDecimal gross) {}

  // -- Per-event view (M2-05) ---------------------------------------------

  public record EventInventory(
      long available,
      long held,
      long sold,
      long comped,
      long capacity) {}

  public record EventFinancials(
      BigDecimal gross,
      int platformFeeBps,
      BigDecimal platformFee,
      BigDecimal stripeFee,
      BigDecimal payoutEstimate) {}

  public record OrganizerEventSummary(
      UUID eventId,
      String title,
      Instant startTime,
      String venueName,
      EventStatus status,
      EventInventory inventory,
      EventFinancials financials,
      long refundsPending,
      long refundsCompleted) {}

  public record VelocityPoint(LocalDate date, long ticketsSold, BigDecimal revenue) {}

  public record AttendeeRow(
      UUID ticketId,
      UUID orderId,
      String buyerEmail,
      String seatSection,
      String seatRow,
      String seatNumber,
      BigDecimal price,
      String status,
      Instant issuedAt) {}

  public record AttendeePage(
      List<AttendeeRow> attendees,
      long total,
      int page,
      int size) {}
}
