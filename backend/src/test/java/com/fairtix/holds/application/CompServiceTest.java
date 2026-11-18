package com.fairtix.holds.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.common.TransactionLock;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.holds.dto.IssueCompRequest;
import com.fairtix.inventory.application.SeatHoldConflictException;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.orders.application.PurchaseCapExceededException;
import com.fairtix.orders.domain.Order;
import com.fairtix.orders.infrastructure.OrderRepository;
import com.fairtix.tickets.application.PurchaseCapGuard;
import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.domain.TicketKind;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CompServiceTest {

  private EventRepository events;
  private SeatRepository seats;
  private TicketRepository tickets;
  private UserRepository users;
  private OrderRepository orders;
  private AuditService audit;
  private TransactionLock transactionLock;
  private PurchaseCapGuard purchaseCapGuard;
  private CompService service;

  private UUID issuerId;
  private UUID eventId;
  private Event event;
  private User issuer;

  @BeforeEach
  void setUp() {
    events = mock(EventRepository.class);
    seats = mock(SeatRepository.class);
    tickets = mock(TicketRepository.class);
    users = mock(UserRepository.class);
    orders = mock(OrderRepository.class);
    audit = mock(AuditService.class);
    transactionLock = mock(TransactionLock.class);
    purchaseCapGuard = mock(PurchaseCapGuard.class);
    service = new CompService(events, seats, tickets, users, orders, audit,
        transactionLock, purchaseCapGuard);

    issuerId = UUID.randomUUID();
    eventId = UUID.randomUUID();

    event = mock(Event.class);
    when(event.getId()).thenReturn(eventId);
    when(event.getCompLimit()).thenReturn(null);
    when(event.getMaxTicketsPerUser()).thenReturn(null);
    when(events.findById(eventId)).thenReturn(Optional.of(event));

    issuer = new User();
    issuer.setId(issuerId);
    issuer.setEmail("staff@venue.test");
    when(users.findById(issuerId)).thenReturn(Optional.of(issuer));

    // Simulate JPA's @GeneratedValue: persist must assign the id before
    // returning, otherwise downstream audit logs receive null where production
    // sees a real UUID. CompService.issueComp passes order.getId() to the
    // audit call immediately after save().
    when(orders.save(any(Order.class))).thenAnswer(inv -> {
      Order o = inv.getArgument(0);
      try {
        java.lang.reflect.Field idField = Order.class.getDeclaredField("id");
        idField.setAccessible(true);
        if (idField.get(o) == null) idField.set(o, UUID.randomUUID());
      } catch (ReflectiveOperationException ignore) {
        // Fall through; the assertion will catch the regression.
      }
      return o;
    });
    when(tickets.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void issuesCompTicketsZeroPriceKindComp() {
    UUID seatId = UUID.randomUUID();
    Seat seat = mockSeat(seatId, SeatStatus.AVAILABLE);
    when(seats.findAndLockByIdIn(List.of(seatId))).thenReturn(List.of(seat));

    IssueCompRequest req = new IssueCompRequest(eventId, List.of(seatId), "Press Guest", null, "review", true);
    List<Ticket> issued = service.issueComp(req, issuerId);

    assertThat(issued).hasSize(1);
    Ticket t = issued.get(0);
    assertThat(t.getKind()).isEqualTo(TicketKind.COMP);
    assertThat(t.getPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(t.getKindReason()).isEqualTo("review");
    assertThat(t.getKindIssuedBy()).isEqualTo(issuerId);
    assertThat(t.isWillCall()).isTrue();
    verify(seat).setStatus(SeatStatus.SOLD);
    verify(audit).log(eq(issuerId), eq("COMP_ISSUED"), eq("TICKET"), any(UUID.class), anyString());
  }

  @Test
  void rejectsSeatNotAvailable() {
    UUID seatId = UUID.randomUUID();
    Seat seat = mockSeat(seatId, SeatStatus.SOLD);
    when(seats.findAndLockByIdIn(List.of(seatId))).thenReturn(List.of(seat));

    IssueCompRequest req = new IssueCompRequest(eventId, List.of(seatId), null, null, null, false);
    assertThatThrownBy(() -> service.issueComp(req, issuerId))
        .isInstanceOf(SeatHoldConflictException.class);
    verify(orders, never()).save(any());
  }

  @Test
  void enforcesPerEventCompCapInsideTransaction() {
    when(event.getCompLimit()).thenReturn(5);
    when(tickets.countByEvent_IdAndKind(eventId, TicketKind.COMP)).thenReturn(4L);

    UUID s1 = UUID.randomUUID();
    UUID s2 = UUID.randomUUID();
    IssueCompRequest req = new IssueCompRequest(eventId, List.of(s1, s2), null, null, null, false);

    assertThatThrownBy(() -> service.issueComp(req, issuerId))
        .isInstanceOf(CompLimitExceededException.class);
    verifyNoInteractions(orders);
  }

  /** The count runs under the per-event lock, taken before any seat is locked. */
  @Test
  void compLimitIsCountedUnderTheEventLock() {
    when(event.getCompLimit()).thenReturn(5);
    UUID seatId = UUID.randomUUID();
    Seat seat = mockSeat(seatId, SeatStatus.AVAILABLE);
    when(seats.findAndLockByIdIn(List.of(seatId))).thenReturn(List.of(seat));

    service.issueComp(new IssueCompRequest(eventId, List.of(seatId), null, null, null, false), issuerId);

    InOrder order = inOrder(transactionLock, tickets, seats);
    order.verify(transactionLock).acquire("comp-limit:" + eventId);
    order.verify(tickets).countByEvent_IdAndKind(eventId, TicketKind.COMP);
    order.verify(seats).findAndLockByIdIn(List.of(seatId));
  }

  /** Comps count toward the recipient's purchase cap, checked under the cap lock. */
  @Test
  void refusesACompOverTheRecipientsPurchaseCap() {
    when(event.getMaxTicketsPerUser()).thenReturn(2);
    User recipient = new User();
    recipient.setId(UUID.randomUUID());
    recipient.setEmail("guest@example.com");
    when(users.findByEmail("guest@example.com")).thenReturn(Optional.of(recipient));
    when(purchaseCapGuard.activeTickets(recipient.getId(), eventId)).thenReturn(1L);
    when(purchaseCapGuard.liveHolds(recipient.getId(), eventId)).thenReturn(1L);
    UUID seatId = UUID.randomUUID();

    assertThatThrownBy(() -> service.issueComp(
        new IssueCompRequest(eventId, List.of(seatId), null, "guest@example.com", null, false),
        issuerId))
        .isInstanceOf(PurchaseCapExceededException.class);

    InOrder order = inOrder(purchaseCapGuard);
    order.verify(purchaseCapGuard).lock(eventId, recipient.getId());
    order.verify(purchaseCapGuard).activeTickets(recipient.getId(), eventId);
    verify(seats, never()).findAndLockByIdIn(any());
    verifyNoInteractions(orders);
  }

  /**
   * A name-only comp (no email) is owned by the issuer as a stand-in; it is not
   * the issuer's purchase, so the issuer's cap is neither locked nor counted.
   */
  @Test
  void nameOnlyCompOnACappedEvent_isNotCountedAgainstTheIssuer() {
    when(event.getMaxTicketsPerUser()).thenReturn(1);
    when(purchaseCapGuard.activeTickets(any(), any())).thenReturn(5L);
    UUID seatId = UUID.randomUUID();
    Seat seat = mockSeat(seatId, SeatStatus.AVAILABLE);
    when(seats.findAndLockByIdIn(List.of(seatId))).thenReturn(List.of(seat));

    assertThat(service.issueComp(
        new IssueCompRequest(eventId, List.of(seatId), "Will Call Guest", null, null, true), issuerId))
        .hasSize(1);

    verify(purchaseCapGuard, never()).lock(any(), any());
    verify(purchaseCapGuard, never()).activeTickets(any(), any());
  }

  @Test
  void uncappedEventTakesNoLocks() {
    UUID seatId = UUID.randomUUID();
    Seat seat = mockSeat(seatId, SeatStatus.AVAILABLE);
    when(seats.findAndLockByIdIn(List.of(seatId))).thenReturn(List.of(seat));

    service.issueComp(new IssueCompRequest(eventId, List.of(seatId), null, null, null, false), issuerId);

    verifyNoInteractions(transactionLock);
    verify(purchaseCapGuard, never()).lock(any(), any());
  }

  @Test
  void resolvesRecipientByEmailIfExistingUser() {
    UUID seatId = UUID.randomUUID();
    Seat seat = mockSeat(seatId, SeatStatus.AVAILABLE);
    when(seats.findAndLockByIdIn(List.of(seatId))).thenReturn(List.of(seat));

    User recipient = new User();
    recipient.setId(UUID.randomUUID());
    recipient.setEmail("guest@example.com");
    when(users.findByEmail("guest@example.com")).thenReturn(Optional.of(recipient));

    IssueCompRequest req = new IssueCompRequest(eventId, List.of(seatId),
        "Guest", "Guest@Example.com", null, false);
    service.issueComp(req, issuerId);

    ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
    verify(orders).save(captor.capture());
    assertThat(captor.getValue().getUser()).isSameAs(recipient);
    verify(users, never()).save(any(User.class)); // existing user → no stub created
  }

  @Test
  void createsStubUserForUnknownRecipientEmail() {
    UUID seatId = UUID.randomUUID();
    Seat seat = mockSeat(seatId, SeatStatus.AVAILABLE);
    when(seats.findAndLockByIdIn(List.of(seatId))).thenReturn(List.of(seat));
    when(users.findByEmail("new@example.com")).thenReturn(Optional.empty());
    when(users.save(any(User.class))).thenAnswer(inv -> {
      User u = inv.getArgument(0);
      u.setId(UUID.randomUUID());
      return u;
    });

    IssueCompRequest req = new IssueCompRequest(eventId, List.of(seatId),
        "New Guest", "new@example.com", null, false);
    service.issueComp(req, issuerId);

    ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
    verify(users).save(userCaptor.capture());
    assertThat(userCaptor.getValue().getEmail()).isEqualTo("new@example.com");
    assertThat(userCaptor.getValue().isEmailVerified()).isFalse();
  }

  private Seat mockSeat(UUID id, SeatStatus status) {
    Seat seat = mock(Seat.class);
    when(seat.getId()).thenReturn(id);
    when(seat.getEvent()).thenReturn(event);
    when(seat.getStatus()).thenReturn(status);
    when(seat.getSection()).thenReturn("FLR");
    when(seat.getRowLabel()).thenReturn("A");
    when(seat.getSeatNumber()).thenReturn("1");
    when(seat.getPrice()).thenReturn(BigDecimal.TEN);
    return seat;
  }
}
