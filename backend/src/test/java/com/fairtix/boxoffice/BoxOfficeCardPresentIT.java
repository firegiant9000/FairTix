package com.fairtix.boxoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fairtix.boxoffice.application.BoxOfficeService;
import com.fairtix.boxoffice.application.BoxOfficeStateException;
import com.fairtix.boxoffice.domain.BoxOfficeSale;
import com.fairtix.boxoffice.domain.BoxOfficeSession;
import com.fairtix.events.domain.Event;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

  @MockitoSpyBean private StripePaymentService stripePaymentService;

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
