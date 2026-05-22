package com.fairtix.tickets.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.fraud.application.RiskScoringService;
import com.fairtix.fraud.application.UserFlaggedForAbuseException;
import com.fairtix.fraud.domain.RiskTier;
import com.fairtix.notifications.application.EmailTemplateService;
import com.fairtix.notifications.application.NotificationGate;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.tickets.infrastructure.TicketTransferRequestRepository;
import com.fairtix.users.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TransferServiceTest {

    private RedissonClient redissonClient;
    private RScoredSortedSet<String> velocitySet;
    private RiskScoringService riskScoringService;
    private AuditService auditService;
    private TicketRepository ticketRepository;
    private TransferService transferService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID TICKET_ID = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redissonClient = mock(RedissonClient.class);
        velocitySet = mock(RScoredSortedSet.class);
        riskScoringService = mock(RiskScoringService.class);
        auditService = mock(AuditService.class);
        ticketRepository = mock(TicketRepository.class);

        doReturn(velocitySet).when(redissonClient).getScoredSortedSet(anyString());

        transferService = new TransferService(
                ticketRepository,
                mock(TicketTransferRequestRepository.class),
                mock(UserRepository.class),
                auditService,
                mock(NotificationGate.class),
                mock(EmailTemplateService.class),
                redissonClient,
                riskScoringService);

        ReflectionTestUtils.setField(transferService, "velocityMaxPerWindow", 5);
        ReflectionTestUtils.setField(transferService, "velocityWindowHours", 24);
    }

    @Test
    void criticalTierUser_isBlockedAndAuditEventEmitted() {
        when(riskScoringService.getTier(USER_ID)).thenReturn(RiskTier.CRITICAL);

        assertThatThrownBy(() ->
                transferService.createTransferRequest(TICKET_ID, USER_ID, "other@example.com"))
                .isInstanceOf(UserFlaggedForAbuseException.class);

        verify(auditService).log(eq(USER_ID), eq("TRANSFER_BLOCKED_FRAUD_RISK"),
                anyString(), any(), anyString());
        verifyNoInteractions(velocitySet);
    }

    @Test
    void velocityLimitAtMax_throwsVelocityException() {
        when(riskScoringService.getTier(USER_ID)).thenReturn(RiskTier.LOW);
        when(velocitySet.removeRangeByScore(anyDouble(), anyBoolean(), anyDouble(), anyBoolean()))
                .thenReturn(0);
        when(velocitySet.size()).thenReturn(5);

        assertThatThrownBy(() ->
                transferService.createTransferRequest(TICKET_ID, USER_ID, "other@example.com"))
                .isInstanceOf(TransferVelocityExceededException.class)
                .hasMessageContaining("Transfer limit exceeded");
    }

    @Test
    void velocityUnderLimit_recordsEntryAndProceedsToTicketLookup() {
        when(riskScoringService.getTier(USER_ID)).thenReturn(RiskTier.LOW);
        when(velocitySet.removeRangeByScore(anyDouble(), anyBoolean(), anyDouble(), anyBoolean()))
                .thenReturn(0);
        when(velocitySet.size()).thenReturn(3);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                transferService.createTransferRequest(TICKET_ID, USER_ID, "other@example.com"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(velocitySet).add(anyDouble(), anyString());
    }
}
