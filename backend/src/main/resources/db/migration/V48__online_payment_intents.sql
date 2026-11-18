-- Persists the online Stripe PaymentIntent per user and hold set, so a retried
-- checkout reuses the same intent instead of creating a second charge.
CREATE TABLE online_payment_intents (
    id                       UUID         PRIMARY KEY,
    user_id                  UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    hold_set_hash            VARCHAR(64)  NOT NULL,
    amount_cents             BIGINT       NOT NULL,
    routing_hash             VARCHAR(64)  NOT NULL,
    stripe_payment_intent_id VARCHAR(255) NOT NULL UNIQUE,
    status                   VARCHAR(20)  NOT NULL,
    invalidated_reason       VARCHAR(40),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    invalidated_at           TIMESTAMPTZ
);

-- Concurrency backstop: at most one ACTIVE intent per user and hold set.
CREATE UNIQUE INDEX idx_online_payment_intents_active
    ON online_payment_intents (user_id, hold_set_hash)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_online_payment_intents_created_at
    ON online_payment_intents (created_at);
