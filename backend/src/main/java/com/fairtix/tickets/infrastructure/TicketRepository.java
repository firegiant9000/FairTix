package com.fairtix.tickets.infrastructure;

import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.domain.TicketKind;
import com.fairtix.tickets.domain.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

  long countByEvent_IdAndKind(UUID eventId, TicketKind kind);

  long countByEvent_IdAndKindIn(UUID eventId, Collection<TicketKind> kinds);

  @Query("SELECT t FROM Ticket t JOIN FETCH t.seat s JOIN FETCH t.event e LEFT JOIN FETCH e.venue "
       + "WHERE t.event.id = :eventId AND t.willCall = TRUE "
       + "AND t.status NOT IN ('CANCELLED','REFUNDED') "
       + "ORDER BY COALESCE(t.recipientName, t.user.email)")
  List<Ticket> findWillCallForPrint(@Param("eventId") UUID eventId);

  @Query("SELECT t FROM Ticket t JOIN FETCH t.seat s JOIN FETCH t.event e LEFT JOIN FETCH e.venue "
       + "WHERE t.event.id = :eventId AND t.willCall = TRUE "
       + "AND t.status NOT IN ('CANCELLED','REFUNDED') "
       + "AND (LOWER(COALESCE(t.recipientName, '')) LIKE LOWER(CONCAT('%', :q, '%')) "
       + "  OR LOWER(t.user.email) LIKE LOWER(CONCAT('%', :q, '%'))) "
       + "ORDER BY COALESCE(t.recipientName, t.user.email)")
  List<Ticket> searchWillCall(@Param("eventId") UUID eventId, @Param("q") String q);

  List<Ticket> findAllByEvent_IdAndKindOrderByIssuedAtDesc(UUID eventId, TicketKind kind);

  @Query("SELECT t FROM Ticket t JOIN FETCH t.event e LEFT JOIN FETCH e.venue WHERE t.id = :id")
  Optional<Ticket> findByIdWithEventAndVenue(@Param("id") UUID id);

  List<Ticket> findAllByUser_IdOrderByIssuedAtDesc(UUID userId);

  List<Ticket> findAllByOrder_Id(UUID orderId);

  long countByUser_IdAndEvent_IdAndStatusNot(UUID userId, UUID eventId, TicketStatus status);

  long countByUser_IdAndEvent_IdAndStatusNotIn(UUID userId, UUID eventId, java.util.Collection<TicketStatus> statuses);

  /** Cancel cascade: find all tickets for a given event with a given status. */
  List<Ticket> findAllByEvent_IdAndStatus(UUID eventId, TicketStatus status);

  @Query(value = "SELECT CAST(issued_at AS DATE), COUNT(*) FROM tickets WHERE status = 'VALID' AND issued_at >= :since GROUP BY CAST(issued_at AS DATE) ORDER BY 1", nativeQuery = true)
  List<Object[]> soldPerDay(@Param("since") Instant since);
}
