package com.fairtix.tickets.application;

/**
 * A pending transfer whose ticket stopped being {@code VALID} (refunded,
 * cancelled or used). {@code TransferService.acceptTransfer} resolves the
 * request before throwing this, and commits that: the transaction does not roll
 * back for it. An {@link IllegalStateException}, so the API answers 409.
 */
public class TransferNoLongerAcceptableException extends IllegalStateException {

    public TransferNoLongerAcceptableException(String message) {
        super(message);
    }
}
