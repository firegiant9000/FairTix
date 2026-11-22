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
 * The ACTIVE-hold sweep must not expire a hold that {@code confirmHold} confirmed after
 * the sweep read it. Same shape as #318 ({@link ConfirmedHoldSweepStaleReadIT}).
 *
 * <p>A hold confirmed at its expiry boundary keeps its original expiry, so the sweep's
 * page query can still have selected it as ACTIVE. If the confirm commits between that
 * read and the sweep's seat read, the sweep sees the seat as BOOKED (new version) and
 * leaves it alone, so the seat-version check that otherwise rolls the sweep back does
 * not fire. The test reproduces that interleaving: the spied page query returns, a real
 * confirm commits on another thread, and the seat is re-read in the sweep's persistence
 * context.
 *
 * <p>Red before the fix: the sweep wrote back the whole hold row it had read, so the
 * hold became EXPIRED while its seat stayed BOOKED: a booked seat with no live hold,
 * which no sweep ever frees and no checkout can use.
 */
@DirtiesContext
class ActiveHoldSweepStaleReadIT extends PostgresIT {

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
  void confirmCommittedAfterTheSweepReadTheHold_keepsHoldConfirmedAndSeatBooked() throws Exception {
    Event event = eventRepository.save(
        new Event("Boundary Confirm Concert", null, Instant.now().plusSeconds(86400), (UUID) null));
    Seat seat = seatRepository.save(new Seat(event, "A", "1", "1", new BigDecimal("50.00")));
    User buyer = newUser("buyer@test.com");
    SeatHold hold = seatHoldService.createHold(event.getId(), List.of(seat.getId()), buyer.getId(), 10)
        .get(0);
    jdbcTemplate.update(
        "UPDATE seat_holds SET expires_at = now() - interval '1 second' WHERE id = ?", hold.getId());

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
        // The customer confirms at the boundary, on its own connection: confirmHold saw
        // the expiry a moment in the future, and the hold keeps that expiry, which is in
        // the past by the time the sweep continues.
        other.submit(() -> {
          jdbcTemplate.update(
              "UPDATE seat_holds SET expires_at = now() + interval '1 minute' WHERE id = ?",
              hold.getId());
          seatHoldService.confirmHold(hold.getId(), buyer.getId());
          jdbcTemplate.update(
              "UPDATE seat_holds SET expires_at = now() - interval '1 second' WHERE id = ?",
              hold.getId());
          return null;
        }).get(30, TimeUnit.SECONDS);
        // The sweep's seat read happens after that commit.
        for (SeatHold h : page.getContent()) {
          entityManager.refresh(h.getSeat());
        }
        return page;
      }).when(seatHoldRepository).findAllByStatusAndExpiresAtBefore(
          eq(HoldStatus.ACTIVE), any(Instant.class), any());

      scheduler.expireHolds();
    } finally {
      other.shutdownNow();
    }

    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM seat_holds WHERE id = ?", String.class, hold.getId()))
        .isEqualTo("CONFIRMED");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM seats WHERE id = ?", String.class, seat.getId()))
        .isEqualTo("BOOKED");
  }

  private User newUser(String email) {
    User user = new User();
    user.setEmail(email);
    user.setPassword("$2a$10$dummyhashfortest");
    user.setEmailVerified(true);
    return userRepository.save(user);
  }
}
