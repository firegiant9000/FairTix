package com.fairtix.analytics.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Aggregate analytics snapshot for the admin dashboard")
public record AnalyticsResponse(
        OverviewStats overview,
        List<VenueCount> eventsByVenue,
        Map<String, Long> seatsByStatus,
        List<EventInventory> topEventsByBookings,
        Map<String, Long> holdsByStatus,
        @Schema(description = "Hold confirmation rate as a percentage (0-100)", example = "75.0")
        double holdConfirmationRate,
        List<DailyHoldCount> holdsPerDay,
        Map<String, Long> usersByRole,
        @Schema(description = "Total completed revenue across all orders") BigDecimal totalRevenue,
        @Schema(description = "Daily revenue for the last 30 days") List<DailyRevenue> revenuePerDay,
        @Schema(description = "Daily tickets sold for the last 30 days") List<DailyHoldCount> ticketsSoldPerDay,
        @Schema(description = "Daily refund requests for the last 30 days") List<DailyHoldCount> refundsPerDay) {

    @Schema(description = "High-level platform statistics")
    public record OverviewStats(
            @Schema(example = "42") long totalEvents,
            @Schema(example = "18") long upcomingEvents,
            @Schema(example = "350") long totalUsers,
            @Schema(example = "5000") long totalSeats,
            @Schema(example = "1200") long bookedSeats,
            @Schema(example = "85") long activeHolds,
            @Schema(example = "300") long soldSeats) {
    }

    @Schema(description = "Event count grouped by venue")
    public record VenueCount(
            @Schema(example = "Midtown Demo Garden") String venue,
            @Schema(example = "12") long count) {
    }

    @Schema(description = "Seat inventory breakdown for a single event")
    public record EventInventory(
            UUID eventId,
            @Schema(example = "Summer Music Festival") String eventTitle,
            @Schema(example = "200") long available,
            @Schema(example = "50") long held,
            @Schema(example = "150") long booked,
            @Schema(example = "100") long sold) {
    }

    @Schema(description = "Hold count for a single day")
    public record DailyHoldCount(
            @Schema(example = "2026-03-25") String date,
            @Schema(example = "34") long count) {
    }

    @Schema(description = "Revenue total for a single day")
    public record DailyRevenue(
            @Schema(example = "2026-03-25") String date,
            @Schema(example = "1250.00") BigDecimal amount) {
    }
}
