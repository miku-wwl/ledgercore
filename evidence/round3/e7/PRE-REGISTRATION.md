# E7 reconciliation experiment pre-registration

- Owner: GPT-6 Luna.
- Recorded on: 2026-11-14 (NZDT), before E7 implementation tests or controlled anomaly runs.
- Branch: `main` only.
- Baseline SHA: `cd195aa891a9ad72158207d281c152f8bb86ee6f`.
- Scope: independent financial/cross-system reconciliation and controlled exception handling. Synthetic funds and identities only.
- Status at registration: **NOT_RUN**. No E7 anomaly or reconciliation experiment has been executed.

## Questions

1. Can a reconciliation implementation independently recompute the ledger balance from immutable postings and identify a deliberately changed balance projection without reporting a healthy fixture as discrepant?
2. Can it identify inconsistencies between transfer records, journal entries, idempotency receipts, payroll items/results, corporate payment intents, outbox records, clearing instructions, and the simulator's independently persisted operation state?
3. Can identified exceptions be assigned and resolved by an authorized organization operator with append-only audit evidence, without mutating posted financial history?

## Frozen method

- Use only fresh disposable PostgreSQL databases and the separate local clearing simulator database/process. Preserve the existing E5/E6 evidence and the persistent development database.
- Create healthy fixtures through real APIs: a customer transfer, a completed payroll item, and a corporate payment whose independent simulator outcome is queryable. Record all generated identities and starting balances before reconciliation.
- The reconciliation SQL will independently sum journal postings and join authoritative business rows. It will not call production balance-update or payment-booking methods to decide whether the database is correct. For clearing, compare bank-side rows with the simulator's authenticated inquiry response, not the bank's cached callback state alone.
- Run the healthy baseline first. Record finding count and category distribution. Expected result: no unexplained finding for each fixture whose lifecycle is terminal and internally consistent.
- In a separate disposable copy/run, change exactly one account's balance projection by +1 minor unit using a controlled fixture action. Do not alter postings, transfers, idempotency rows, payroll results, payment intents, or simulator state. Expected result: one projection mismatch identifying the account and expected/observed amounts; no unrelated finding. Recreate/restore the clean fixture before any other anomaly.
- For business/event anomalies, use only isolated fixtures. Prefer a supported fault seam or synthetic row when available. If a database constraint must be bypassed to represent an otherwise impossible corruption, use a fresh disposable database and a narrowly scoped, recorded setup/teardown procedure; do not weaken or edit a migration, and do not use `session_replication_role` or disable constraints unless the exact operation is proven to be confined to that disposable database and fully restored before grading. Report any unsafe or infeasible anomaly as NOT_RUN rather than substituting a different condition.
- Exercise exception assignment and resolution with an authenticated `OPERATIONS` actor; verify the authorization boundary with a non-operator. Resolution must retain the original expected/observed facts and append actor-attributed audit. No action may edit/delete journal entries or postings. A financial correction, if any is justified, must be a new balanced compensating entry and must be linked to the exception.

## Predeclared coverage

| Domain | Planned independent check | Planned anomaly/negative case | Expected verdict |
|---|---|---|---|
| Ledger and projection | Sum immutable debit/credit postings by ledger account and compare with projection | One-minor-unit projection offset | Detect exact account and difference; healthy accounts clear |
| Transfer and journal | Match transfer amount, source/target ledger postings, journal currency/type, idempotency success receipt | Missing/mismatched operation metadata, only if safely representable | Detect a named consistency category; otherwise mark anomaly NOT_RUN |
| Payroll | Match every item to its durable result, successful transfer, receipt and expected parties/amount; compare terminal batch status with item result counts | Missing result metadata and incomplete terminal batch, only in an isolated fixture | Detect missing/inconsistent item linkage; no duplicate debit |
| Corporate payment | Match immutable approved intent/digest/status with booking, settlement or refund journals | Wrong-beneficiary attempt or mismatched booking facts, only through controlled fixture | Reject unauthorized/altered intent or detect exact mismatch |
| Clearing | Compare instruction and bank callback/inbox state with independently persisted simulator inquiry; check settlement/refund journal against final external outcome | Missing callback, stale bank state, or out-of-order result where simulator can produce it | Detect divergence; UNKNOWN remains unresolved and is never refunded as if rejected |
| Durable events | Check deterministic required event keys and aggregate identities; detect duplicate/missing event facts where schema permits a safe fixture | Duplicate or missing event in a disposable fixture | Detect the event anomaly without repeating financial posting |
| Tenant boundary | Verify each organization-owned account, payroll batch, payment intent, clearing instruction and exception has matching organization ownership | Cross-organization fact mismatch attempt | Detect or reject; no cross-tenant detail disclosed to an unauthorized actor |
| Exception workflow | Verify category, severity, resource/operation, expected/observed facts, assignment, resolution and audit | Unauthorized assignment/resolution; unauthorized financial correction | Deny non-operator; preserve original evidence; correction must be separately balanced and linked |

## Measurements and acceptance rules

- Preserve complete request/response, generated IDs, reconciliation reports, independent SQL output, simulator inquiry output, audit rows, application logs, and process/database identity under ignored `work/e7-lab/runs/<RunId>/` plus a tracked summary under `evidence/round3/e7/`.
- Report healthy-fixture false positives as the exact count and categories. For each anomaly run, report whether the declared anomaly was detected, whether unrelated categories were emitted, and whether any expected anomaly was missed. Do not infer sensitivity for unexecuted cases.
- Acceptance requires an independently computed clean baseline, exact detection of the projection anomaly, financial-history immutability, a working authorized exception workflow, and real-postgres tests. Cross-system checks count as executed only if the simulator process/database is independent and its inquiry is authenticated.
- Reconciliation status will remain **PARTIAL** if any required domain or exception control is missing or not executed. No stage is VALIDATED until local evidence, direct-main commit/push, and exact-commit GitHub Actions pass.

## Safety limits

- Do not alter V1–V9 migration files. A schema change, if necessary, must be forward-only V10+ and tested against fresh and populated V9 databases.
- Do not inject anomalies into the persistent development database, a shared CI database, or the E5/E6 evidence runs.
- Do not repair/delete financial postings, weaken tenant guards, or claim clearing reconciliation from a callback cache.
- Keep the E7 false-positive/false-negative result separate from E5 simulator lifecycle evidence and E6 crash-recovery evidence.
