-- EXPLORATORY (not pre-registered): per-item cost of the two queries V7's result-transition trigger adds when a
-- result becomes SUCCEEDED. Runs read-only lookups for real SUCCEEDED results in the R2-P3 Round 2 database.
\pset footer off
SELECT current_database() AS database, (SELECT count(*) FROM payroll_item_results WHERE status = 'SUCCEEDED') AS succeeded_results;
DO $$
DECLARE
    rec record; started timestamptz; elapsed interval := interval '0'; n int := 0;
    batch_status varchar; batch_payer uuid; batch_organization uuid; batch_creator uuid; item_payee uuid; item_amount bigint;
    linked boolean;
BEGIN
    FOR pass IN 1..2 LOOP                                   -- pass 1 warms caches; pass 2 is measured
        elapsed := interval '0'; n := 0;
        FOR rec IN SELECT item_id, batch_id, transfer_id FROM payroll_item_results WHERE status = 'SUCCEEDED'
                    ORDER BY item_id LIMIT 2000 LOOP
            started := clock_timestamp();
            SELECT b.status, b.payer_account_id, b.organization_id, b.created_by_customer_id, i.payee_account_id, i.amount_minor
              INTO batch_status, batch_payer, batch_organization, batch_creator, item_payee, item_amount
              FROM payroll_items i JOIN payroll_batches b ON b.id = i.batch_id WHERE i.id = rec.item_id;
            SELECT EXISTS (SELECT 1 FROM transfers t JOIN idempotency_records k ON k.transfer_id = t.id
                            WHERE t.id = rec.transfer_id AND t.source_account_id = batch_payer
                              AND t.target_account_id = item_payee AND t.amount_minor = item_amount
                              AND t.owner_customer_id = batch_creator AND t.source_organization_id = batch_organization
                              AND k.owner_customer_id = batch_creator AND k.operation = 'TRANSFER'
                              AND k.idempotency_key = 'payroll-' || rec.batch_id || '-' || rec.item_id)
              INTO linked;
            elapsed := elapsed + (clock_timestamp() - started);
            n := n + 1;
            IF NOT linked THEN RAISE EXCEPTION 'unexpected unlinked result %', rec.item_id; END IF;
        END LOOP;
    END LOOP;
    RAISE NOTICE 'V7 transition-trigger lookups: % results, mean % ms per result (pass 2)', n,
        round((extract(epoch FROM elapsed) * 1000 / n)::numeric, 4);
END $$;
