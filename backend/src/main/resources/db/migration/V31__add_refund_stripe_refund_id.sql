ALTER TABLE refund_requests
    ADD COLUMN stripe_refund_id VARCHAR(64);

CREATE UNIQUE INDEX idx_refund_stripe_refund_id
    ON refund_requests(stripe_refund_id)
    WHERE stripe_refund_id IS NOT NULL;
