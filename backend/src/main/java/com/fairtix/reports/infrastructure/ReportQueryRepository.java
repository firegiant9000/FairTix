package com.fairtix.reports.infrastructure;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Native SQL aggregates that back the settlement & tax reports. The math must
 * tie out to the penny, so every aggregate is computed in a single round trip
 * — never a sum of separate dashboard queries that can drift apart.
 */
@Repository
public class ReportQueryRepository {

  @PersistenceContext
  private EntityManager em;

  // ------------------------------------------------------------------ DOS aggregates

  /** Counts of (kind, ticket_count, gross) for an event, only counting non-cancelled/non-refunded. */
  @SuppressWarnings("unchecked")
  public List<Object[]> ticketBreakdownByKind(UUID eventId) {
    Query q = em.createNativeQuery("""
        SELECT t.kind, COUNT(*), COALESCE(SUM(t.price), 0)
          FROM tickets t
         WHERE t.event_id = :id
           AND t.status NOT IN ('CANCELLED','REFUNDED')
         GROUP BY t.kind
        """);
    q.setParameter("id", eventId);
    return q.getResultList();
  }

  /** Box-office sale totals (amount, seat_count) grouped by method. */
  @SuppressWarnings("unchecked")
  public List<Object[]> boxOfficeBreakdown(UUID eventId) {
    Query q = em.createNativeQuery("""
        SELECT method, COUNT(*), COALESCE(SUM(amount), 0), COALESCE(SUM(seat_count), 0)
          FROM box_office_sales
         WHERE event_id = :id
         GROUP BY method
        """);
    q.setParameter("id", eventId);
    return q.getResultList();
  }

  /** Stripe online payments captured for orders that contain tickets in this event. */
  public BigDecimal onlinePaymentsCaptured(UUID eventId) {
    Query q = em.createNativeQuery("""
        SELECT COALESCE(SUM(DISTINCT pr.amount), 0)
          FROM payment_records pr
         WHERE pr.status = 'SUCCESS'
           AND pr.order_id IN (
             SELECT DISTINCT order_id FROM tickets WHERE event_id = :id AND kind = 'PAID'
           )
        """);
    q.setParameter("id", eventId);
    return (BigDecimal) q.getSingleResult();
  }

  /** Refunds completed against tickets for an event, splitting pre-show and post-show. */
  @SuppressWarnings("unchecked")
  public List<Object[]> refundsForEvent(UUID eventId) {
    // (refund_id, amount, completed_at)
    Query q = em.createNativeQuery("""
        SELECT r.id, r.amount, COALESCE(r.completed_at, r.updated_at)
          FROM refund_requests r
         WHERE r.status IN ('COMPLETED','APPROVED')
           AND r.order_id IN (
             SELECT DISTINCT order_id FROM tickets WHERE event_id = :id
           )
        """);
    q.setParameter("id", eventId);
    return q.getResultList();
  }

  /** Comp ticket count grouped by reason for the per-event report. */
  @SuppressWarnings("unchecked")
  public List<Object[]> compReasonBreakdown(UUID eventId) {
    Query q = em.createNativeQuery("""
        SELECT COALESCE(kind_reason, '(no reason)') AS reason, COUNT(*)
          FROM tickets
         WHERE event_id = :id
           AND kind = 'COMP'
           AND status NOT IN ('CANCELLED','REFUNDED')
         GROUP BY COALESCE(kind_reason, '(no reason)')
         ORDER BY 2 DESC
        """);
    q.setParameter("id", eventId);
    return q.getResultList();
  }

  /** Active (un-released, un-converted) event holds at the time of query. */
  public long activeHoldsForEvent(UUID eventId) {
    Query q = em.createNativeQuery("""
        SELECT COUNT(*)
          FROM event_holds
         WHERE event_id = :id
           AND released_at IS NULL
           AND converted_ticket_id IS NULL
        """);
    q.setParameter("id", eventId);
    return ((Number) q.getSingleResult()).longValue();
  }

  // ------------------------------------------------------------------ Tax helper

