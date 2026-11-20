package com.fairtix.boxoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fairtix.audit.application.AuditService;
import com.fairtix.boxoffice.application.BoxOfficeService;
import com.fairtix.boxoffice.application.BoxOfficeStateException;
import com.fairtix.boxoffice.domain.BoxOfficeSale;
import com.fairtix.boxoffice.domain.BoxOfficeSession;
import com.fairtix.events.domain.Event;
import com.fairtix.events.domain.EventNotOnSaleException;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.organizations.application.OrganizationService;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.payments.application.StripePaymentService;
import com.fairtix.support.PostgresIT;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import com.stripe.model.PaymentIntent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exposure gate (b): the card-present PaymentIntent is created after the holds and
 * the pending sale commit, with no transaction (and no seat lock) open, under a key
 * derived from the sale row.
 *
 * <p>Red on commit {@code 5da020d}: the intent was created inside the
 * {@code @Transactional} method while the {@code PESSIMISTIC_WRITE} seat locks were
 * held, with no idempotency key, and before the sale row existed.
 * {@code stripeFailure_releasesSeatsAndSale} is a guard for the compensating path.
 */
// Its own context (spies on StripePaymentService and AuditService): close it after the class
// so its pool doesn't count against the shared container's max_connections for the rest of the suite.
@DirtiesContext
@TestPropertySource(properties = "stripe.enabled=true")
class BoxOfficeCardPresentIT extends PostgresIT {

  @Autowired private BoxOfficeService boxOfficeService;
  @Autowired private OrganizationService organizationService;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private javax.sql.DataSource dataSource;

  @MockitoSpyBean private StripePaymentService stripePaymentService;
  @MockitoSpyBean private AuditService auditService;
  @MockitoSpyBean private com.fairtix.events.application.EventSaleGuard eventSaleGuard;

  private final List<String> sentKeys = new CopyOnWriteArrayList<>();
  private final List<Boolean> txActiveAtStripe = new CopyOnWriteArrayList<>();

  private User staff;
  private Organization org;
  private Event event;
  private Seat seat;
  private BoxOfficeSession session;

  @BeforeEach
  void setUp() {
    staff = new User();
    staff.setEmail("bo-staff-" + UUID.randomUUID() + "@test.com");
    staff.setPassword("$2a$10$dummyhashfortest");
    staff.setEmailVerified(true);
    staff = userRepository.save(staff);
    org = organizationService.createOrganization("BO Card " + UUID.randomUUID(), "bo@test", staff.getId());
    org.setStripeConnectAccountId("acct_test_bo");
    org.setStripeChargesEnabled(true);
    org = organizationRepository.save(org);
    event = new Event("Door Sale", null, Instant.now().plusSeconds(3600), staff.getId());
    event.setOrganizationId(org.getId());
    event = eventRepository.save(event);
    seat = seatRepository.save(new Seat(event, "GA", "A", "1", new BigDecimal("25.00")));
    session = boxOfficeService.openSession(org.getId(), staff.getId(), new BigDecimal("100.00"));
  }

