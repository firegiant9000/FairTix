package com.fairtix.queue.application;

import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.queue.domain.QueueEntry;
import com.fairtix.queue.domain.QueueStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.redisson.client.RedisTimeoutException;
import org.redisson.client.codec.Codec;
import org.redisson.misc.CompletableFutureWrapper;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueueSseServiceTest {

    @Mock
    private QueueService queueService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RTopic topic;

    private QueueSseService sseService;

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(redissonClient.getTopic(eq(QueueSseService.TOPIC), any(Codec.class))).thenReturn(topic);
        sseService = new QueueSseService(queueService, redissonClient);
    }

    @AfterEach
    void tearDown() {
        sseService.unsubscribe();
    }

    @Test
    void publishUpdate_sendsOnlyTheEventId_andLeavesTheBroadcastToSubscribers() {
        QueueEntry entry = mockWaitingEntry();
        when(queueService.getStatus(EVENT_ID, USER_ID)).thenReturn(entry);
        sseService.register(EVENT_ID, USER_ID);
        reset(queueService);
        when(topic.publishAsync(EVENT_ID.toString())).thenReturn(new CompletableFutureWrapper<>(2L));

        sseService.publishUpdate(EVENT_ID);

        verify(topic).publishAsync(EVENT_ID.toString());
        verify(queueService, after(300).never()).getStatus(any(), any());
    }

    @Test
    void publishUpdate_broadcastsLocallyWhenRedisFails() {
        QueueEntry entry = mockWaitingEntry();
        when(queueService.getStatus(EVENT_ID, USER_ID)).thenReturn(entry);
        sseService.register(EVENT_ID, USER_ID);
        when(topic.publishAsync(anyString()))
                .thenReturn(new CompletableFutureWrapper<>(new RedisTimeoutException("down")));

        sseService.publishUpdate(EVENT_ID);

        verify(queueService, timeout(5000).times(2)).getStatus(EVENT_ID, USER_ID);
    }

    @Test
    void publishUpdate_broadcastsLocallyWhenNoInstanceIsSubscribed() {
        QueueEntry entry = mockWaitingEntry();
        when(queueService.getStatus(EVENT_ID, USER_ID)).thenReturn(entry);
        sseService.register(EVENT_ID, USER_ID);
        when(topic.publishAsync(anyString())).thenReturn(new CompletableFutureWrapper<>(0L));

        sseService.publishUpdate(EVENT_ID);

        verify(queueService, timeout(5000).times(2)).getStatus(EVENT_ID, USER_ID);
    }

    /** Subscription failed at startup: peers still receive, so publish alone would not reach us. */
    @Test
    void publishUpdate_broadcastsLocallyAndRetriesWhileThisInstanceIsNotSubscribed() {
        when(topic.addListener(eq(String.class), any()))
                .thenThrow(new RedisTimeoutException("down"))
                .thenThrow(new RedisTimeoutException("down"))
                .thenReturn(3);
        sseService.subscribe();
        QueueEntry entry = mockWaitingEntry();
        when(queueService.getStatus(EVENT_ID, USER_ID)).thenReturn(entry);
        sseService.register(EVENT_ID, USER_ID);
        when(topic.publishAsync(anyString())).thenReturn(new CompletableFutureWrapper<>(1L));

        sseService.publishUpdate(EVENT_ID);
        verify(queueService, timeout(5000).times(2)).getStatus(EVENT_ID, USER_ID);

        sseService.publishUpdate(EVENT_ID);
        verify(topic, times(3)).addListener(eq(String.class), any());
        verify(queueService, after(300).times(2)).getStatus(EVENT_ID, USER_ID);
    }

    @Test
    void subscribedListener_broadcastsToThisInstancesEmitters() {
        when(topic.addListener(eq(String.class), any())).thenReturn(7);
        sseService.subscribe();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<MessageListener<String>> listener = ArgumentCaptor.forClass(MessageListener.class);
        verify(topic).addListener(eq(String.class), listener.capture());

        QueueEntry entry = mockWaitingEntry();
        when(queueService.getStatus(EVENT_ID, USER_ID)).thenReturn(entry);
        sseService.register(EVENT_ID, USER_ID);

        listener.getValue().onMessage(QueueSseService.TOPIC, EVENT_ID.toString());

        verify(queueService, timeout(5000).times(2)).getStatus(EVENT_ID, USER_ID);
        sseService.unsubscribe();
        verify(topic).removeListener(7);
    }

    @Test
    void onMessage_ignoresMalformedIdsAndEventsWithNoEmittersHere() {
        sseService.onMessage("not-a-uuid");
        sseService.onMessage(UUID.randomUUID().toString());

        verifyNoInteractions(queueService);
    }

    @Test
    void broadcastSkipsWhenNoEmitters() {
        sseService.broadcast(EVENT_ID);
        verifyNoInteractions(queueService);
    }

    @Test
    void broadcastSendsStatusToConnectedEmitter() throws Exception {
        QueueEntry entry = mockWaitingEntry();
        when(queueService.getStatus(EVENT_ID, USER_ID)).thenReturn(entry);

        SseEmitter emitter = sseService.register(EVENT_ID, USER_ID);
        assertThat(emitter).isNotNull();

        reset(queueService);
        when(queueService.getStatus(EVENT_ID, USER_ID)).thenReturn(entry);

        sseService.broadcast(EVENT_ID);

        verify(queueService).getStatus(EVENT_ID, USER_ID);
    }

    @Test
    void broadcastRemovesDeadEmitterOnError() {
        QueueEntry entry = mockWaitingEntry();
        when(queueService.getStatus(EVENT_ID, USER_ID)).thenReturn(entry);

        sseService.register(EVENT_ID, USER_ID);

        reset(queueService);
        when(queueService.getStatus(EVENT_ID, USER_ID))
                .thenThrow(new ResourceNotFoundException("not found"));

        sseService.broadcast(EVENT_ID);

        // A second broadcast should find no live emitters and not call queueService
        reset(queueService);
        sseService.broadcast(EVENT_ID);
        verifyNoInteractions(queueService);
    }

    @Test
    void registerSendsInitialStatusImmediately() {
        QueueEntry entry = mockWaitingEntry();
        when(queueService.getStatus(EVENT_ID, USER_ID)).thenReturn(entry);

        SseEmitter emitter = sseService.register(EVENT_ID, USER_ID);

        assertThat(emitter).isNotNull();
        verify(queueService).getStatus(EVENT_ID, USER_ID);
        verifyNoMoreInteractions(queueService);
    }

    private QueueEntry mockWaitingEntry() {
        QueueEntry entry = mock(QueueEntry.class);
        when(entry.getStatus()).thenReturn(QueueStatus.WAITING);
        when(entry.getPosition()).thenReturn(1);
        when(entry.getId()).thenReturn(UUID.randomUUID());
        return entry;
    }
}
