package com.fairtix.refunds.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.fraud.application.RiskScoringService;
import com.fairtix.fraud.domain.RiskTier;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.notifications.application.EmailTemplateService;
import com.fairtix.notifications.application.NotificationGate;
import com.fairtix.orders.domain.Order;
import com.fairtix.orders.domain.OrderStatus;
import com.fairtix.orders.infrastructure.OrderRepository;
import com.fairtix.payments.application.StripePaymentService;
import com.fairtix.payments.infrastructure.PaymentRecordRepository;
import com.fairtix.refunds.domain.RefundRequest;
import com.fairtix.refunds.domain.RefundStatus;
import com.fairtix.refunds.infrastructure.RefundRepository;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.tickets.infrastructure.TicketTransferRequestRepository;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The customer mails sent by requestRefund and reviewRefund go out after the commit. */
class RefundServiceMailTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final String EMAIL = "buyer@example.com";

    private RefundRepository refundRepository;
    private OrderRepository orderRepository;
    private NotificationGate notificationGate;
    private RiskScoringService riskScoringService;
    private RefundService refundService;

    @BeforeEach
    void setUp() {
        refundRepository = mock(RefundRepository.class);
        orderRepository = mock(OrderRepository.class);
        notificationGate = mock(NotificationGate.class);
        riskScoringService = mock(RiskScoringService.class);
        UserRepository userRepository = mock(UserRepository.class);

        refundService = new RefundService(refundRepository, orderRepository,
                mock(TicketRepository.class), mock(SeatRepository.class),
                mock(PaymentRecordRepository.class), userRepository, mock(AuditService.class),
                notificationGate, mock(EmailTemplateService.class), riskScoringService,
                mock(StripePaymentService.class), mock(TicketTransferRequestRepository.class));
        // reviewRefund locks the refund row through the EntityManager (@PersistenceContext).
        ReflectionTestUtils.setField(refundService, "entityManager",
                mock(jakarta.persistence.EntityManager.class));
        ReflectionTestUtils.setField(refundService, "refundEnabled", true);
        ReflectionTestUtils.setField(refundService, "refundWindowDays", 14);
        ReflectionTestUtils.setField(refundService, "autoApproveThreshold", new BigDecimal("50.00"));

        User user = mock(User.class);
        when(user.getId()).thenReturn(USER_ID);
        when(user.getEmail()).thenReturn(EMAIL);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(refundRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(riskScoringService.getTier(USER_ID)).thenReturn(RiskTier.LOW);
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void requestedMail_isSentOnlyAfterTheCommit() {
        completedOrderOver50();
        TransactionSynchronizationManager.initSynchronization();

        RefundRequest refund = refundService.requestRefund(USER_ID, ORDER_ID, "can't attend");
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        verifyNoInteractions(notificationGate);

        commit();
        verify(notificationGate).sendEmail(eq(USER_ID), any(), eq(EMAIL), anyString(), any());
    }

    @Test
    void requestedMail_isNotSentWhenTheTransactionRollsBack() {
        completedOrderOver50();
        TransactionSynchronizationManager.initSynchronization();

        refundService.requestRefund(USER_ID, ORDER_ID, "can't attend");
        rollBack();

        verifyNoInteractions(notificationGate);
    }

    @Test
    void rejectedMail_isSentOnlyAfterTheCommit_andItsFailureIsSwallowed() {
        RefundRequest refund = new RefundRequest(ORDER_ID, USER_ID, new BigDecimal("80.00"), "reason");
        UUID refundId = UUID.randomUUID();
        when(refundRepository.findById(refundId)).thenReturn(Optional.of(refund));
        doThrow(new RuntimeException("Email delivery failed"))
                .when(notificationGate).sendEmail(any(), any(), any(), anyString(), any());
        TransactionSynchronizationManager.initSynchronization();

        refundService.reviewRefund(UUID.randomUUID(), refundId, false, "outside policy");
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REJECTED);
        verifyNoInteractions(notificationGate);

        assertThatCode(this::commit).doesNotThrowAnyException();
        verify(notificationGate).sendEmail(eq(USER_ID), any(), eq(EMAIL), anyString(), any());
    }

    /** Auto-approved and fraud-held requests send no "received" mail, before or after commit. */
    @Test
    void autoApprovedAndHeldRequests_sendNoMail() {
        completedOrder("20.00");
        TransactionSynchronizationManager.initSynchronization();
        assertThat(refundService.requestRefund(USER_ID, ORDER_ID, "small").getStatus())
                .isEqualTo(RefundStatus.APPROVED);

        completedOrder("80.00");
        when(riskScoringService.getTier(USER_ID)).thenReturn(RiskTier.HIGH);
        assertThat(refundService.requestRefund(USER_ID, ORDER_ID, "held").getStatus())
                .isEqualTo(RefundStatus.PENDING_MANUAL);

        commit();
        verifyNoInteractions(notificationGate);
    }

    private void completedOrderOver50() {
        completedOrder("80.00");
    }

    private void completedOrder(String total) {
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(USER_ID);
        Order order = mock(Order.class);
        when(order.getUser()).thenReturn(owner);
        when(order.getStatus()).thenReturn(OrderStatus.COMPLETED);
        when(order.getCreatedAt()).thenReturn(Instant.now());
        when(order.getTotalAmount()).thenReturn(new BigDecimal(total));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
    }

    private void commit() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
    }

    private void rollBack() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    }
}
