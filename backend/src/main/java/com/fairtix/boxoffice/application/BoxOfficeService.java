package com.fairtix.boxoffice.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.boxoffice.domain.BoxOfficeSale;
import com.fairtix.boxoffice.domain.BoxOfficeSession;
import com.fairtix.boxoffice.domain.SaleMethod;
import com.fairtix.boxoffice.domain.SessionStatus;
import com.fairtix.boxoffice.infrastructure.BoxOfficeSaleRepository;
import com.fairtix.boxoffice.infrastructure.BoxOfficeSessionRepository;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.application.SeatHoldConflictException;
import com.fairtix.inventory.domain.HoldStatus;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatHoldRepository;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.orders.domain.Order;
import com.fairtix.orders.domain.OrderStatus;
import com.fairtix.orders.infrastructure.OrderRepository;
import com.fairtix.organizations.application.OrgSalesCapService;
import com.fairtix.organizations.application.OrganizationService;
import com.fairtix.organizations.domain.OrgPermission;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.domain.Plan;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.payments.application.StripePaymentService;
import com.fairtix.payments.application.StripePaymentService.ConnectContext;
import com.fairtix.payments.domain.PaymentRecord;
import com.fairtix.payments.domain.PaymentStatus;
import com.fairtix.payments.infrastructure.PaymentRecordRepository;
import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Walk-up box-office sales for tablets at the door.
 *
 * <p>Every sale path goes through the same seat/hold lifecycle the online flow
 * uses ({@link SeatHold} → BOOKED → SOLD). The plan calls this out explicitly:
 * BO must not write tickets that skip the hold layer, even at the door, or two
 * staff at the same drawer can double-sell a GA seat. Hold TTL is 1 minute,
 * which is enough to recover from a card decline without leaving stale holds.
 *
 * <p>Tickets are owned by the staff user, with the walk-up customer's email and
 * name recorded on the {@link BoxOfficeSale} for the receipt and the will-call
 * list. Lightweight customer accounts (and the rest of Phase E's comp/hold
 * model) land with M2-12; this layer is intentionally narrow.
 */
@Service
public class BoxOfficeService {

  static final int HOLD_DURATION_MINUTES = 1;

  private final BoxOfficeSessionRepository sessions;
  private final BoxOfficeSaleRepository sales;
  private final SeatRepository seatRepository;
  private final SeatHoldRepository seatHoldRepository;
  private final EventRepository eventRepository;
  private final OrderRepository orderRepository;
  private final TicketRepository ticketRepository;
  private final UserRepository userRepository;
  private final OrganizationRepository organizationRepository;
  private final OrganizationService organizationService;
  private final PaymentRecordRepository paymentRecordRepository;
  private final StripePaymentService stripePaymentService;
  private final OrgSalesCapService salesCapService;
  private final AuditService auditService;

  public BoxOfficeService(BoxOfficeSessionRepository sessions,
                          BoxOfficeSaleRepository sales,
                          SeatRepository seatRepository,
                          SeatHoldRepository seatHoldRepository,
                          EventRepository eventRepository,
                          OrderRepository orderRepository,
                          TicketRepository ticketRepository,
                          UserRepository userRepository,
                          OrganizationRepository organizationRepository,
                          OrganizationService organizationService,
                          PaymentRecordRepository paymentRecordRepository,
                          StripePaymentService stripePaymentService,
                          OrgSalesCapService salesCapService,
                          AuditService auditService) {
    this.sessions = sessions;
    this.sales = sales;
    this.seatRepository = seatRepository;
    this.seatHoldRepository = seatHoldRepository;
    this.eventRepository = eventRepository;
    this.orderRepository = orderRepository;
    this.ticketRepository = ticketRepository;
    this.userRepository = userRepository;
    this.organizationRepository = organizationRepository;
    this.organizationService = organizationService;
    this.paymentRecordRepository = paymentRecordRepository;
    this.stripePaymentService = stripePaymentService;
    this.salesCapService = salesCapService;
    this.auditService = auditService;
  }

  // ---- Session lifecycle ----

  @Transactional
  public BoxOfficeSession openSession(UUID organizationId, UUID staffUserId, BigDecimal openingCash) {
    sessions.findFirstByOrganizationIdAndStaffUserIdAndStatus(organizationId, staffUserId, SessionStatus.OPEN)
        .ifPresent(s -> { throw new BoxOfficeStateException(
            "An open box-office session already exists for this staff member: " + s.getId()); });
    BoxOfficeSession session = sessions.save(new BoxOfficeSession(organizationId, staffUserId, openingCash));
    auditService.log(staffUserId, "BOX_OFFICE_SESSION_OPENED", "BOX_OFFICE_SESSION", session.getId(),
        "orgId=" + organizationId + " openingCash=" + openingCash);
    return session;
  }