  @Test
  void stripeCall_runsWithNoTransaction() {
    stubIntent("pi_test_bo_1");

    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seat.getId()), "tmr_test");

    assertThat(txActiveAtStripe).containsExactly(false);
  }

  @Test
  void stripeCall_usesSaleKey() {
    stubIntent("pi_test_bo_2");

    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seat.getId()), "tmr_test");

    UUID saleId = jdbcTemplate.queryForObject(
        "SELECT id FROM box_office_sales WHERE stripe_payment_intent_id = ?", UUID.class, "pi_test_bo_2");
    assertThat(sentKeys).containsExactly("fairtix-bo-" + saleId);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM seats WHERE id = ?", String.class, seat.getId())).isEqualTo("HELD");
  }

  @Test
  void stripeFailure_releasesSeatsAndSale() {
    doAnswer(inv -> {
      throw new RuntimeException("Failed to create Stripe card-present intent: network");
    }).when(stripePaymentService).createCardPresentPaymentIntent(anyLong(), anyString(), any(), any());

    assertThatThrownBy(() -> boxOfficeService.createCardPresentIntent(session.getId(), org.getId(),
        staff.getId(), event.getId(), List.of(seat.getId()), "tmr_test"))
        .hasMessageContaining("network");

    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM seats WHERE id = ?", String.class, seat.getId())).isEqualTo("AVAILABLE");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM seat_holds WHERE seat_id = ? AND status = 'ACTIVE'",
        Integer.class, seat.getId())).isZero();
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM box_office_sales WHERE session_id = ?", Integer.class, session.getId()))
        .isZero();
  }

  /** Item 7: the confirm-time Stripe verification runs with no transaction open either. */
  @Test
  void confirmVerification_runsWithNoTransaction() {
    stubIntent("pi_test_bo_c1");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seat.getId()), "tmr_test");
    List<Boolean> txActiveAtVerify = new CopyOnWriteArrayList<>();
    doAnswer(inv -> {
      txActiveAtVerify.add(TransactionSynchronizationManager.isActualTransactionActive());
      return true;
    }).when(stripePaymentService).verifyPaymentSucceeded(eq("pi_test_bo_c1"), eq(2500L));

    BoxOfficeSale sale = boxOfficeService.confirmCardSale(session.getId(), org.getId(),
        staff.getId(), "pi_test_bo_c1", null, null);

    assertThat(txActiveAtVerify).containsExactly(false);
    assertThat(sale.getOrderId()).isNotNull();
    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM seats WHERE id = ?", String.class, seat.getId())).isEqualTo("SOLD");
  }

  /**
   * The intent's seat hold is before the charge (the short sale-lock wait); the confirm is after
   * it (the longer wait), since giving up there leaves a charge for the refund runbook.
   */
  @Test
  void saleLockWaits_shortBeforeTheCharge_longerAfterIt() {
    stubIntent("pi_test_bo_wait");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seat.getId()), "tmr_test");
    verify(eventSaleGuard).lockOnSale(event.getId());
    verify(eventSaleGuard, times(0)).lockOnSaleAfterCharge(event.getId());
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(eq("pi_test_bo_wait"), anyLong());

    boxOfficeService.confirmCardSale(session.getId(), org.getId(), staff.getId(),
        "pi_test_bo_wait", null, null);

    verify(eventSaleGuard).lockOnSaleAfterCharge(event.getId());
    verify(eventSaleGuard, times(1)).lockOnSale(event.getId());
  }

  /** A confirmed sale is returned as is, without asking Stripe again. */
  @Test
  void confirmReplay_returnsTheSaleWithoutVerifyingAgain() {
    stubIntent("pi_test_bo_c2");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seat.getId()), "tmr_test");
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(eq("pi_test_bo_c2"), anyLong());

    BoxOfficeSale first = boxOfficeService.confirmCardSale(session.getId(), org.getId(),
        staff.getId(), "pi_test_bo_c2", null, null);
    BoxOfficeSale again = boxOfficeService.confirmCardSale(session.getId(), org.getId(),
        staff.getId(), "pi_test_bo_c2", null, null);

    assertThat(again.getOrderId()).isEqualTo(first.getOrderId());
    verify(stripePaymentService, times(1)).verifyPaymentSucceeded(eq("pi_test_bo_c2"), anyLong());
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM tickets WHERE seat_id = ?", Integer.class, seat.getId())).isEqualTo(1);
  }

  @Test
  void confirmWhenThePaymentDidNotSucceed_leavesTheSeatsHeld() {
    stubIntent("pi_test_bo_c3");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seat.getId()), "tmr_test");
    doReturn(false).when(stripePaymentService).verifyPaymentSucceeded(eq("pi_test_bo_c3"), anyLong());

    assertThatThrownBy(() -> boxOfficeService.confirmCardSale(session.getId(), org.getId(),
        staff.getId(), "pi_test_bo_c3", null, null))
        .isInstanceOf(BoxOfficeStateException.class);

    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM seats WHERE id = ?", String.class, seat.getId())).isEqualTo("HELD");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT order_id FROM box_office_sales WHERE stripe_payment_intent_id = ?",
        UUID.class, "pi_test_bo_c3")).isNull();
  }

  /**
   * Two confirms of one intent overlap: the second runs to completion while the
   * first is inside the Stripe check. Under open-in-view the first request still
   * holds the sale it read before Stripe; it must re-read the row and return the
   * sale the second completed, not try to complete it again.
   */
  @Test
  void confirmOverlappingAnotherConfirm_underOpenInView_returnsTheCompletedSale() throws Exception {
    stubIntent("pi_test_bo_c4");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seat.getId()), "tmr_test");
    AtomicInteger verifyCalls = new AtomicInteger();
    AtomicReference<BoxOfficeSale> other = new AtomicReference<>();
    doAnswer(inv -> {
      if (verifyCalls.incrementAndGet() == 1) {
        other.set(onAnotherThread(() -> boxOfficeService.confirmCardSale(session.getId(),
            org.getId(), staff.getId(), "pi_test_bo_c4", null, null)));
      }
      return true;
    }).when(stripePaymentService).verifyPaymentSucceeded(eq("pi_test_bo_c4"), anyLong());

    BoxOfficeSale sale = inOpenInView(() -> boxOfficeService.confirmCardSale(session.getId(),
        org.getId(), staff.getId(), "pi_test_bo_c4", null, null));

    assertThat(other.get().getOrderId()).isNotNull();
    assertThat(sale.getOrderId()).isEqualTo(other.get().getOrderId());
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM tickets WHERE seat_id = ?", Integer.class, seat.getId())).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payment_records WHERE transaction_id = ?", Integer.class,
        "pi_test_bo_c4")).isEqualTo(1);
  }

  /**
   * Two confirms of one intent contend for the sale's row lock: the first holds it inside its
   * completing transaction while the second waits on it. Once the first commits, the second
   * reads the completed sale and returns it: one order, one ticket, one payment record.
   */
  @Test
  void confirmWaitingOnAnotherConfirmsRowLock_returnsTheCompletedSale() throws Exception {
    stubIntent("pi_test_bo_c6");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seat.getId()), "tmr_test");
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(eq("pi_test_bo_c6"), anyLong());
    CountDownLatch insideLock = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger recordCalls = new AtomicInteger();
    doAnswer(inv -> {
      if (recordCalls.incrementAndGet() == 1) {
        insideLock.countDown();
        release.await(30, TimeUnit.SECONDS);
      }
      return inv.callRealMethod();
    }).when(stripePaymentService).recordStripePayment(anyString(), any(), any(), any(), anyString());

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<BoxOfficeSale> first = pool.submit(() -> boxOfficeService.confirmCardSale(
          session.getId(), org.getId(), staff.getId(), "pi_test_bo_c6", null, null));
      assertThat(insideLock.await(10, TimeUnit.SECONDS)).isTrue();
      Future<BoxOfficeSale> second = pool.submit(() -> boxOfficeService.confirmCardSale(
          session.getId(), org.getId(), staff.getId(), "pi_test_bo_c6", null, null));
      boolean secondWaited = waitForLockWaiters(1, Duration.ofSeconds(10));
      release.countDown();
      assertThat(secondWaited).as("the second confirm waited on the sale's row lock").isTrue();

      BoxOfficeSale a = first.get(30, TimeUnit.SECONDS);
      BoxOfficeSale b = second.get(30, TimeUnit.SECONDS);
      assertThat(a.getOrderId()).isNotNull();
      assertThat(b.getOrderId()).isEqualTo(a.getOrderId());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
    assertThat(recordCalls.get()).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM tickets WHERE seat_id = ?", Integer.class, seat.getId())).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payment_records WHERE transaction_id = ?", Integer.class,
        "pi_test_bo_c6")).isEqualTo(1);
  }

  private boolean waitForLockWaiters(int waiters, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      Integer n = jdbcTemplate.queryForObject(
          "SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
              + " AND datname = current_database()", Integer.class);
      if (n != null && n >= waiters) {
        return true;
      }
      Thread.sleep(50);
    }
    return false;
  }

  /** A session closed while the confirm was talking to Stripe is seen as closed. */
  @Test
  void confirmAfterTheSessionClosedDuringTheStripeCheck_underOpenInView_isRefused() {
    stubIntent("pi_test_bo_c5");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seat.getId()), "tmr_test");
    doAnswer(inv -> {
      onAnotherThread(() -> boxOfficeService.closeSession(session.getId(), org.getId(),
          new BigDecimal("100.00"), null, staff.getId()));
      return true;
    }).when(stripePaymentService).verifyPaymentSucceeded(eq("pi_test_bo_c5"), anyLong());

    assertThatThrownBy(() -> inOpenInView(() -> boxOfficeService.confirmCardSale(session.getId(),
        org.getId(), staff.getId(), "pi_test_bo_c5", null, null)))
        .isInstanceOf(BoxOfficeStateException.class)
        .hasMessageContaining("Session is closed");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM seats WHERE id = ?", String.class, seat.getId())).isEqualTo("HELD");
    assertNoOrderAndNotCompletedRow("pi_test_bo_c5", "BoxOfficeStateException");
  }

  /**
   * The customer was charged, then the held seats stopped matching the sale (here the hold
   * was released while the confirm talked to Stripe). The confirm is refused, and the
   * charge goes on record for the runbook.
   */
  @Test
  void confirmAfterTheHoldsChangedDuringTheStripeCheck_isRefusedAndRecorded() {
    stubIntent("pi_test_bo_c7");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seat.getId()), "tmr_test");
    doAnswer(inv -> {
      jdbcTemplate.update("UPDATE seat_holds SET status = 'RELEASED' WHERE seat_id = ?",
          seat.getId());
      return true;
    }).when(stripePaymentService).verifyPaymentSucceeded(eq("pi_test_bo_c7"), anyLong());

    assertThatThrownBy(() -> boxOfficeService.confirmCardSale(session.getId(), org.getId(),
        staff.getId(), "pi_test_bo_c7", null, null))
        .isInstanceOf(BoxOfficeStateException.class)
        .hasMessageContaining("Held seats no longer match");
    assertNoOrderAndNotCompletedRow("pi_test_bo_c7", "BoxOfficeStateException");
  }

  /**
   * Item 3: one staff member, two card sales pending at once on one event. Each confirm
   * completes its own sale's seats (red before V53: the confirm took every ACTIVE hold of the
   * staff member on the event, so the seat count no longer matched).
   */
  @Test
  void twoPendingCardSales_bySameStaffOnOneEvent_eachConfirmWithTheirOwnSeats() {
    Seat second = seatRepository.save(new Seat(event, "GA", "A", "2", new BigDecimal("30.00")));
    stubIntent("pi_test_bo_two_a");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seat.getId()), "tmr_test");
    stubIntent("pi_test_bo_two_b");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(second.getId()), "tmr_test");
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong());

    // The later sale first: an "all my holds" match would hand it both seats.
    BoxOfficeSale b = boxOfficeService.confirmCardSale(session.getId(), org.getId(),
        staff.getId(), "pi_test_bo_two_b", null, null);
    BoxOfficeSale a = boxOfficeService.confirmCardSale(session.getId(), org.getId(),
        staff.getId(), "pi_test_bo_two_a", null, null);

    assertThat(ticketSeats(a.getOrderId())).containsExactly(seat.getId());
    assertThat(ticketSeats(b.getOrderId())).containsExactly(second.getId());
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_logs"
        + " WHERE action = 'BOX_OFFICE_CARD_NOT_COMPLETED'", Integer.class)).isZero();
  }

  /** The intent records which holds belong to its sale. */
  @Test
  void cardIntent_linksTheSaleToItsHolds() {
    stubIntent("pi_test_bo_link");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seat.getId()), "tmr_test");

    assertThat(jdbcTemplate.queryForList(
        "SELECT h.seat_id FROM box_office_sale_holds l"
            + " JOIN box_office_sales s ON s.id = l.sale_id"
            + " JOIN seat_holds h ON h.id = l.hold_id"
            + " WHERE s.stripe_payment_intent_id = ?", UUID.class, "pi_test_bo_link"))
        .containsExactly(seat.getId());
  }

  /**
   * A sale pending when V53 was applied has no links: its confirm falls back to matching the
   * staff member's ACTIVE holds on the event, as before.
   */
  @Test
  void pendingSaleFromBeforeTheLinks_confirmsByTheOldMatch() {
    stubIntent("pi_test_bo_legacy");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seat.getId()), "tmr_test");
    jdbcTemplate.update("DELETE FROM box_office_sale_holds");
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(eq("pi_test_bo_legacy"), anyLong());

    BoxOfficeSale sale = boxOfficeService.confirmCardSale(session.getId(), org.getId(),
        staff.getId(), "pi_test_bo_legacy", null, null);

    assertThat(ticketSeats(sale.getOrderId())).containsExactly(seat.getId());
  }

  /**
   * Closing the drawer flags its pending card sales abandoned (the reader may still have
   * charged one, so the check keeps listing them); a completed sale is not flagged.
   */
  @Test
  void closingTheSession_flagsPendingCardSalesAbandoned() {
    Seat second = seatRepository.save(new Seat(event, "GA", "A", "2", new BigDecimal("30.00")));
    stubIntent("pi_test_bo_done");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seat.getId()), "tmr_test");
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(eq("pi_test_bo_done"), anyLong());
    boxOfficeService.confirmCardSale(session.getId(), org.getId(), staff.getId(),
        "pi_test_bo_done", null, null);
    stubIntent("pi_test_bo_left");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(second.getId()), "tmr_test");

    boxOfficeService.closeSession(session.getId(), org.getId(), new BigDecimal("100.00"), null,
        staff.getId());

    assertThat(abandonedAt("pi_test_bo_left")).isNotNull();
    assertThat(abandonedAt("pi_test_bo_done")).isNull();
    assertThat(jdbcTemplate.queryForObject("SELECT details FROM audit_logs"
        + " WHERE action = 'BOX_OFFICE_SESSION_CLOSED'", String.class))
        .contains("abandonedCardSales=1");
  }

  /**
   * A confirm completing a sale while the drawer closes: the close waits on the sale row, then
   * sees the order and leaves the sale alone. It never writes back the order id it read
   * before the wait.
   */
  @Test
  void closingTheSession_whileAConfirmCompletesASale_keepsTheOrder() throws Exception {
    stubIntent("pi_test_bo_race");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seat.getId()), "tmr_test");
    UUID saleId = jdbcTemplate.queryForObject(
        "SELECT id FROM box_office_sales WHERE stripe_payment_intent_id = 'pi_test_bo_race'",
        UUID.class);
    UUID orderId = UUID.randomUUID();
    jdbcTemplate.update("INSERT INTO orders (id, user_id, status, total_amount, currency,"
        + " created_at) VALUES (?, ?, 'COMPLETED', 25.00, 'USD', now())", orderId, staff.getId());

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (java.sql.Connection confirm = dataSource.getConnection()) {
      confirm.setAutoCommit(false);
      try (java.sql.PreparedStatement ps = confirm.prepareStatement(
          "UPDATE box_office_sales SET order_id = ? WHERE id = ?")) {
        ps.setObject(1, orderId);
        ps.setObject(2, saleId);
        ps.executeUpdate();
      }
      Future<?> close = pool.submit(() -> boxOfficeService.closeSession(session.getId(),
          org.getId(), new BigDecimal("100.00"), null, staff.getId()));
      boolean closeWaited = waitForLockWaiters(1, Duration.ofSeconds(10));
      confirm.commit();
      assertThat(closeWaited).as("the close waited on the sale row").isTrue();
      close.get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    assertThat(jdbcTemplate.queryForObject(
        "SELECT order_id FROM box_office_sales WHERE id = ?", UUID.class, saleId)).isEqualTo(orderId);
    assertThat(abandonedAt("pi_test_bo_race")).isNull();
  }

  /** A repeated seat id holds and charges the seat once, and the paid sale still confirms. */
  @Test
  void cardIntentWithARepeatedSeat_countsItOnce_andConfirms() {
    stubIntent("pi_test_bo_dup");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seat.getId(), seat.getId()), "tmr_test");
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(eq("pi_test_bo_dup"), eq(2500L));

    BoxOfficeSale sale = boxOfficeService.confirmCardSale(session.getId(), org.getId(),
        staff.getId(), "pi_test_bo_dup", null, null);

    assertThat(sale.getSeatCount()).isEqualTo(1);
    assertThat(ticketSeats(sale.getOrderId())).containsExactly(seat.getId());
  }

  /**
   * The drawer closed while the confirm talked to Stripe, after the confirm had read the
   * session as open (here: the close's flag is on the sale row when the confirm locks it).
   * The confirm is refused and the charge goes on record.
   */
  @Test
  void confirmOfASaleAbandonedDuringTheStripeCheck_isRefusedAndRecorded() {
    stubIntent("pi_test_bo_abandoned");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seat.getId()), "tmr_test");
    doAnswer(inv -> {
      jdbcTemplate.update("UPDATE box_office_sales SET abandoned_at = now()"
          + " WHERE stripe_payment_intent_id = 'pi_test_bo_abandoned'");
      return true;
    }).when(stripePaymentService).verifyPaymentSucceeded(eq("pi_test_bo_abandoned"), anyLong());

    assertThatThrownBy(() -> boxOfficeService.confirmCardSale(session.getId(), org.getId(),
        staff.getId(), "pi_test_bo_abandoned", null, null))
        .isInstanceOf(BoxOfficeStateException.class)
        .hasMessageContaining("Session is closed");
    assertNoOrderAndNotCompletedRow("pi_test_bo_abandoned", "BoxOfficeStateException");
  }

  /** At deploy time: an unlinked sale's old match leaves out holds a linked sale owns. */
  @Test
  void unlinkedSale_nextToALinkedOne_takesOnlyItsOwnSeats() {
    Seat second = seatRepository.save(new Seat(event, "GA", "A", "2", new BigDecimal("30.00")));
    stubIntent("pi_test_bo_old");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seat.getId()), "tmr_test");
    jdbcTemplate.update("DELETE FROM box_office_sale_holds");
    stubIntent("pi_test_bo_new");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(second.getId()), "tmr_test");
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong());

    BoxOfficeSale old = boxOfficeService.confirmCardSale(session.getId(), org.getId(),
        staff.getId(), "pi_test_bo_old", null, null);
    BoxOfficeSale linked = boxOfficeService.confirmCardSale(session.getId(), org.getId(),
        staff.getId(), "pi_test_bo_new", null, null);

    assertThat(ticketSeats(old.getOrderId())).containsExactly(seat.getId());
    assertThat(ticketSeats(linked.getOrderId())).containsExactly(second.getId());
  }

  private List<UUID> ticketSeats(UUID orderId) {
    return jdbcTemplate.queryForList(
        "SELECT seat_id FROM tickets WHERE order_id = ?", UUID.class, orderId);
  }

  private java.sql.Timestamp abandonedAt(String intentId) {
    return jdbcTemplate.queryForObject(
        "SELECT abandoned_at FROM box_office_sales WHERE stripe_payment_intent_id = ?",
        java.sql.Timestamp.class, intentId);
  }

  /** A cancelled event sells nothing at the door either. */
  @Test
  void cashSale_onACancelledEvent_isRefused() {
    jdbcTemplate.update("UPDATE events SET status = 'CANCELLED' WHERE id = ?", event.getId());

    assertThatThrownBy(() -> boxOfficeService.recordCashSale(session.getId(), org.getId(),
        staff.getId(), event.getId(), List.of(seat.getId()), null, null))
        .isInstanceOf(EventNotOnSaleException.class);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tickets", Integer.class)).isZero();
  }

  @Test
  void cardIntent_onACancelledEvent_isRefusedBeforeStripe() {
    stubIntent("pi_test_bo_closed");
    jdbcTemplate.update("UPDATE events SET status = 'CANCELLED' WHERE id = ?", event.getId());

    assertThatThrownBy(() -> boxOfficeService.createCardPresentIntent(session.getId(), org.getId(),
        staff.getId(), event.getId(), List.of(seat.getId()), "tmr_test"))
        .isInstanceOf(EventNotOnSaleException.class);
    assertThat(txActiveAtStripe).isEmpty();
  }

  /**
   * The event is cancelled while the reader takes the card: the charge has succeeded, the
   * sale is refused, and the charge goes on record for the runbook.
   */
  @Test
  void confirmAfterTheEventWasCancelledDuringTheStripeCheck_isRefusedAndRecorded() {
    stubIntent("pi_test_bo_c11");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seat.getId()), "tmr_test");
    doAnswer(inv -> {
      jdbcTemplate.update("UPDATE events SET status = 'CANCELLED' WHERE id = ?", event.getId());
      return true;
    }).when(stripePaymentService).verifyPaymentSucceeded(eq("pi_test_bo_c11"), anyLong());

    assertThatThrownBy(() -> boxOfficeService.confirmCardSale(session.getId(), org.getId(),
        staff.getId(), "pi_test_bo_c11", null, null))
        .isInstanceOf(EventNotOnSaleException.class);
    assertNoOrderAndNotCompletedRow("pi_test_bo_c11", "EventNotOnSaleException");
  }

  /**
   * A retry whose completing transaction fails after another confirm already completed the
   * sale (here: completed, then the session closed) is refused, but the charge has an order,
   * so it writes no not-completed row.
   */
  @Test
  void confirmRefusedAfterAnotherConfirmCompletedTheSale_writesNoNotCompletedRow() {
    stubIntent("pi_test_bo_c8");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seat.getId()), "tmr_test");
    AtomicInteger verifyCalls = new AtomicInteger();
    doAnswer(inv -> {
      if (verifyCalls.incrementAndGet() == 1) {
        onAnotherThread(() -> boxOfficeService.confirmCardSale(session.getId(),
            org.getId(), staff.getId(), "pi_test_bo_c8", null, null));
        onAnotherThread(() -> boxOfficeService.closeSession(session.getId(), org.getId(),
            new BigDecimal("100.00"), null, staff.getId()));
      }
      return true;
    }).when(stripePaymentService).verifyPaymentSucceeded(eq("pi_test_bo_c8"), anyLong());

    assertThatThrownBy(() -> inOpenInView(() -> boxOfficeService.confirmCardSale(session.getId(),
        org.getId(), staff.getId(), "pi_test_bo_c8", null, null)))
        .isInstanceOf(BoxOfficeStateException.class)
        .hasMessageContaining("Session is closed");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT order_id FROM box_office_sales WHERE stripe_payment_intent_id = ?",
        UUID.class, "pi_test_bo_c8")).isNotNull();
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM audit_logs WHERE action = 'BOX_OFFICE_CARD_NOT_COMPLETED'",
        Integer.class)).isZero();
  }

  /**
   * The not-completed row can't be written either (say the database is down): the caller
   * still gets the original error, with the audit failure attached, not the audit failure.
   */
  @Test
  void confirmRefused_andTheAuditFails_rethrowsTheOriginalError() {
    stubIntent("pi_test_bo_c9");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seat.getId()), "tmr_test");
    doAnswer(inv -> {
      jdbcTemplate.update("UPDATE seat_holds SET status = 'RELEASED' WHERE seat_id = ?",
          seat.getId());
      return true;
    }).when(stripePaymentService).verifyPaymentSucceeded(eq("pi_test_bo_c9"), anyLong());
    RuntimeException auditDown = new RuntimeException("audit down");
    doThrow(auditDown).when(auditService).log(any(), eq("BOX_OFFICE_CARD_NOT_COMPLETED"),
        anyString(), any(), anyString());

    assertThatThrownBy(() -> boxOfficeService.confirmCardSale(session.getId(), org.getId(),
        staff.getId(), "pi_test_bo_c9", null, null))
        .isInstanceOf(BoxOfficeStateException.class)
        .hasMessageContaining("Held seats no longer match")
        .satisfies(ex -> assertThat(ex.getSuppressed()).contains(auditDown));
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM audit_logs WHERE action = 'BOX_OFFICE_CARD_NOT_COMPLETED'",
        Integer.class)).isZero();
  }

  /**
   * The completing transaction fails after it wrote BOX_OFFICE_CARD_SALE: that row rolls back
   * with the sale, so the record shows only the not-completed row.
   */
  @Test
  void confirmRolledBackAfterTheSaleRow_leavesOnlyTheNotCompletedRow() {
    stubIntent("pi_test_bo_c10");
    boxOfficeService.createCardPresentIntent(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seat.getId()), "tmr_test");
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(eq("pi_test_bo_c10"), anyLong());
    // Stubbed inside a transaction: the spy wraps the MANDATORY transactional proxy.
    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
        doAnswer(inv -> {
          inv.callRealMethod();
          throw new IllegalStateException("commit-time failure");
        }).when(auditService).logInCurrentTransaction(any(), eq("BOX_OFFICE_CARD_SALE"),
            anyString(), any(), anyString()));

    assertThatThrownBy(() -> boxOfficeService.confirmCardSale(session.getId(), org.getId(),
        staff.getId(), "pi_test_bo_c10", null, null))
        .hasMessageContaining("commit-time failure");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM audit_logs WHERE action = 'BOX_OFFICE_CARD_SALE'", Integer.class))
        .isZero();
    assertNoOrderAndNotCompletedRow("pi_test_bo_c10", "IllegalStateException");
  }

  /** The verified charge has no order, and one not-completed audit row names it. */
  private void assertNoOrderAndNotCompletedRow(String intentId, String errorClass) {
    UUID saleId = jdbcTemplate.queryForObject(
        "SELECT id FROM box_office_sales WHERE stripe_payment_intent_id = ?", UUID.class, intentId);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT order_id FROM box_office_sales WHERE id = ?", UUID.class, saleId)).isNull();
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM orders", Integer.class)).isZero();
    List<String> details = jdbcTemplate.queryForList(
        "SELECT details FROM audit_logs WHERE action = 'BOX_OFFICE_CARD_NOT_COMPLETED'"
            + " AND resource_type = 'BOX_OFFICE_SALE' AND resource_id = ?", String.class, saleId);
    assertThat(details).hasSize(1);
    assertThat(details.get(0)).contains("intent=" + intentId, "eventId=" + event.getId(),
        "error=" + errorClass);
  }

  /** Binds one EntityManager for the call, as OpenEntityManagerInViewInterceptor does per request. */
  private <T> T inOpenInView(Supplier<T> call) {
    EntityManager em = entityManagerFactory.createEntityManager();
    TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(em));
    try {
      return call.get();
    } finally {
      TransactionSynchronizationManager.unbindResource(entityManagerFactory);
      em.close();
    }
  }

  private static <T> T onAnotherThread(Supplier<T> call) throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      return executor.submit(call::get).get(30, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
  }

  private void stubIntent(String intentId) {
    doAnswer(inv -> {
      sentKeys.add(inv.getArgument(3));
      txActiveAtStripe.add(TransactionSynchronizationManager.isActualTransactionActive());
      PaymentIntent intent = mock(PaymentIntent.class);
      when(intent.getId()).thenReturn(intentId);
      return intent;
    }).when(stripePaymentService).createCardPresentPaymentIntent(anyLong(), anyString(), any(), any());
  }
}
