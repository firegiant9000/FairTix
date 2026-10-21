package com.fairtix.refunds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fairtix.events.application.EventService;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.orders.domain.Order;
import com.fairtix.orders.domain.OrderStatus;
import com.fairtix.orders.infrastructure.OrderRepository;
import com.fairtix.payments.application.StripePaymentService;
import com.fairtix.payments.domain.PaymentRecord;
import com.fairtix.payments.domain.PaymentStatus;
import com.fairtix.payments.infrastructure.PaymentRecordRepository;
import com.fairtix.refunds.application.RefundExecutionService;
import com.fairtix.support.PostgresIT;
import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import com.stripe.model.Refund;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Exposure gate (b): event cancellation refunds each order in its own transaction,
 * after the cancellation commits, so one Stripe failure can't undo the others.
 *
 * <p>Red on commit {@code cbf4604}: every order's Stripe refund ran inside the one
 * event-cancel transaction, so a commit failure at the end would have lost the DB
 * side of refunds Stripe had already paid, and the seat locks stayed open across N
 * Stripe round trips.
 */
@TestPropertySource(properties = "stripe.enabled=true")
class EventCancellationRefundIT extends PostgresIT {

  @Autowired private EventService eventService;
  @Autowired private RefundExecutionService refundExecutionService;
  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private OrderRepository orderRepository;
  @Autowired private TicketRepository ticketRepository;
  @Autowired private PaymentRecordRepository paymentRecordRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoSpyBean private StripePaymentService stripePaymentService;

  @Test
  void oneStripeFailure_othersStillRefund() {
    User buyer = user("cancel-buyer");
    UUID adminId = user("cancel-admin").getId();
    Event event = eventRepository.save(
        new Event("Cancelled Concert", null, Instant.now().plusSeconds(86400), (UUID) null));
    String failingIntent = "pi_test_fail_" + UUID.randomUUID();
    Order orderA = stripePaidOrder(buyer, event, "1", failingIntent);
    Order orderB = stripePaidOrder(buyer, event, "2", "pi_test_ok_" + UUID.randomUUID());

    List<Boolean> txActiveAtStripe = new CopyOnWriteArrayList<>();
    doAnswer(inv -> {
      txActiveAtStripe.add(TransactionSynchronizationManager.isActualTransactionActive());
      if (failingIntent.equals(inv.getArgument(0))) {
        throw new RuntimeException("Failed to create Stripe refund: network");
      }
      Refund refund = mock(Refund.class);
      when(refund.getId()).thenReturn("re_test_" + inv.getArgument(3));
      return refund;
    }).when(stripePaymentService).createRefund(anyString(), anyLong(), any(), any(UUID.class));

    eventService.cancelEvent(event.getId(), adminId, "Venue flooded");
    refundExecutionService.executeAllForEvent(event.getId(), adminId);

    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM events WHERE id = ?", String.class, event.getId())).isEqualTo("CANCELLED");
    assertThat(txActiveAtStripe).hasSize(2).containsOnly(false);

    assertThat(orderStatus(orderB.getId())).isEqualTo("REFUNDED");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payment_records WHERE order_id = ? AND amount < 0",
        Integer.class, orderB.getId())).isEqualTo(1);

    assertThat(orderStatus(orderA.getId())).isEqualTo("COMPLETED");
    UUID refundA = jdbcTemplate.queryForObject(
        "SELECT id FROM refund_requests WHERE order_id = ?", UUID.class, orderA.getId());
    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM refund_requests WHERE id = ?", String.class, refundA)).isEqualTo("APPROVED");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM audit_logs WHERE action = 'REFUND_STRIPE_FAILED' AND resource_id = ?",
        Integer.class, refundA)).isEqualTo(1);
  }

  private User user(String prefix) {
    User u = new User();
    u.setEmail(prefix + "_" + UUID.randomUUID() + "@test.com");
    u.setPassword("$2a$10$dummyhashfortest");
    u.setEmailVerified(true);
    return userRepository.save(u);
  }

  private Order stripePaidOrder(User buyer, Event event, String seatNumber, String intentId) {
    BigDecimal price = new BigDecimal("40.00");
    Seat seat = seatRepository.save(new Seat(event, "Floor", "A", seatNumber, price));
    seat.setStatus(SeatStatus.SOLD);
    seat = seatRepository.save(seat);
    Order order = orderRepository.save(new Order(buyer, List.of(), price, "USD", OrderStatus.COMPLETED));
    ticketRepository.save(new Ticket(order, buyer, seat, event, price));
    paymentRecordRepository.save(new PaymentRecord(order.getId(), buyer.getId(), price, "USD",
        PaymentStatus.SUCCESS, intentId, null));
    return order;
  }

  private String orderStatus(UUID orderId) {
    return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
  }
}
