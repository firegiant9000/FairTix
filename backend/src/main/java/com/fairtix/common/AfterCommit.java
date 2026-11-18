package com.fairtix.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * Runs a side effect, such as an email, once the current transaction has committed,
 * so that it does not run while the transaction's row and advisory locks are held,
 * and never happens for a change that was rolled back. Without an active
 * transaction it runs at once.
 *
 * <p>It still runs on the committing thread, before the transaction's resources
 * are released, so the JDBC connection stays checked out while the action runs
 * (and, with open-in-view, until the request ends). Data access inside the action
 * joins the committed transaction and is never committed itself.
 *
 * <p>The action must not touch managed entities: capture the values it needs
 * before calling. A failure is logged and swallowed; the transaction has already
 * committed and cannot be undone by it. The caller's MDC (the request id) is
 * restored while the action runs.
 */
public final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {
    }

    /**
     * @param description what the action does and for which record (an id), for
     *                    the log line if it fails; must not contain personal data
     *                    such as an email address
     */
    public static void run(String description, Runnable action) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        Runnable guarded = () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (mdc != null) {
                MDC.setContextMap(mdc);
            }
            try {
                action.run();
            } catch (RuntimeException ex) {
                log.warn("After-commit action failed ({}): {}", description, ex.getClass().getSimpleName());
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    guarded.run();
                }
            });
        } else {
            guarded.run();
        }
    }
}