  public Optional<BoxOfficeSession> findActiveSession(UUID organizationId, UUID staffUserId) {
    return sessions.findFirstByOrganizationIdAndStaffUserIdAndStatus(
        organizationId, staffUserId, SessionStatus.OPEN);
  }

  public BoxOfficeSession requireSession(UUID sessionId, UUID organizationId) {
    BoxOfficeSession s = sessions.findById(sessionId)
        .orElseThrow(() -> new BoxOfficeSessionNotFoundException("Session not found: " + sessionId));
    if (!s.getOrganizationId().equals(organizationId)) {
      throw new BoxOfficeSessionNotFoundException("Session not found: " + sessionId);
    }
    return s;
  }

  /**
   * Closes a session and computes the cash variance. Manager sign-off is the
   * acting user; if it is the same person who opened the drawer they must hold
   * BOX_OFFICE_SELL (handled at the controller), but a meaningful variance
   * (>$5) should be reviewed by an OWNER/MANAGER. Per the plan we surface the
   * variance and require a reason — the policy on who can sign off is enforced
   * at the controller via {@code @OrgScoped}.
   */
  @Transactional
  public BoxOfficeSession closeSession(UUID sessionId, UUID organizationId,
                                       BigDecimal closingCash, String varianceReason,
                                       UUID signerUserId) {
    BoxOfficeSession session = requireSession(sessionId, organizationId);
    if (session.getStatus() != SessionStatus.OPEN) {
      throw new BoxOfficeStateException("Session is already closed");
    }
    BigDecimal cashSalesTotal = totalForMethod(sessionId, SaleMethod.CASH);
    BigDecimal expected = session.getOpeningCash().add(cashSalesTotal);
    BigDecimal variance = closingCash.subtract(expected);

    // A variance over $5 (positive or negative) must come with a written reason.
    // Below-threshold drift is normal change-making noise.
    if (variance.abs().compareTo(new BigDecimal("5.00")) > 0
        && (varianceReason == null || varianceReason.isBlank())) {
      throw new BoxOfficeStateException(
          "Variance of " + variance + " requires a written reason");
    }

    // OWNER/MANAGER must sign off if the signer is not the staff user themselves
    // and the variance is significant. Permission check is enforced by the
    // OrgScoped annotation at the controller; we only need to record who.
    session.close(closingCash, expected, variance,
        varianceReason == null ? "" : varianceReason.trim(), signerUserId);
    sessions.save(session);
    auditService.log(signerUserId, "BOX_OFFICE_SESSION_CLOSED", "BOX_OFFICE_SESSION", session.getId(),
        "orgId=" + organizationId + " closingCash=" + closingCash + " expected=" + expected
            + " variance=" + variance + (varianceReason == null ? "" : " reason=" + varianceReason));
    return session;
  }

  // ---- Sales ----

  @Transactional
  public BoxOfficeSale recordCashSale(UUID sessionId, UUID organizationId, UUID staffUserId,
                                      UUID eventId, List<UUID> seatIds,
                                      String customerEmail, String customerName) {
    BoxOfficeSession session = requireOpenSession(sessionId, organizationId, staffUserId);
    BigDecimal amount = sellSeats(eventId, seatIds, staffUserId, organizationId);
    long amountCents = amount.multiply(BigDecimal.valueOf(100)).longValueExact();
    salesCapService.checkCanCharge(organizationId, amountCents);
    Order order = createOrder(staffUserId, seatIds, amount, OrderStatus.COMPLETED);
    issueTicketsForSeats(order, seatIds);
    recordCashPaymentRecord(order, staffUserId, amount);
    BoxOfficeSale sale = sales.save(new BoxOfficeSale(
        session.getId(), organizationId, eventId, order.getId(),
        SaleMethod.CASH, amount, seatIds.size(),
        normalize(customerEmail), normalize(customerName), null, null, null, staffUserId));
    salesCapService.recordSale(organizationId, amountCents,
        OrgSalesCapService.CHANNEL_BOX_OFFICE, sale.getId().toString());
    auditService.log(staffUserId, "BOX_OFFICE_CASH_SALE", "BOX_OFFICE_SALE", sale.getId(),
        "orgId=" + organizationId + " eventId=" + eventId + " seats=" + seatIds.size()
            + " amount=" + amount + " orderId=" + order.getId());
    return sale;
  }

