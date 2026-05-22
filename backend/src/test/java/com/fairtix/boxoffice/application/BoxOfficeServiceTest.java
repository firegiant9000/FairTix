package com.fairtix.boxoffice.application;

import com.fairtix.boxoffice.domain.BoxOfficeSale;
import com.fairtix.boxoffice.domain.BoxOfficeSession;
import com.fairtix.boxoffice.domain.SaleMethod;
import com.fairtix.boxoffice.domain.SessionStatus;
import com.fairtix.boxoffice.infrastructure.BoxOfficeSaleRepository;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.application.SeatHoldConflictException;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.organizations.application.OrganizationService;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.users.domain.Role;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;

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

/**
 * Phase D acceptance: cash + comp walk-up sales go through the SeatHold path,
 * sessions enforce one-open-per-staff, and reconciliation totals tie out.
 *
 * <p>Card-present (M2-10) requires a real Stripe Connect test account to
 * exercise end-to-end and is covered by the integration test that runs only
 * when {@code STRIPE_TEST_KEY} is set (see M2 entry checklist) — we focus here
 * on the deterministic parts.
 */
@SpringBootTest
@Transactional
class BoxOfficeServiceTest {

  @Autowired private BoxOfficeService boxOfficeService;
  @Autowired private OrganizationService orgService;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private TicketRepository ticketRepository;
  @Autowired private BoxOfficeSaleRepository saleRepository;

  private Organization org;
  private User staff;
  private Event event;
  private Seat seatA;
  private Seat seatB;

  @BeforeEach
  void setUp() {
    staff = newUser("staff-" + uniq() + "@fairtix.test");
    org = orgService.createOrganization("BO Test " + uniq(), "bo@test", staff.getId());
    event = new Event("Tonight " + uniq(), null, Instant.now().plusSeconds(3600), staff.getId());
    event.setOrganizationId(org.getId());
    event = eventRepository.save(event);
    seatA = seatRepository.save(new Seat(event, "GA", "A", "1", new BigDecimal("25.00")));
    seatB = seatRepository.save(new Seat(event, "GA", "A", "2", new BigDecimal("30.00")));
  }

  @Test
  void openingTwoSessionsForSameStaffIsRejected() {
    boxOfficeService.openSession(org.getId(), staff.getId(), new BigDecimal("100.00"));
    assertThatThrownBy(() ->
        boxOfficeService.openSession(org.getId(), staff.getId(), new BigDecimal("50.00")))
        .isInstanceOf(BoxOfficeStateException.class)
        .hasMessageContaining("already exists");
  }

  @Test
  void cashSaleTransitionsSeatsThroughHoldPathAndIssuesTickets() {
    BoxOfficeSession session = boxOfficeService.openSession(
        org.getId(), staff.getId(), new BigDecimal("100.00"));

    BoxOfficeSale sale = boxOfficeService.recordCashSale(
        session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seatA.getId(), seatB.getId()),
        "walkup@test", "Walk Up");

