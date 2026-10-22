package com.fairtix.refunds.application;

import com.fairtix.audit.infrastructure.AuditLogRepository;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.fraud.domain.RiskScore;
import com.fairtix.fraud.domain.RiskTier;
import com.fairtix.fraud.infrastructure.RiskScoreRepository;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.orders.domain.Order;
import com.fairtix.orders.domain.OrderStatus;
import com.fairtix.orders.infrastructure.OrderRepository;
import com.fairtix.refunds.domain.RefundRequest;
import com.fairtix.refunds.domain.RefundStatus;
import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.domain.TicketStatus;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class RefundServiceTest {

    @Autowired private RefundService refundService;
    @Autowired private RefundExecutionService refundExecutionService;
    @Autowired private UserRepository userRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private RiskScoreRepository riskScoreRepository;
    @Autowired private AuditLogRepository auditLogRepository;

    private User user;
    private Order order;
    private Event event;
    private Seat seat;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setEmail("refundtest_" + UUID.randomUUID() + "@test.com");
        user.setPassword("$2a$10$dummyhashfortest");
        user.setEmailVerified(true);
        user = userRepository.save(user);

        event = eventRepository.save(
            new Event("Refund Test Event", null, Instant.now().plusSeconds(86400), (UUID) null));

        seat = seatRepository.save(new Seat(event, "Floor", "A", "1", new BigDecimal("30.00")));

        order = orderRepository.save(
            new Order(user, List.of(), new BigDecimal("30.00"), "USD", OrderStatus.COMPLETED));

        ticketRepository.save(new Ticket(order, user, seat, event, new BigDecimal("30.00")));
    }

    private void setRiskScore(int score, RiskTier tier) {
        riskScoreRepository.save(new RiskScore(user.getId(), score, tier, 0, "test"));
    }

    /** A further order's ticket needs its own seat: one active ticket per seat (V46). */
    private Seat anotherSeat() {
        return seatRepository.save(new Seat(event, "Floor", "A", "2", new BigDecimal("30.00")));
    }

    /** refund_requests.reviewed_by references users(id). */
    private UUID newAdmin() {
        User admin = new User();
        admin.setEmail("refundadmin_" + UUID.randomUUID() + "@test.com");
        admin.setPassword("$2a$10$dummyhashfortest");
        return userRepository.save(admin).getId();
    }

    @Test
    void criticalTierUser_isRoutedToManualReview_andAuditEventEmitted() {
        setRiskScore(80, RiskTier.CRITICAL);

        RefundRequest refund = refundService.requestRefund(user.getId(), order.getId(), "Test refund");

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_MANUAL);
        long auditCount = auditLogRepository.countByUserIdAndActionAndCreatedAtAfter(
            user.getId(), "REFUND_HELD_FRAUD_RISK", Instant.now().minus(1, ChronoUnit.MINUTES));
        assertThat(auditCount).isGreaterThan(0);
    }

    @Test
    void highTierUser_isRoutedToManualReview() {
        setRiskScore(55, RiskTier.HIGH);

        RefundRequest refund = refundService.requestRefund(user.getId(), order.getId(), "Test refund");

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING_MANUAL);
    }

    @Test
    void mediumTierUser_underThreshold_isAutoApproved() {
        setRiskScore(30, RiskTier.MEDIUM);

        RefundRequest refund = refundExecutionService.request(user.getId(), order.getId(), "Test refund");

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
    }

    @Test
    void lowTierUser_underThreshold_isAutoApproved() {
        RefundRequest refund = refundExecutionService.request(user.getId(), order.getId(), "Test refund");

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
    }

    @Test
    void lowTierUser_overThreshold_remainsRequested() {
        order = orderRepository.save(
            new Order(user, List.of(), new BigDecimal("100.00"), "USD", OrderStatus.COMPLETED));
        ticketRepository.save(new Ticket(order, user, anotherSeat(), event, new BigDecimal("100.00")));

        RefundRequest refund = refundService.requestRefund(user.getId(), order.getId(), "Test refund");

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    @Test
    void requestRefund_withPendingOrder_throwsRefundNotEligible() {
        Order pendingOrder = orderRepository.save(
            new Order(user, List.of(), new BigDecimal("30.00"), "USD", OrderStatus.PENDING));
        ticketRepository.save(new Ticket(pendingOrder, user, anotherSeat(), event, new BigDecimal("30.00")));

        assertThatThrownBy(() -> refundService.requestRefund(user.getId(), pendingOrder.getId(), "Test"))
            .isInstanceOf(RefundNotEligibleException.class)
            .hasMessageContaining("completed");
    }

    @Test
    void requestRefund_whenPendingRefundExists_throwsRefundNotEligible() {
        Order highOrder = orderRepository.save(
            new Order(user, List.of(), new BigDecimal("75.00"), "USD", OrderStatus.COMPLETED));
        ticketRepository.save(new Ticket(highOrder, user, anotherSeat(), event, new BigDecimal("75.00")));
        refundService.requestRefund(user.getId(), highOrder.getId(), "First request");

        assertThatThrownBy(() -> refundService.requestRefund(user.getId(), highOrder.getId(), "Duplicate"))
            .isInstanceOf(RefundNotEligibleException.class)
            .hasMessageContaining("already pending");
    }

    @Test
    void requestRefund_withUsedTicket_throwsRefundNotEligible() {
        Ticket usedTicket = ticketRepository.findAllByOrder_Id(order.getId()).get(0);
        usedTicket.setStatus(TicketStatus.USED);
        ticketRepository.save(usedTicket);

        assertThatThrownBy(() -> refundService.requestRefund(user.getId(), order.getId(), "Test"))
            .isInstanceOf(RefundNotEligibleException.class)
            .hasMessageContaining("used");
    }

    @Test
    void reviewRefund_approve_completesRefundAndReleasesSeats() {
        Order highOrder = orderRepository.save(
            new Order(user, List.of(), new BigDecimal("75.00"), "USD", OrderStatus.COMPLETED));
        Seat highSeat = anotherSeat();
        ticketRepository.save(new Ticket(highOrder, user, highSeat, event, new BigDecimal("75.00")));
        highSeat.setStatus(SeatStatus.SOLD);
        seatRepository.save(highSeat);

        RefundRequest refund = refundService.requestRefund(user.getId(), highOrder.getId(), "Please refund");
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);

        RefundRequest result = refundExecutionService.review(newAdmin(), refund.getId(), true, "Approved");

        assertThat(result.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        Seat reloaded = seatRepository.findById(highSeat.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    void reviewRefund_reject_setsRejectedStatus() {
        Order highOrder = orderRepository.save(
            new Order(user, List.of(), new BigDecimal("75.00"), "USD", OrderStatus.COMPLETED));
        ticketRepository.save(new Ticket(highOrder, user, anotherSeat(), event, new BigDecimal("75.00")));

        RefundRequest refund = refundService.requestRefund(user.getId(), highOrder.getId(), "Please refund");
        RefundRequest result = refundService.reviewRefund(newAdmin(), refund.getId(), false, "Policy violation");

        assertThat(result.getStatus()).isEqualTo(RefundStatus.REJECTED);
    }
}
