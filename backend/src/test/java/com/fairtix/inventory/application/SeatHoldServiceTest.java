package com.fairtix.inventory.application;

import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.domain.HoldStatus;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatHoldRepository;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.inventory.scheduler.HoldExpirationScheduler;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class SeatHoldServiceTest {

  @Autowired private SeatHoldService seatHoldService;
  @Autowired private SeatRepository seatRepository;
  @Autowired private SeatHoldRepository seatHoldRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private HoldExpirationScheduler scheduler;
  @PersistenceContext private EntityManager entityManager;

  private Event testEvent;
  private Seat testSeat;

  private static final UUID USER_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID USER_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

  /**
   * The sweep ends holds by bulk update, which bypasses this test transaction's
   * persistence context; flush the pending seat writes and drop the cached entities so
   * the assertions read the database.
   */
  private void readFromDatabase() {
    entityManager.flush();
    entityManager.clear();
  }

  @BeforeEach
  void setUp() {
    testEvent = eventRepository.save(
        new Event("Test Event", null, Instant.now().plusSeconds(3600), null));
    testSeat = seatRepository.save(new Seat(testEvent, "Floor", "A", "101", new BigDecimal("25.00")));
  }

  // -------------------------------------------------------------------------
  // Original tests (updated for UUID ownerId)
  // -------------------------------------------------------------------------

  @Test
  void holdingASeatSucceeds() {
    List<SeatHold> holds = seatHoldService.createHold(
        testEvent.getId(), List.of(testSeat.getId()), USER_1, 10);

    assertThat(holds).hasSize(1);
    SeatHold hold = holds.get(0);
    assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
    assertThat(hold.getOwnerId()).isEqualTo(USER_1);
    assertThat(hold.getExpiresAt()).isAfter(Instant.now());

    Seat updatedSeat = seatRepository.findById(testSeat.getId()).orElseThrow();
    assertThat(updatedSeat.getStatus()).isEqualTo(SeatStatus.HELD);
  }

  @Test
  void holdingTheSameSeatTwiceReturnsConflict() {
    seatHoldService.createHold(
        testEvent.getId(), List.of(testSeat.getId()), USER_1, 10);

    assertThatThrownBy(() -> seatHoldService.createHold(
        testEvent.getId(), List.of(testSeat.getId()), USER_2, 10))
        .isInstanceOf(SeatHoldConflictException.class)
        .hasMessageContaining("not available");
  }

  @Test
  void expiredHoldsGetReleasedByScheduler() {
    testSeat.setStatus(SeatStatus.HELD);
    seatRepository.save(testSeat);

    SeatHold expiredHold = new SeatHold(testSeat, USER_1, Instant.now().minusSeconds(60));
    seatHoldRepository.save(expiredHold);

    scheduler.expireHolds();
    readFromDatabase();

    SeatHold hold = seatHoldRepository.findById(expiredHold.getId()).orElseThrow();
    assertThat(hold.getStatus()).isEqualTo(HoldStatus.EXPIRED);

    Seat seat = seatRepository.findById(testSeat.getId()).orElseThrow();
    assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
  }

  // -------------------------------------------------------------------------
  // Idempotency
  // -------------------------------------------------------------------------

  @Test
  void releasingAnAlreadyReleasedHoldIsIdempotent() {
    SeatHold hold = seatHoldService.createHold(
        testEvent.getId(), List.of(testSeat.getId()), USER_1, 10).get(0);

    SeatHold firstRelease  = seatHoldService.releaseHold(hold.getId(), USER_1);
    SeatHold secondRelease = seatHoldService.releaseHold(hold.getId(), USER_1);

    assertThat(firstRelease.getStatus()).isEqualTo(HoldStatus.RELEASED);
    assertThat(secondRelease.getStatus()).isEqualTo(HoldStatus.RELEASED);
    // Seat must remain AVAILABLE after idempotent call
    Seat seat = seatRepository.findById(testSeat.getId()).orElseThrow();
    assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
  }

  @Test
  void confirmingAnAlreadyConfirmedHoldIsIdempotent() {
    SeatHold hold = seatHoldService.createHold(
        testEvent.getId(), List.of(testSeat.getId()), USER_1, 10).get(0);

    SeatHold firstConfirm  = seatHoldService.confirmHold(hold.getId(), USER_1);
    SeatHold secondConfirm = seatHoldService.confirmHold(hold.getId(), USER_1);

    assertThat(firstConfirm.getStatus()).isEqualTo(HoldStatus.CONFIRMED);
    assertThat(secondConfirm.getStatus()).isEqualTo(HoldStatus.CONFIRMED);
    Seat seat = seatRepository.findById(testSeat.getId()).orElseThrow();
    assertThat(seat.getStatus()).isEqualTo(SeatStatus.BOOKED);
  }

  // -------------------------------------------------------------------------
  // Duration clamping (max-duration-minutes=60 in test properties)
  // -------------------------------------------------------------------------

  @Test
  void durationIsClampedToMaxDurationMinutes() {
    // Request a hold 10× longer than the allowed maximum
    List<SeatHold> holds = seatHoldService.createHold(
        testEvent.getId(), List.of(testSeat.getId()), USER_1, 600);

    Instant hold60MinBound = Instant.now().plusSeconds(60 * 60L + 5); // 60 min + 5 s slack
    assertThat(holds.get(0).getExpiresAt()).isBefore(hold60MinBound);
  }

  // -------------------------------------------------------------------------
  // Max active holds per holder (max-active-per-holder=2 in test properties)
  // -------------------------------------------------------------------------

  @Test
  void exceedingMaxActiveHoldsPerHolderThrowsConflict() {
    // Create 2 seats so USER_1 can reach the limit
    Seat extraSeat = seatRepository.save(new Seat(testEvent, "Floor", "A", "102", new BigDecimal("25.00")));

    seatHoldService.createHold(testEvent.getId(), List.of(testSeat.getId()), USER_1, 10);
    seatHoldService.createHold(testEvent.getId(), List.of(extraSeat.getId()), USER_1, 10);

    // Third seat — must be blocked by the active-hold limit
    Seat thirdSeat = seatRepository.save(new Seat(testEvent, "Floor", "A", "103", new BigDecimal("25.00")));
    assertThatThrownBy(() -> seatHoldService.createHold(
        testEvent.getId(), List.of(thirdSeat.getId()), USER_1, 10))
        .isInstanceOf(SeatHoldConflictException.class)
        .hasMessageContaining("Hold limit reached");
  }

  @Test
  void holdLimitIsPerHolder_otherHolderCanStillHold() {
    Seat extraSeat = seatRepository.save(new Seat(testEvent, "Floor", "A", "102", new BigDecimal("25.00")));

    seatHoldService.createHold(testEvent.getId(), List.of(testSeat.getId()), USER_1, 10);
    seatHoldService.createHold(testEvent.getId(), List.of(extraSeat.getId()), USER_1, 10);

    // USER_2 is unaffected by USER_1's limit
    Seat thirdSeat = seatRepository.save(new Seat(testEvent, "Floor", "A", "103", new BigDecimal("25.00")));
    List<SeatHold> holds = seatHoldService.createHold(
        testEvent.getId(), List.of(thirdSeat.getId()), USER_2, 10);
    assertThat(holds).hasSize(1);
    assertThat(holds.get(0).getStatus()).isEqualTo(HoldStatus.ACTIVE);
  }

  // -------------------------------------------------------------------------
  // Batch locking / seat ordering
  // -------------------------------------------------------------------------

  @Test
  void batchHoldLocksSeatsInAnyInputOrder() {
    // Create two seats and request them in reverse UUID order.
    // The service must sort them internally; both must end up HELD.
    Seat seat2 = seatRepository.save(new Seat(testEvent, "Floor", "A", "102", new BigDecimal("25.00")));

    // Provide seatIds in descending order (likely reversed from UUID sort)
    UUID id1 = testSeat.getId();
    UUID id2 = seat2.getId();
    List<UUID> reversed = id1.compareTo(id2) > 0
        ? List.of(id1, id2)
        : List.of(id2, id1);

    List<SeatHold> holds = seatHoldService.createHold(
        testEvent.getId(), reversed, USER_1, 10);

    assertThat(holds).hasSize(2);
    assertThat(seatRepository.findById(id1).orElseThrow().getStatus()).isEqualTo(SeatStatus.HELD);
    assertThat(seatRepository.findById(id2).orElseThrow().getStatus()).isEqualTo(SeatStatus.HELD);
  }

  @Test
  void duplicateSeatIdsInRequestAreDeduped() {
    // Sending the same seat twice should create exactly one hold
    List<SeatHold> holds = seatHoldService.createHold(
        testEvent.getId(),
        List.of(testSeat.getId(), testSeat.getId()),
        USER_1, 10);

    assertThat(holds).hasSize(1);
  }

  // -------------------------------------------------------------------------
  // Scheduler with paging
  // -------------------------------------------------------------------------

  @Test
  void schedulerExpiresMultipleHoldsInOneBatch() {
    // Create 3 expired holds manually (scheduler page size is 500, so all fit)
    Seat seat2 = seatRepository.save(new Seat(testEvent, "Floor", "A", "102", new BigDecimal("25.00")));
    Seat seat3 = seatRepository.save(new Seat(testEvent, "Floor", "A", "103", new BigDecimal("25.00")));

    for (Seat seat : List.of(testSeat, seat2, seat3)) {
      seat.setStatus(SeatStatus.HELD);
      seatRepository.save(seat);
      seatHoldRepository.save(new SeatHold(seat, USER_1, Instant.now().minusSeconds(60)));
    }

    scheduler.expireHolds();
    readFromDatabase();

    // All 3 holds must be EXPIRED, all seats back to AVAILABLE
    List<UUID> seatIds = List.of(testSeat.getId(), seat2.getId(), seat3.getId());
    List<SeatHold> holds = seatHoldRepository.findAll().stream()
        .filter(h -> seatIds.contains(h.getSeat().getId()))
        .toList();
    assertThat(holds).hasSize(3).allMatch(h -> h.getStatus() == HoldStatus.EXPIRED);
    assertThat(seatRepository.findById(testSeat.getId()).orElseThrow().getStatus())
        .isEqualTo(SeatStatus.AVAILABLE);
    assertThat(seatRepository.findById(seat2.getId()).orElseThrow().getStatus())
        .isEqualTo(SeatStatus.AVAILABLE);
    assertThat(seatRepository.findById(seat3.getId()).orElseThrow().getStatus())
        .isEqualTo(SeatStatus.AVAILABLE);
  }

  // -------------------------------------------------------------------------
  // List holds
  // -------------------------------------------------------------------------

  @Test
  void listHoldsReturnsOnlyActiveHoldsForHolder() {
    Seat seat2 = seatRepository.save(new Seat(testEvent, "Floor", "A", "102", new BigDecimal("25.00")));

    // Two holds for USER_1 (hits the limit of 2 for test props)
    seatHoldService.createHold(testEvent.getId(), List.of(testSeat.getId()), USER_1, 10);
    seatHoldService.createHold(testEvent.getId(), List.of(seat2.getId()),    USER_1, 10);

    List<SeatHold> active = seatHoldService.listHolds(USER_1, HoldStatus.ACTIVE);
    assertThat(active).hasSize(2);
    assertThat(active).allMatch(h -> h.getStatus() == HoldStatus.ACTIVE);
    assertThat(active).allMatch(h -> h.getOwnerId().equals(USER_1));
  }

  // -------------------------------------------------------------------------
  // Ownership isolation
  // -------------------------------------------------------------------------

  @Test
  void cannotReleaseAnotherUsersHold() {
    SeatHold hold = seatHoldService.createHold(
        testEvent.getId(), List.of(testSeat.getId()), USER_1, 10).get(0);

    assertThatThrownBy(() -> seatHoldService.releaseHold(hold.getId(), USER_2))
        .isInstanceOf(SeatHoldNotFoundException.class);
  }

  @Test
  void cannotConfirmAnotherUsersHold() {
    SeatHold hold = seatHoldService.createHold(
        testEvent.getId(), List.of(testSeat.getId()), USER_1, 10).get(0);

    assertThatThrownBy(() -> seatHoldService.confirmHold(hold.getId(), USER_2))
        .isInstanceOf(SeatHoldNotFoundException.class);
  }

  @Test
  void cannotGetAnotherUsersHold() {
    SeatHold hold = seatHoldService.createHold(
        testEvent.getId(), List.of(testSeat.getId()), USER_1, 10).get(0);

    assertThatThrownBy(() -> seatHoldService.getHold(hold.getId(), USER_2))
        .isInstanceOf(SeatHoldNotFoundException.class);
  }
}