    assertThat(sale.getMethod()).isEqualTo(SaleMethod.CASH);
    assertThat(sale.getAmount()).isEqualByComparingTo(new BigDecimal("55.00"));
    assertThat(sale.getOrderId()).isNotNull();
    assertThat(seatRepository.findById(seatA.getId()).get().getStatus()).isEqualTo(SeatStatus.SOLD);
    assertThat(seatRepository.findById(seatB.getId()).get().getStatus()).isEqualTo(SeatStatus.SOLD);
    assertThat(ticketRepository.findAllByOrder_Id(sale.getOrderId())).hasSize(2);
  }

  @Test
  void compSaleRecordsZeroAmountAndStillMarksSeatsSold() {
    BoxOfficeSession session = boxOfficeService.openSession(
        org.getId(), staff.getId(), new BigDecimal("100.00"));

    BoxOfficeSale sale = boxOfficeService.recordCompSale(
        session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seatA.getId()),
        "Artist guest", null, null);

    assertThat(sale.getMethod()).isEqualTo(SaleMethod.COMP);
    assertThat(sale.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(sale.getCompReason()).isEqualTo("Artist guest");
    assertThat(seatRepository.findById(seatA.getId()).get().getStatus()).isEqualTo(SeatStatus.SOLD);
    // Tickets are issued at $0 — the COMP filter the settlement report uses keys
    // off the sale method, not ticket price (price = 0 is also a marker but the
    // method enum is the source of truth here).
    var tickets = ticketRepository.findAllByOrder_Id(sale.getOrderId());
    assertThat(tickets).hasSize(1);
    assertThat(tickets.get(0).getPrice()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void doubleSellOfSameSeatIsRejectedByHoldLayer() {
    BoxOfficeSession session = boxOfficeService.openSession(
        org.getId(), staff.getId(), new BigDecimal("100.00"));

    boxOfficeService.recordCashSale(
        session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seatA.getId()), null, null);

    // Open a second drawer for a different staff member at the same org and try
    // to sell the same seat. The shared SeatHold layer must block this.
    User staff2 = newUser("staff2-" + uniq() + "@fairtix.test");
    // promote staff2 into the org as BOX_OFFICE
    // shortcut: use OWNER permission via service since membership exists
    BoxOfficeSession session2 = boxOfficeService.openSession(
        org.getId(), staff2.getId(), new BigDecimal("0.00"));
    assertThatThrownBy(() -> boxOfficeService.recordCashSale(
        session2.getId(), org.getId(), staff2.getId(),
        event.getId(), List.of(seatA.getId()), null, null))
        .isInstanceOf(SeatHoldConflictException.class);
  }

  @Test
  void closeSessionComputesExpectedCashAndVariance() {
    BoxOfficeSession session = boxOfficeService.openSession(
        org.getId(), staff.getId(), new BigDecimal("100.00"));
    boxOfficeService.recordCashSale(
        session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seatA.getId()), null, null);

    BoxOfficeSession closed = boxOfficeService.closeSession(
        session.getId(), org.getId(), new BigDecimal("125.00"), null, staff.getId());

    assertThat(closed.getStatus()).isEqualTo(SessionStatus.CLOSED);
    assertThat(closed.getExpectedCash()).isEqualByComparingTo("125.00");
    assertThat(closed.getVariance()).isEqualByComparingTo("0.00");
    assertThat(closed.getSignedOffByUserId()).isEqualTo(staff.getId());
  }

  @Test
  void closeSessionWithLargeVarianceRequiresReason() {
    BoxOfficeSession session = boxOfficeService.openSession(
        org.getId(), staff.getId(), new BigDecimal("100.00"));

    // No sales — expected $100. Closing with $80 = -$20 variance, no reason.
    assertThatThrownBy(() ->
        boxOfficeService.closeSession(
            session.getId(), org.getId(), new BigDecimal("80.00"), null, staff.getId()))
        .isInstanceOf(BoxOfficeStateException.class)
        .hasMessageContaining("reason");

    BoxOfficeSession closed = boxOfficeService.closeSession(
        session.getId(), org.getId(), new BigDecimal("80.00"), "till short by $20", staff.getId());
    assertThat(closed.getVariance()).isEqualByComparingTo("-20.00");
    assertThat(closed.getVarianceReason()).isEqualTo("till short by $20");
  }

  @Test
  void reconciliationTotalsTieOutToSalesLedger() {
    BoxOfficeSession session = boxOfficeService.openSession(
        org.getId(), staff.getId(), new BigDecimal("50.00"));

    boxOfficeService.recordCashSale(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seatA.getId()), null, null);
    boxOfficeService.recordCompSale(session.getId(), org.getId(), staff.getId(),
        event.getId(), List.of(seatB.getId()), "press", null, null);

    BigDecimal cash = boxOfficeService.totalForMethod(session.getId(), SaleMethod.CASH);
    BigDecimal comp = boxOfficeService.totalForMethod(session.getId(), SaleMethod.COMP);
    assertThat(cash).isEqualByComparingTo("25.00");
    assertThat(comp).isEqualByComparingTo("0.00");
    assertThat(saleRepository.findAllBySessionIdOrderByCreatedAtAsc(session.getId())).hasSize(2);
  }

  private User newUser(String email) {
    User u = new User();
    u.setEmail(email);
    u.setPassword("bcrypt-placeholder");
    u.setRole(Role.USER);
    u.setEmailVerified(true);
    return userRepository.save(u);
  }

  private static String uniq() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
