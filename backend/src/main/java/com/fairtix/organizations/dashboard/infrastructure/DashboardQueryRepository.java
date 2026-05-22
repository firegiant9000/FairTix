package com.fairtix.organizations.dashboard.infrastructure;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.fairtix.organizations.dashboard.dto.OrganizerDashboardDtos.AttendeeRow;
import com.fairtix.organizations.dashboard.dto.OrganizerDashboardDtos.RecentSale;
import com.fairtix.organizations.dashboard.dto.OrganizerDashboardDtos.TopEvent;
import com.fairtix.organizations.dashboard.dto.OrganizerDashboardDtos.VelocityPoint;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

/**
 * Native SQL queries for the organizer dashboard. Kept separate from the JPA
 * repositories because every query is org-scoped: the {@code organization_id}
 * column lives on {@code events}, so most aggregates join through tickets→events.
 */
@Repository
public class DashboardQueryRepository {

  @PersistenceContext
  private EntityManager em;

  // -- Overview aggregates ------------------------------------------------

  public BigDecimal grossRevenueSince(UUID orgId, Instant since) {
    Query q = em.createNativeQuery("""
        SELECT COALESCE(SUM(t.price), 0)
          FROM tickets t
          JOIN events e ON e.id = t.event_id
         WHERE e.organization_id = :orgId
           AND t.status IN ('VALID','USED','TRANSFERRED')
           AND t.issued_at >= :since
        """);
    q.setParameter("orgId", orgId);
    q.setParameter("since", Timestamp.from(since));
    Object result = q.getSingleResult();
    return result == null ? BigDecimal.ZERO : (BigDecimal) result;
  }

  public long ticketsSoldSince(UUID orgId, Instant since) {
    Query q = em.createNativeQuery("""
        SELECT COUNT(*)
          FROM tickets t
          JOIN events e ON e.id = t.event_id
         WHERE e.organization_id = :orgId
           AND t.status IN ('VALID','USED','TRANSFERRED')
           AND t.issued_at >= :since
        """);
    q.setParameter("orgId", orgId);
    q.setParameter("since", Timestamp.from(since));
    return ((Number) q.getSingleResult()).longValue();
  }

  public BigDecimal grossRevenueBetween(UUID orgId, Instant from, Instant to) {
    Query q = em.createNativeQuery("""
        SELECT COALESCE(SUM(t.price), 0)
          FROM tickets t
          JOIN events e ON e.id = t.event_id
         WHERE e.organization_id = :orgId
           AND t.status IN ('VALID','USED','TRANSFERRED')
           AND t.issued_at >= :from
           AND t.issued_at <  :to
        """);
    q.setParameter("orgId", orgId);
    q.setParameter("from", Timestamp.from(from));
    q.setParameter("to", Timestamp.from(to));
    return (BigDecimal) q.getSingleResult();
  }

  public long ticketsSoldBetween(UUID orgId, Instant from, Instant to) {
    Query q = em.createNativeQuery("""
        SELECT COUNT(*)
          FROM tickets t
          JOIN events e ON e.id = t.event_id
         WHERE e.organization_id = :orgId
           AND t.status IN ('VALID','USED','TRANSFERRED')
           AND t.issued_at >= :from
           AND t.issued_at <  :to
        """);
    q.setParameter("orgId", orgId);
    q.setParameter("from", Timestamp.from(from));
    q.setParameter("to", Timestamp.from(to));
    return ((Number) q.getSingleResult()).longValue();
  }

  public long pendingRefundCount(UUID orgId) {
    Query q = em.createNativeQuery("""
        SELECT COUNT(DISTINCT r.id)
          FROM refund_requests r
          JOIN tickets t ON t.order_id = r.order_id
          JOIN events e  ON e.id      = t.event_id
         WHERE e.organization_id = :orgId
           AND r.status IN ('REQUESTED','PENDING_MANUAL')
        """);
    q.setParameter("orgId", orgId);
    return ((Number) q.getSingleResult()).longValue();
  }

  public Instant oldestPendingRefund(UUID orgId) {
    Query q = em.createNativeQuery("""
        SELECT MIN(r.created_at)
          FROM refund_requests r
          JOIN tickets t ON t.order_id = r.order_id
          JOIN events e  ON e.id      = t.event_id
         WHERE e.organization_id = :orgId
           AND r.status IN ('REQUESTED','PENDING_MANUAL')
        """);
    q.setParameter("orgId", orgId);
    Object o = q.getSingleResult();
    if (o == null) return null;
    return ((Timestamp) o).toInstant();
  }

