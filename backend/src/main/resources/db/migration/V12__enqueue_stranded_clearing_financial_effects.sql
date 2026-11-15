-- Round 4 (R4-01): durable financial-effect work for terminal clearing outcomes that a pre-Round-4 node acknowledged
-- without posting their settlement or refund. Before Round 4 the only code that applied such an effect was an in-memory
-- AFTER_COMMIT listener whose failures Spring logs and swallows, and the callback's own outbox event was consumed by a
-- receipt-only observer. This migration only enqueues work for the governed outbox; it posts no journal and changes no
-- financial, payment, callback or audit row. V1-V11 remain unchanged.
INSERT INTO outbox_events (id, event_key, event_type, aggregate_type, aggregate_id, actor_customer_id, organization_id,
    correlation_id, payload)
SELECT gen_random_uuid(), 'clearing.financial-effect.backfill:' || p.id::text, 'clearing.financial-effect.backfill',
       'CLEARING_INSTRUCTION', p.operation_id, NULL, p.organization_id, NULL,
       jsonb_build_object('paymentId', p.id::text, 'externalStatus', p.external_status, 'reason', 'R4-01 V12 backfill')
FROM corporate_payment_intents p
JOIN clearing_instructions c ON c.operation_id = p.operation_id
WHERE p.status IN ('PENDING_CLEARING', 'UNKNOWN')
  AND ((p.external_status = 'SETTLED' AND p.settlement_journal_entry_id IS NULL)
    OR (p.external_status = 'REJECTED' AND p.reversal_journal_entry_id IS NULL AND p.settlement_journal_entry_id IS NULL))
ON CONFLICT (event_key) DO NOTHING;
