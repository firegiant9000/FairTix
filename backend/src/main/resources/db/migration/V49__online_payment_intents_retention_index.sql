-- Retention deletes only INVALIDATED rows by invalidated_at
-- (OnlinePaymentIntentRepository.deleteInvalidatedBefore); index exactly that.
CREATE INDEX idx_online_payment_intents_invalidated_at
    ON online_payment_intents (invalidated_at)
    WHERE status = 'INVALIDATED';

-- Rows whose order already exists were left ACTIVE before checkout started ending them
-- (reason COMPLETED). End them now so retention can remove them. A payment record's
-- transaction_id is the Stripe intent id for Stripe orders; simulated ids never match.
UPDATE online_payment_intents
SET status = 'INVALIDATED',
    invalidated_reason = 'COMPLETED',
    invalidated_at = NOW()
WHERE status = 'ACTIVE'
  AND stripe_payment_intent_id IN (SELECT transaction_id FROM payment_records);