  /** Year-to-date gross + tx-count across the org's Stripe-settled payments, by calendar year. */
  public Object[] orgYtdGrossAndCount(UUID orgId, int year) {
    Query q = em.createNativeQuery("""
        SELECT COALESCE(SUM(pr.amount), 0), COUNT(*)
          FROM payment_records pr
         WHERE pr.status = 'SUCCESS'
           AND EXTRACT(YEAR FROM pr.created_at) = :yr
           AND pr.order_id IN (
             SELECT DISTINCT t.order_id FROM tickets t
               JOIN events e ON e.id = t.event_id
              WHERE e.organization_id = :orgId
           )
        """);
    q.setParameter("orgId", orgId);
    q.setParameter("yr", year);
    return (Object[]) q.getSingleResult();
  }

  /** Per-event tax rows for an org & year — used for the year-end CSV export. */
  @SuppressWarnings("unchecked")
  public List<Object[]> taxRowsForYear(UUID orgId, int year) {
    Query q = em.createNativeQuery("""
        SELECT e.id, e.title, e.start_time,
               COALESCE(SUM(CASE WHEN t.kind = 'PAID'
                                   AND t.status NOT IN ('CANCELLED','REFUNDED')
                                  THEN t.price ELSE 0 END), 0) AS gross
          FROM events e
          LEFT JOIN tickets t ON t.event_id = e.id
         WHERE e.organization_id = :orgId
           AND EXTRACT(YEAR FROM e.start_time) = :yr
         GROUP BY e.id, e.title, e.start_time
         ORDER BY e.start_time
        """);
    q.setParameter("orgId", orgId);
    q.setParameter("yr", year);
    return q.getResultList();
  }

  // ------------------------------------------------------------------ Payout-to-event mapping

  /**
   * Given a stripe payout id, return the events whose ticket-revenue contributed
   * to that payout via payment_intents → orders → tickets → events. Stripe does
   * not expose the underlying charges on Standard payouts here; we approximate
   * by mapping all payments captured in the 24h window before the payout's
   * arrival_date that belong to this org. Good enough for the M2 dashboard;
   * exact balance-transaction reconciliation lands when we add Stripe Reporting
   * in M5.
   */
  @SuppressWarnings("unchecked")
  public List<Object[]> eventsForPayoutWindow(UUID orgId, Instant windowStart, Instant windowEnd) {
    Query q = em.createNativeQuery("""
        SELECT e.id, e.title, e.start_time,
               COUNT(DISTINCT t.id) AS tickets,
               COALESCE(SUM(CASE WHEN t.kind = 'PAID' THEN t.price ELSE 0 END), 0) AS gross
          FROM payment_records pr
          JOIN tickets t ON t.order_id = pr.order_id
          JOIN events e  ON e.id      = t.event_id
         WHERE e.organization_id = :orgId
           AND pr.status = 'SUCCESS'
           AND pr.created_at >= :from
           AND pr.created_at <  :to
         GROUP BY e.id, e.title, e.start_time
         ORDER BY e.start_time DESC
        """);
    q.setParameter("orgId", orgId);
    q.setParameter("from", Timestamp.from(windowStart));
    q.setParameter("to", Timestamp.from(windowEnd));
    return q.getResultList();
  }

  // ------------------------------------------------------------------ Helpers

  public static List<LocalDate> daysBetween(Instant from, Instant to) {
    List<LocalDate> out = new ArrayList<>();
    LocalDate start = LocalDate.ofInstant(from, java.time.ZoneOffset.UTC);
    LocalDate end = LocalDate.ofInstant(to, java.time.ZoneOffset.UTC);
    while (!start.isAfter(end)) {
      out.add(start);
      start = start.plusDays(1);
    }
    return out;
  }

  public static LocalDate dateOf(Object sqlDate) {
    if (sqlDate == null) return null;
    if (sqlDate instanceof Date d) return d.toLocalDate();
    if (sqlDate instanceof Timestamp ts) return ts.toLocalDateTime().toLocalDate();
    return null;
  }
}