  public long refundsCompletedForEvent(UUID eventId) {
    Query q = em.createNativeQuery("""
        SELECT COUNT(DISTINCT r.id)
          FROM refund_requests r
          JOIN tickets t ON t.order_id = r.order_id
         WHERE t.event_id = :eventId
           AND r.status = 'COMPLETED'
        """);
    q.setParameter("eventId", eventId);
    return ((Number) q.getSingleResult()).longValue();
  }

  public long refundsPendingForEvent(UUID eventId) {
    Query q = em.createNativeQuery("""
        SELECT COUNT(DISTINCT r.id)
          FROM refund_requests r
          JOIN tickets t ON t.order_id = r.order_id
         WHERE t.event_id = :eventId
           AND r.status IN ('REQUESTED','PENDING_MANUAL')
        """);
    q.setParameter("eventId", eventId);
    return ((Number) q.getSingleResult()).longValue();
  }

  @SuppressWarnings("unchecked")
  public List<RecentSale> recentSales(UUID orgId, int limit) {
    Query q = em.createNativeQuery("""
        SELECT t.id, t.event_id, e.title, u.email,
               s.section, s.row_label, s.seat_number, t.price, t.issued_at
          FROM tickets t
          JOIN events e ON e.id = t.event_id
          JOIN users  u ON u.id = t.user_id
          JOIN seats  s ON s.id = t.seat_id
         WHERE e.organization_id = :orgId
         ORDER BY t.issued_at DESC
         LIMIT :limit
        """);
    q.setParameter("orgId", orgId);
    q.setParameter("limit", limit);
    List<Object[]> rows = q.getResultList();
    List<RecentSale> out = new ArrayList<>(rows.size());
    for (Object[] r : rows) {
      String seatLabel = String.format("%s %s-%s", r[4], r[5], r[6]);
      out.add(new RecentSale(
          (UUID) r[0],
          (UUID) r[1],
          (String) r[2],
          (String) r[3],
          seatLabel,
          (BigDecimal) r[7],
          ((Timestamp) r[8]).toInstant()));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  public List<TopEvent> topEventsByVelocity(UUID orgId, Instant since, int limit) {
    Query q = em.createNativeQuery("""
        SELECT e.id, e.title, e.start_time,
               COUNT(t.id) AS sold, COALESCE(SUM(t.price), 0) AS revenue
          FROM events e
          LEFT JOIN tickets t
                 ON t.event_id = e.id
                AND t.issued_at >= :since
                AND t.status IN ('VALID','USED','TRANSFERRED')
         WHERE e.organization_id = :orgId
           AND e.status IN ('PUBLISHED','ACTIVE')
         GROUP BY e.id, e.title, e.start_time
         ORDER BY sold DESC, e.start_time ASC
         LIMIT :limit
        """);
    q.setParameter("orgId", orgId);
    q.setParameter("since", Timestamp.from(since));
    q.setParameter("limit", limit);
    List<Object[]> rows = q.getResultList();
    List<TopEvent> out = new ArrayList<>(rows.size());
    for (Object[] r : rows) {
      out.add(new TopEvent(
          (UUID) r[0],
          (String) r[1],
          ((Timestamp) r[2]).toInstant(),
          ((Number) r[3]).longValue(),
          (BigDecimal) r[4]));
    }
    return out;
  }

  /** Returns rows of (event_id, sold_count, capacity) for the org's upcoming events. */
  @SuppressWarnings("unchecked")
  public List<Object[]> eventInventoryRollup(UUID orgId, Instant startWindow, Instant endWindow) {
    Query q = em.createNativeQuery("""
        SELECT e.id,
               (SELECT COUNT(*) FROM tickets tk
                 WHERE tk.event_id = e.id
                   AND tk.status IN ('VALID','USED','TRANSFERRED')) AS sold,
               (SELECT COUNT(*) FROM seats s WHERE s.event_id = e.id) AS capacity
          FROM events e
         WHERE e.organization_id = :orgId
           AND e.start_time >= :startWindow
           AND e.start_time <  :endWindow
        """);
    q.setParameter("orgId", orgId);
    q.setParameter("startWindow", Timestamp.from(startWindow));
    q.setParameter("endWindow", Timestamp.from(endWindow));
    return q.getResultList();
  }

  // -- Per-event aggregates ----------------------------------------------

  public long countSeats(UUID eventId) {
    Query q = em.createNativeQuery("SELECT COUNT(*) FROM seats WHERE event_id = :id");
    q.setParameter("id", eventId);
    return ((Number) q.getSingleResult()).longValue();
  }

  @SuppressWarnings("unchecked")
  public List<Object[]> seatStatusBreakdown(UUID eventId) {
    Query q = em.createNativeQuery(
        "SELECT status, COUNT(*) FROM seats WHERE event_id = :id GROUP BY status");
    q.setParameter("id", eventId);
    return q.getResultList();
  }

  public long ticketsSoldForEvent(UUID eventId) {
    Query q = em.createNativeQuery("""
        SELECT COUNT(*) FROM tickets
         WHERE event_id = :id
           AND status IN ('VALID','USED','TRANSFERRED')
        """);
    q.setParameter("id", eventId);
    return ((Number) q.getSingleResult()).longValue();
  }

  public BigDecimal grossForEvent(UUID eventId) {
    Query q = em.createNativeQuery("""
        SELECT COALESCE(SUM(price), 0) FROM tickets
         WHERE event_id = :id
           AND status IN ('VALID','USED','TRANSFERRED')
        """);
    q.setParameter("id", eventId);
    return (BigDecimal) q.getSingleResult();
  }

  @SuppressWarnings("unchecked")
  public List<VelocityPoint> velocity(UUID eventId, Instant since) {
    Query q = em.createNativeQuery("""
        SELECT CAST(issued_at AS DATE) AS day,
               COUNT(*) AS sold,
               COALESCE(SUM(price), 0) AS revenue
          FROM tickets
         WHERE event_id = :id
           AND status IN ('VALID','USED','TRANSFERRED')
           AND issued_at >= :since
         GROUP BY CAST(issued_at AS DATE)
         ORDER BY day
        """);
    q.setParameter("id", eventId);
    q.setParameter("since", Timestamp.from(since));
    List<Object[]> rows = q.getResultList();
    List<VelocityPoint> out = new ArrayList<>(rows.size());
    for (Object[] r : rows) {
      LocalDate day = ((Date) r[0]).toLocalDate();
      out.add(new VelocityPoint(day, ((Number) r[1]).longValue(), (BigDecimal) r[2]));
    }
    return out;
  }

  // -- Attendees (paginated + CSV) ---------------------------------------

  public long countAttendees(UUID eventId, String search) {
    String sql = """
        SELECT COUNT(*)
          FROM tickets t
          JOIN users u ON u.id = t.user_id
         WHERE t.event_id = :id
        """ + (search == null || search.isBlank() ? "" : " AND LOWER(u.email) LIKE :q ");
    Query q = em.createNativeQuery(sql);
    q.setParameter("id", eventId);
    if (search != null && !search.isBlank()) {
      q.setParameter("q", "%" + search.toLowerCase().trim() + "%");
    }
    return ((Number) q.getSingleResult()).longValue();
  }

  @SuppressWarnings("unchecked")
  public List<AttendeeRow> attendees(UUID eventId, String search, int page, int size) {
    String sql = """
        SELECT t.id, t.order_id, u.email,
               s.section, s.row_label, s.seat_number,
               t.price, t.status, t.issued_at
          FROM tickets t
          JOIN users u ON u.id = t.user_id
          JOIN seats s ON s.id = t.seat_id
         WHERE t.event_id = :id
        """ + (search == null || search.isBlank() ? "" : " AND LOWER(u.email) LIKE :q ") + """
         ORDER BY t.issued_at DESC
         LIMIT :limit OFFSET :offset
        """;
    Query q = em.createNativeQuery(sql);
    q.setParameter("id", eventId);
    if (search != null && !search.isBlank()) {
      q.setParameter("q", "%" + search.toLowerCase().trim() + "%");
    }
    q.setParameter("limit", size);
    q.setParameter("offset", (long) page * size);
    List<Object[]> rows = q.getResultList();
    List<AttendeeRow> out = new ArrayList<>(rows.size());
    for (Object[] r : rows) {
      out.add(new AttendeeRow(
          (UUID) r[0],
          (UUID) r[1],
          (String) r[2],
          (String) r[3],
          (String) r[4],
          (String) r[5],
          (BigDecimal) r[6],
          (String) r[7],
          ((Timestamp) r[8]).toInstant()));
    }
    return out;
  }
}
