package com.fairtix.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.application.SeatHoldService;
import com.fairtix.inventory.domain.HoldStatus;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.infrastructure.SeatHoldRepository;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.inventory.scheduler.HoldExpirationScheduler;
import com.fairtix.support.PostgresIT;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * #318: the CONFIRMED-hold sweep must not undo a payment extension that commits
 * after the sweep read the hold.
 *
 * <p>The sweep reads a page of holds, then each hold's seat. If an extension commits
 * between those two reads, the sweep sees the hold's old expiry but the seat's new
 * version, so the seat-version check that otherwise rolls the sweep back does not
 * fire. The test reproduces that interleaving exactly: the spied page query returns,
 * a real extension runs and commits on another thread, and the seat is then re-read
 * in the sweep's persistence context, standing in for the later seat read.
 *
 * <p>Red before the fix: the sweep wrote back the whole hold row it had read, so the
 * hold became EXPIRED with its old expiry and the seat went back on sale while the
 * customer was paying.
 *
 * <p>Its own context (a spied repository), so {@code @DirtiesContext} like the other
 * single-context ITs; scheduling is off in the {@code it} profile.
 */
@DirtiesContext
class ConfirmedHoldSweepStaleReadIT extends PostgresIT {

  @MockitoSpyBean private SeatHoldRepository seatHoldRepository;
  @Autowired private HoldExpirationScheduler scheduler;
  @Autowired private SeatHoldService seatHoldService;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @PersistenceContext private EntityManager entityManager;

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void extensionCommittedAfterTheSweepReadTheHold_keepsHoldAndSeat() throws Exception {
    Event event = eventRepository.save(
        new Event("Stale Sweep Concert", null, Instant.now().plusSeconds(86400), (UUID) null));
    Seat seat = seatRepository.save(new Seat(event, "A", "1", "1", new BigDecimal("50.00")));
    User buyer = newUser("buyer@test.com");
    SeatHold hold = seatHoldService.createHold(event.getId(), List.of(seat.getId()), buyer.getId(), 10)
        .get(0);
    seatHoldService.confirmHold(hold.getId(), buyer.getId());
    jdbcTemplate.update(
        "UPDATE seat_holds SET expires_at = now() - interval '20 minutes' WHERE id = ?", hold.getId());

    ExecutorService other = Executors.newSingleThreadExecutor();
    try {
      doAnswer(inv -> {
        // The same query the derived method runs; a repository spy delegates and has no
        // real method to call.
        Pageable pageable = inv.getArgument(2);
        List<SeatHold> holds = entityManager.createQuery(
                "SELECT h FROM SeatHold h WHERE h.status = :status AND h.expiresAt < :before"
                    + " ORDER BY h.id", SeatHold.class)
            .setParameter("status", inv.getArgument(0))
            .setParameter("before", inv.getArgument(1))
            .setMaxResults(pageable.getPageSize())
            .getResultList();
        Page<SeatHold> page = new PageImpl<>(holds, pageable, holds.size());
        // The customer opens checkout now: the extension commits on its own connection.
        other.submit(() -> {
          seatHoldService.extendConfirmedHoldsForPayment(List.of(hold.getId()), buyer.getId());
          return null;
        }).get(30, TimeUnit.SECONDS);
        // The sweep's seat read happens after that commit.
        for (SeatHold h : page.getContent()) {
          entityManager.refresh(h.getSeat());
        }
        return page;
      }).when(seatHoldRepository).findAllByStatusAndExpiresAtBefore(
          eq(HoldStatus.CONFIRMED), any(Instant.class), any());

      scheduler.expireConfirmedHolds();
    } finally {
      other.shutdownNow();
    }

    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM seat_holds WHERE id = ?", String.class, hold.getId()))
        .isEqualTo("CONFIRMED");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM seats WHERE id = ?", String.class, seat.getId()))
        .isEqualTo("BOOKED");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT expires_at > now() + interval '9 minutes' FROM seat_holds WHERE id = ?",
        Boolean.class, hold.getId())).isTrue();
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM audit_logs WHERE action = 'EXPIRE' AND resource_id = ?",
        Integer.class, hold.getId())).isZero();
  }

  private User newUser(String email) {
    User user = new User();
    user.setEmail(email);
    user.setPassword("$2a$10$dummyhashfortest");
    user.setEmailVerified(true);
    return userRepository.save(user);
  }
}