  @Transactional
  public BoxOfficeSale recordCompSale(UUID sessionId, UUID organizationId, UUID staffUserId,
                                      UUID eventId, List<UUID> seatIds, String reason,
                                      String customerEmail, String customerName) {
    BoxOfficeSession session = requireOpenSession(sessionId, organizationId, staffUserId);
    // Lock + transition through the same path; the only difference is the order
    // total is zero (we discard the seat-derived total).
    sellSeats(eventId, seatIds, staffUserId, organizationId);
    Order order = createOrder(staffUserId, seatIds, BigDecimal.ZERO, OrderStatus.COMPLETED);
    issueTicketsForSeats(order, seatIds, BigDecimal.ZERO);
    BoxOfficeSale sale = sales.save(new BoxOfficeSale(
        session.getId(), organizationId, eventId, order.getId(),
        SaleMethod.COMP, BigDecimal.ZERO, seatIds.size(),
        normalize(customerEmail), normalize(customerName), reason.trim(), null, null, staffUserId));
    auditService.log(staffUserId, "BOX_OFFICE_COMP_ISSUED", "BOX_OFFICE_SALE", sale.getId(),
        "orgId=" + organizationId + " eventId=" + eventId + " seats=" + seatIds.size()
            + " reason=" + reason);
    return sale;
  }

