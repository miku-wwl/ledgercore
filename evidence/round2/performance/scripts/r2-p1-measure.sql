-- R2-P1 measurement. Usage (psql variables): -v stage=S1|S2 -v is_v7=true|false
-- R1_* statements are the Round 1 (b584b95) PayrollService SQL; R2_* are the Round 2 SQL (V7 schema only).
-- Each statement is PREPAREd with one parameter per JDBC placeholder, warmed up five times, explained from the
-- cached prepared plan, then timed 30 times server-side in PL/pgSQL (static SQL uses the same plan cache rules).
\set ON_ERROR_STOP on
\pset footer off
SELECT set_config('r2p1.stage', :'stage', false) AS stage_label;
SELECT current_database() AS database, :'stage' AS stage, :'is_v7' AS is_v7_requested,
       EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'payroll_item_results'
               AND column_name = 'batch_id') AS has_v7_result_batch_column,
       (SELECT count(*) FROM payroll_item_results) AS global_results;
CREATE TEMP TABLE IF NOT EXISTS r2p1_timing (stage text, query text, iteration int, ms double precision);

PREPARE r1_counts(uuid) AS
SELECT count(*) FILTER (WHERE r.status = 'PENDING') AS pending_count,
       count(*) FILTER (WHERE r.status = 'SUCCEEDED') AS succeeded_count,
       count(*) FILTER (WHERE r.status = 'FAILED') AS failed_count
FROM payroll_items i JOIN payroll_item_results r ON r.item_id = i.id
WHERE i.batch_id = $1;

PREPARE r1_view(uuid) AS
SELECT b.id, b.organization_id, b.name, b.payer_account_id, b.status,
       count(i.id) AS item_count, COALESCE(sum(i.amount_minor), 0) AS total_minor,
       count(*) FILTER (WHERE r.status = 'PENDING') AS pending_count,
       count(*) FILTER (WHERE r.status = 'SUCCEEDED') AS succeeded_count,
       count(*) FILTER (WHERE r.status = 'FAILED') AS failed_count,
       COALESCE(sum(i.amount_minor) FILTER (WHERE r.status = 'SUCCEEDED'), 0) AS succeeded_minor,
       b.created_by_customer_id, b.submitted_by_customer_id, b.approved_by_customer_id,
       b.rejected_by_customer_id, b.rejection_reason, b.content_digest,
       b.approved_content_digest, b.created_at, b.submitted_at, b.approved_at,
       b.rejected_at, b.started_at, b.completed_at
FROM payroll_batches b
LEFT JOIN payroll_items i ON i.batch_id = b.id
LEFT JOIN payroll_item_results r ON r.item_id = i.id
WHERE b.id = $1
GROUP BY b.id;

\echo '== R1_COUNTS warm-up result (expected 3000 pending / 0 succeeded / 2000 failed)'
EXECUTE r1_counts(md5('r2p1-batch-0')::uuid);
EXECUTE r1_counts(md5('r2p1-batch-0')::uuid);
EXECUTE r1_counts(md5('r2p1-batch-0')::uuid);
EXECUTE r1_counts(md5('r2p1-batch-0')::uuid);
EXECUTE r1_counts(md5('r2p1-batch-0')::uuid);
EXECUTE r1_view(md5('r2p1-batch-0')::uuid);
EXECUTE r1_view(md5('r2p1-batch-0')::uuid);
EXECUTE r1_view(md5('r2p1-batch-0')::uuid);
EXECUTE r1_view(md5('r2p1-batch-0')::uuid);
EXECUTE r1_view(md5('r2p1-batch-0')::uuid);
\echo '== PLAN R1_COUNTS'
EXPLAIN (ANALYZE, BUFFERS, SETTINGS) EXECUTE r1_counts(md5('r2p1-batch-0')::uuid);
\echo '== PLAN R1_VIEW'
EXPLAIN (ANALYZE, BUFFERS, SETTINGS) EXECUTE r1_view(md5('r2p1-batch-0')::uuid);

DO $$
DECLARE
    target uuid := md5('r2p1-batch-0')::uuid;
    started timestamptz;
    pending bigint; succeeded bigint; failed bigint; items bigint; total numeric; succeeded_minor numeric;
