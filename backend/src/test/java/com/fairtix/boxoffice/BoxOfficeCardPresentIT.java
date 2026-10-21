package com.fairtix.boxoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fairtix.boxoffice.application.BoxOfficeService;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * <p>Red on commit {@code RED_HASH}: the intent was created inside the
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