  /**
   * Creates a card-present PaymentIntent through Stripe Connect. Seats are held
   * for {@value #HOLD_DURATION_MINUTES} minute so the customer has time to tap,
   * dip, or swipe. The reader confirms the intent client-side; the frontend
   * then calls {@link #confirmCardSale}.
   */
  @Transactional
  public CardPresentIntentResult createCardPresentIntent(UUID sessionId, UUID organizationId,
      UUID staffUserId, UUID eventId, List<UUID> seatIds, String terminalReaderId) {
    BoxOfficeSession session = requireOpenSession(sessionId, organizationId, staffUserId);
    if (!stripePaymentService.isStripeEnabled()) {
      throw new BoxOfficeStateException("Stripe is not enabled in this environment");
    }
    Organization org = organizationRepository.findById(organizationId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
    if (org.getStripeConnectAccountId() == null || !org.isStripeChargesEnabled()) {
      throw new BoxOfficeStateException(
          "Organization must complete Stripe Connect onboarding before taking card payments");
    }

    BigDecimal amount = holdSeats(eventId, seatIds, staffUserId, organizationId);
    long amountCents = amount.multiply(BigDecimal.valueOf(100)).longValueExact();
    salesCapService.checkCanCharge(organizationId, amountCents);
    Plan plan = org.getPlan() == null ? Plan.FREE : org.getPlan();
    long feeCents = (amountCents * plan.getPlatformFeeBps()) / 10_000L;
    ConnectContext connect = new ConnectContext(
        org.getId().toString(), org.getStripeConnectAccountId(), feeCents, org.getName());

    com.stripe.model.PaymentIntent intent =
        stripePaymentService.createCardPresentPaymentIntent(amountCents, "usd", connect);

    // Pre-create a PENDING sale row so we can correlate when the reader confirms.
    sales.save(new BoxOfficeSale(
        session.getId(), organizationId, eventId, null,
        SaleMethod.CARD, amount, seatIds.size(),
        null, null, null, intent.getId(), terminalReaderId, staffUserId));
    return new CardPresentIntentResult(intent, org.getStripeConnectAccountId(), feeCents);
  }

  /**
   * Confirms a card-present sale once the reader has processed the payment.
   * Verifies the intent against Stripe before transitioning seats SOLD; if
   * Stripe says the intent did not succeed we leave seats BOOKED so the user
   * can retry on the reader (or the staff member can release).
   */
  @Transactional
  public BoxOfficeSale confirmCardSale(UUID sessionId, UUID organizationId, UUID staffUserId,
                                       String paymentIntentId,
                                       String customerEmail, String customerName) {
    requireOpenSession(sessionId, organizationId, staffUserId);
    BoxOfficeSale pending = sales.findByStripePaymentIntentId(paymentIntentId)
        .orElseThrow(() -> new ResourceNotFoundException(
            "No pending box-office sale found for intent " + paymentIntentId));
    if (!pending.getSessionId().equals(sessionId)
        || !pending.getOrganizationId().equals(organizationId)) {
      throw new AccessDeniedException("Sale does not belong to this session");
    }
    if (pending.getOrderId() != null) {
      // Already confirmed — return existing
      return pending;
    }
    long expectedAmountCents = pending.getAmount()
        .multiply(BigDecimal.valueOf(100)).longValueExact();
    if (!stripePaymentService.verifyPaymentSucceeded(paymentIntentId, expectedAmountCents)) {
      throw new BoxOfficeStateException("Card-present payment did not succeed: " + paymentIntentId);
    }

    UUID eventId = pending.getEventId();
    List<UUID> seatIds = seatHoldRepository.findAllByOwnerIdAndStatus(staffUserId, HoldStatus.ACTIVE)
        .stream()
        .filter(h -> h.getSeat().getEvent().getId().equals(eventId))
        .map(h -> h.getSeat().getId())
        .toList();
    if (seatIds.size() != pending.getSeatCount()) {
      throw new BoxOfficeStateException(
          "Held seats no longer match the intent — release and retry");
    }
    confirmHolds(seatIds, staffUserId);
    transitionToSold(seatIds, staffUserId);
    Order order = createOrder(staffUserId, seatIds, pending.getAmount(), OrderStatus.COMPLETED);
    issueTicketsForSeats(order, seatIds);
    stripePaymentService.recordStripePayment(paymentIntentId, order.getId(), staffUserId,
        pending.getAmount(), "USD");

    pending.completeCard(order.getId(), normalize(customerEmail), normalize(customerName));
    BoxOfficeSale completed = sales.save(pending);
    salesCapService.recordSale(organizationId, expectedAmountCents,
        OrgSalesCapService.CHANNEL_BOX_OFFICE, completed.getId().toString());
    auditService.log(staffUserId, "BOX_OFFICE_CARD_SALE", "BOX_OFFICE_SALE", completed.getId(),
        "orgId=" + organizationId + " eventId=" + eventId + " seats=" + pending.getSeatCount()
            + " amount=" + pending.getAmount() + " intent=" + paymentIntentId);
    return completed;
  }

  // ---- Reporting ----

  public List<BoxOfficeSale> listSales(UUID sessionId) {
    return sales.findAllBySessionIdOrderByCreatedAtAsc(sessionId);
  }

  public BigDecimal totalForMethod(UUID sessionId, SaleMethod method) {
    return sales.findAllBySessionIdOrderByCreatedAtAsc(sessionId).stream()
        .filter(s -> s.getMethod() == method && s.getOrderId() != null)
        .map(BoxOfficeSale::getAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** Today's events for the org, ordered by start time. */
  public List<Event> todaysEvents(UUID organizationId) {
    Instant now = Instant.now();
    Instant from = now.minusSeconds(6L * 3600); // include just-started shows up to 6h ago
    Instant to = now.plusSeconds(24L * 3600);
    return eventRepository.findOrgEventsBetween(organizationId, from, to);
  }

  // ---- Internals ----

  /**
   * Locks seats, validates them, transitions to BOOKED, and persists ACTIVE
   * holds owned by the staff user. Returns the summed seat price.
   *
   * <p>Mirrors {@link com.fairtix.inventory.application.SeatHoldService#createHold}
   * but with a 1-minute TTL and no per-user purchase-cap check (staff sell on
   * behalf of many customers). Lock ordering and atomicity guarantees are
   * preserved by sorting seat IDs and using the existing batch lock query.
   */
  private BigDecimal holdSeats(UUID eventId, List<UUID> seatIds, UUID staffUserId, UUID organizationId) {
    Event event = eventRepository.findById(eventId)
        .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));
    if (event.getOrganizationId() == null || !event.getOrganizationId().equals(organizationId)) {
      throw new AccessDeniedException("Event does not belong to this organization");
    }
    List<UUID> sortedIds = seatIds.stream().distinct().sorted().toList();
    List<Seat> locked = seatRepository.findAndLockByIdIn(sortedIds);
    if (locked.size() != sortedIds.size()) {
      throw new ResourceNotFoundException("One or more seats not found");
    }
    Instant expiresAt = Instant.now().plusSeconds(HOLD_DURATION_MINUTES * 60L);
    BigDecimal total = BigDecimal.ZERO;
    Map<UUID, Seat> seatById = locked.stream().collect(Collectors.toMap(Seat::getId, Function.identity()));
    for (Seat seat : locked) {
      if (!seat.getEvent().getId().equals(eventId)) {
        throw new IllegalArgumentException(
            "Seat " + seat.getId() + " does not belong to event " + eventId);
      }
      if (seat.getStatus() != SeatStatus.AVAILABLE) {
        throw new SeatHoldConflictException(
            "Seat " + seat.getId() + " is not available (status: " + seat.getStatus() + ")");
      }
      seat.setStatus(SeatStatus.HELD);
      total = total.add(seat.getPrice());
    }
    seatRepository.saveAll(locked);
    List<SeatHold> holds = new ArrayList<>();
    for (UUID id : seatIds.stream().distinct().toList()) {
      holds.add(new SeatHold(seatById.get(id), staffUserId, expiresAt));
    }
    seatHoldRepository.saveAll(holds);
    return total;
  }

  /** Holds → confirms → marks SOLD in one shot for synchronous cash/comp sales. */
  private BigDecimal sellSeats(UUID eventId, List<UUID> seatIds, UUID staffUserId, UUID organizationId) {
    BigDecimal total = holdSeats(eventId, seatIds, staffUserId, organizationId);
    confirmHolds(seatIds, staffUserId);
    transitionToSold(seatIds, staffUserId);
    return total;
  }

  private void confirmHolds(List<UUID> seatIds, UUID staffUserId) {
    List<SeatHold> active = seatHoldRepository.findAllByOwnerIdAndStatus(staffUserId, HoldStatus.ACTIVE);
    var byId = active.stream().collect(Collectors.toMap(h -> h.getSeat().getId(), Function.identity(), (a, b) -> a));
    for (UUID seatId : seatIds) {
      SeatHold h = byId.get(seatId);
      if (h == null) throw new BoxOfficeStateException("Hold missing for seat " + seatId);
      h.setStatus(HoldStatus.CONFIRMED);
      h.getSeat().setStatus(SeatStatus.BOOKED);
    }
  }

  private void transitionToSold(List<UUID> seatIds, UUID staffUserId) {
    List<SeatHold> holds = seatHoldRepository.findAllByOwnerIdAndStatus(staffUserId, HoldStatus.CONFIRMED);
    var byId = holds.stream().collect(Collectors.toMap(h -> h.getSeat().getId(), Function.identity(), (a, b) -> a));
    for (UUID seatId : seatIds) {
      SeatHold h = byId.get(seatId);
      if (h == null) throw new BoxOfficeStateException("Confirmed hold missing for seat " + seatId);
      Seat seat = h.getSeat();
      seat.setStatus(SeatStatus.SOLD);
      h.setStatus(HoldStatus.RELEASED);
    }
  }

  private Order createOrder(UUID staffUserId, List<UUID> seatIds, BigDecimal amount, OrderStatus status) {
    User staff = userRepository.findById(staffUserId)
        .orElseThrow(() -> new ResourceNotFoundException("Staff user not found"));
    // Box office orders don't carry upstream hold IDs in the order_holds table;
    // the seat → ticket linkage is recorded in the Ticket rows we issue next.
    Order order = new Order(staff, List.of(), amount, "USD", status);
    return orderRepository.save(order);
  }

  private void issueTicketsForSeats(Order order, List<UUID> seatIds) {
    issueTicketsForSeats(order, seatIds, null);
  }

  private void issueTicketsForSeats(Order order, List<UUID> seatIds, BigDecimal overridePrice) {
    List<Seat> seats = seatRepository.findAllById(seatIds);
    List<Ticket> tickets = seats.stream()
        .map(s -> new Ticket(order, order.getUser(), s, s.getEvent(),
            overridePrice == null ? s.getPrice() : overridePrice))
        .toList();
    ticketRepository.saveAll(tickets);
  }

  private void recordCashPaymentRecord(Order order, UUID staffUserId, BigDecimal amount) {
    // Cash transactions don't have a Stripe id; we still record a row so the
    // payouts/settlement reports (M2-15..M2-17) can sum across channels.
    String txnId = "cash-" + order.getId();
    PaymentRecord record = new PaymentRecord(order.getId(), staffUserId, amount, "USD",
        PaymentStatus.SUCCESS, txnId, null);
    paymentRecordRepository.save(record);
  }

  private BoxOfficeSession requireOpenSession(UUID sessionId, UUID organizationId, UUID staffUserId) {
    BoxOfficeSession session = requireSession(sessionId, organizationId);
    if (session.getStatus() != SessionStatus.OPEN) {
      throw new BoxOfficeStateException("Session is closed");
    }
    if (!session.getStaffUserId().equals(staffUserId)
        && !organizationService.hasPermission(staffUserId, organizationId, OrgPermission.ALL)) {
      // Only the opening staff (or org owners with ALL) can sell on a session.
      // Other staff must open their own drawer.
      throw new AccessDeniedException("This box-office session belongs to another staff member");
    }
    return session;
  }

  private static String normalize(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }

  /** Caller bundle for the card-present create flow. */
  public record CardPresentIntentResult(
      com.stripe.model.PaymentIntent intent,
      String connectedAccountId,
      long applicationFeeCents) {}
}