BEGIN
    FOR iteration IN 1..35 LOOP
        started := clock_timestamp();
        SELECT count(*) FILTER (WHERE r.status = 'PENDING'), count(*) FILTER (WHERE r.status = 'SUCCEEDED'),
               count(*) FILTER (WHERE r.status = 'FAILED')
          INTO pending, succeeded, failed
          FROM payroll_items i JOIN payroll_item_results r ON r.item_id = i.id
         WHERE i.batch_id = target;
        IF iteration > 5 THEN
            INSERT INTO r2p1_timing VALUES (current_setting('r2p1.stage'), 'R1_COUNTS', iteration - 5,
                extract(epoch FROM clock_timestamp() - started) * 1000);
        END IF;
    END LOOP;
    FOR iteration IN 1..35 LOOP
        started := clock_timestamp();
        SELECT count(i.id), COALESCE(sum(i.amount_minor), 0), count(*) FILTER (WHERE r.status = 'PENDING'),
               count(*) FILTER (WHERE r.status = 'SUCCEEDED'), count(*) FILTER (WHERE r.status = 'FAILED'),
               COALESCE(sum(i.amount_minor) FILTER (WHERE r.status = 'SUCCEEDED'), 0)
          INTO items, total, pending, succeeded, failed, succeeded_minor
          FROM payroll_batches b
          LEFT JOIN payroll_items i ON i.batch_id = b.id
          LEFT JOIN payroll_item_results r ON r.item_id = i.id
         WHERE b.id = target
         GROUP BY b.id;
        IF iteration > 5 THEN
            INSERT INTO r2p1_timing VALUES (current_setting('r2p1.stage'), 'R1_VIEW', iteration - 5,
                extract(epoch FROM clock_timestamp() - started) * 1000);
        END IF;
    END LOOP;
END $$;

\if :is_v7
PREPARE r2_counts(uuid, uuid) AS
SELECT (SELECT count(*) FROM payroll_items WHERE batch_id = $1) AS item_count,
       count(*) AS result_count,
       count(*) FILTER (WHERE status = 'PENDING') AS pending_count,
       count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded_count,
       count(*) FILTER (WHERE status = 'FAILED') AS failed_count
FROM payroll_item_results
WHERE batch_id = $2;

PREPARE r2_view(uuid, uuid, uuid) AS
WITH item_totals AS (
    SELECT count(*) AS item_count, COALESCE(sum(amount_minor), 0) AS total_minor
    FROM payroll_items
    WHERE batch_id = $1
), result_totals AS (
    SELECT count(*) FILTER (WHERE r.status = 'PENDING') AS pending_count,
           count(*) FILTER (WHERE r.status = 'SUCCEEDED') AS succeeded_count,
           count(*) FILTER (WHERE r.status = 'FAILED') AS failed_count,
           COALESCE(sum(i.amount_minor) FILTER (WHERE r.status = 'SUCCEEDED'), 0) AS succeeded_minor
    FROM payroll_item_results r
    JOIN payroll_items i ON i.id = r.item_id AND i.batch_id = r.batch_id
    WHERE r.batch_id = $2
)
SELECT b.id, b.organization_id, b.name, b.payer_account_id, b.status,
       item_totals.item_count, item_totals.total_minor,
       result_totals.pending_count, result_totals.succeeded_count, result_totals.failed_count,
       result_totals.succeeded_minor,
       b.created_by_customer_id, b.submitted_by_customer_id, b.approved_by_customer_id,
       b.rejected_by_customer_id, b.rejection_reason, b.content_digest,
       b.approved_content_digest, b.created_at, b.submitted_at, b.approved_at,
       b.rejected_at, b.started_at, b.completed_at
FROM payroll_batches b
CROSS JOIN item_totals
CROSS JOIN result_totals
WHERE b.id = $3;

\echo '== R2_COUNTS warm-up result (expected 5000 items / 5000 results / 3000 pending / 0 succeeded / 2000 failed)'
EXECUTE r2_counts(md5('r2p1-batch-0')::uuid, md5('r2p1-batch-0')::uuid);
EXECUTE r2_counts(md5('r2p1-batch-0')::uuid, md5('r2p1-batch-0')::uuid);
EXECUTE r2_counts(md5('r2p1-batch-0')::uuid, md5('r2p1-batch-0')::uuid);
EXECUTE r2_counts(md5('r2p1-batch-0')::uuid, md5('r2p1-batch-0')::uuid);
EXECUTE r2_counts(md5('r2p1-batch-0')::uuid, md5('r2p1-batch-0')::uuid);
EXECUTE r2_view(md5('r2p1-batch-0')::uuid, md5('r2p1-batch-0')::uuid, md5('r2p1-batch-0')::uuid);
EXECUTE r2_view(md5('r2p1-batch-0')::uuid, md5('r2p1-batch-0')::uuid, md5('r2p1-batch-0')::uuid);
EXECUTE r2_view(md5('r2p1-batch-0')::uuid, md5('r2p1-batch-0')::uuid, md5('r2p1-batch-0')::uuid);
EXECUTE r2_view(md5('r2p1-batch-0')::uuid, md5('r2p1-batch-0')::uuid, md5('r2p1-batch-0')::uuid);
EXECUTE r2_view(md5('r2p1-batch-0')::uuid, md5('r2p1-batch-0')::uuid, md5('r2p1-batch-0')::uuid);
\echo '== PLAN R2_COUNTS'
EXPLAIN (ANALYZE, BUFFERS, SETTINGS) EXECUTE r2_counts(md5('r2p1-batch-0')::uuid, md5('r2p1-batch-0')::uuid);
\echo '== PLAN R2_VIEW'
EXPLAIN (ANALYZE, BUFFERS, SETTINGS)
    EXECUTE r2_view(md5('r2p1-batch-0')::uuid, md5('r2p1-batch-0')::uuid, md5('r2p1-batch-0')::uuid);

