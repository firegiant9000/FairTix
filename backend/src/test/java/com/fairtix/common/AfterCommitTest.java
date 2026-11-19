package com.fairtix.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AfterCommitTest {

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        MDC.clear();
    }

    @Test
    void withoutATransaction_runsAtOnce() {
        AtomicInteger runs = new AtomicInteger();

        AfterCommit.run("test", runs::incrementAndGet);

        assertThat(runs).hasValue(1);
    }

    @Test
    void inATransaction_waitsForTheCommit() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger runs = new AtomicInteger();

        AfterCommit.run("test", runs::incrementAndGet);
        assertThat(runs).hasValue(0);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        assertThat(runs).hasValue(1);
    }

    @Test
    void onRollback_neverRuns() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger runs = new AtomicInteger();

        AfterCommit.run("test", runs::incrementAndGet);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(runs).hasValue(0);
    }

    @Test
    void aFailingAction_isSwallowed() {
        TransactionSynchronizationManager.initSynchronization();
        AfterCommit.run("test", () -> {
            throw new RuntimeException("Email delivery failed");
        });

        assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit))
                .doesNotThrowAnyException();
    }

    /** The scheduled jobs clear their request id before the commit runs. */
    @Test
    void theCallersRequestId_isRestoredWhileTheActionRuns() {
        TransactionSynchronizationManager.initSynchronization();
        MDC.put("requestId", "req-1");
        AtomicReference<String> seen = new AtomicReference<>();

        AfterCommit.run("test", () -> seen.set(MDC.get("requestId")));
        MDC.remove("requestId");
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        assertThat(seen).hasValue("req-1");
        assertThat(MDC.get("requestId")).isNull();
    }
}