DO $$
DECLARE
    target uuid := md5('r2p1-batch-0')::uuid;
    started timestamptz;
    items bigint; results bigint; pending bigint; succeeded bigint; failed bigint; total numeric; succeeded_minor numeric;
BEGIN
    FOR iteration IN 1..35 LOOP
        started := clock_timestamp();
        SELECT (SELECT count(*) FROM payroll_items WHERE batch_id = target), count(*),
               count(*) FILTER (WHERE status = 'PENDING'), count(*) FILTER (WHERE status = 'SUCCEEDED'),
               count(*) FILTER (WHERE status = 'FAILED')
          INTO items, results, pending, succeeded, failed
          FROM payroll_item_results WHERE batch_id = target;
        IF iteration > 5 THEN
            INSERT INTO r2p1_timing VALUES (current_setting('r2p1.stage'), 'R2_COUNTS', iteration - 5,
                extract(epoch FROM clock_timestamp() - started) * 1000);
        END IF;
    END LOOP;
    FOR iteration IN 1..35 LOOP
        started := clock_timestamp();
        WITH item_totals AS (
            SELECT count(*) AS item_count, COALESCE(sum(amount_minor), 0) AS total_minor
              FROM payroll_items WHERE batch_id = target
        ), result_totals AS (
            SELECT count(*) FILTER (WHERE r.status = 'PENDING') AS pending_count,
                   count(*) FILTER (WHERE r.status = 'SUCCEEDED') AS succeeded_count,
                   count(*) FILTER (WHERE r.status = 'FAILED') AS failed_count,
                   COALESCE(sum(i.amount_minor) FILTER (WHERE r.status = 'SUCCEEDED'), 0) AS succeeded_minor
              FROM payroll_item_results r
              JOIN payroll_items i ON i.id = r.item_id AND i.batch_id = r.batch_id
             WHERE r.batch_id = target
        )
        SELECT item_totals.item_count, item_totals.total_minor, result_totals.pending_count,
               result_totals.succeeded_count, result_totals.failed_count, result_totals.succeeded_minor
          INTO items, total, pending, succeeded, failed, succeeded_minor
          FROM payroll_batches b CROSS JOIN item_totals CROSS JOIN result_totals
         WHERE b.id = target;
        IF iteration > 5 THEN
            INSERT INTO r2p1_timing VALUES (current_setting('r2p1.stage'), 'R2_VIEW', iteration - 5,
                extract(epoch FROM clock_timestamp() - started) * 1000);
        END IF;
    END LOOP;
END $$;

\echo '== Correctness: Round 1 and Round 2 counts for every batch in the database must agree'
SELECT b.name,
       (SELECT count(*) FILTER (WHERE r.status = 'PENDING') || '/' || count(*) FILTER (WHERE r.status = 'FAILED')
          FROM payroll_items i JOIN payroll_item_results r ON r.item_id = i.id WHERE i.batch_id = b.id) AS r1_pending_failed,
       (SELECT count(*) FILTER (WHERE status = 'PENDING') || '/' || count(*) FILTER (WHERE status = 'FAILED')
          FROM payroll_item_results WHERE batch_id = b.id) AS r2_pending_failed
  FROM payroll_batches b ORDER BY b.name;
\endif

\echo '== Server-side timings (ms) for 30 measured executions after 5 warm-ups'
SELECT stage, query, count(*) AS samples,
       round(percentile_cont(0.5) WITHIN GROUP (ORDER BY ms)::numeric, 3) AS p50_ms,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms)::numeric, 3) AS p95_ms,
       round(min(ms)::numeric, 3) AS min_ms, round(max(ms)::numeric, 3) AS max_ms
  FROM r2p1_timing GROUP BY stage, query ORDER BY query;
\echo '== Financial isolation: the fixture created no journal entries, postings, or transfers'
SELECT (SELECT count(*) FROM journal_entries) AS journal_entries, (SELECT count(*) FROM postings) AS postings,
       (SELECT count(*) FROM transfers) AS transfers;
