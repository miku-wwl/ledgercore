# Financial and Authorization Invariants

| ID | Invariant | V1 evidence / V2 acceptance rule |
|---|---|---|
| INV-01 | Each committed journal entry balances | V1 deferred PostgreSQL constraint trigger and ledger tests; extend with corporate and payroll cases |
| INV-02 | Committed journal entries and postings are immutable | V1 append-only database triggers; corrections use reversing entries |
| INV-03 | Money uses exact integer minor units | V1 Money domain tests and BIGINT amount_minor columns; validate overflow and payroll input precision |
| INV-04 | No unauthorized overdraft | V1 locks deposit ledgers in sorted UUID order, then reads authoritative ledger balance before posting; test corporate concurrency |
| INV-05 | Business operations are idempotent | V1 transfer key is scoped by actor/customer, operation, and key; payroll must have a stable item identity and durable result |
| INV-06 | Each payroll item is atomic | E2 local pass: transfer postings, receipt/idempotency record, and `SUCCEEDED` result share one item transaction; injected result-write failure rolled all of them back. See `evidence/e2/VALIDATION.md` |
| INV-07 | Approval is valid and independent | E2 local pass: Maker self-approval returned 403 in the browser; separate authorized approver succeeded; API and PostgreSQL integration tests passed |
| INV-08 | Approved payroll intent cannot be silently changed | E2 local pass: canonical SHA-256 digest is bound at approval; PostgreSQL immutability trigger and mutation-vs-approval lock race rejected late changes |
| INV-09 | Organization resources are isolated | E1 local validation: membership-gated organization/account queries; `ORG_ADMIN` manages, `PAYROLL_MAKER` debits; nonmember resource access is 404. Direct-main CI pending |
| INV-10 | Partial execution is recoverable | E2 local pass: actual Java process kill after 3 committed items left 497 pending; a restarted process completed the batch with no duplicate transfer. See `evidence/e2/VALIDATION.md` |
| INV-11 | Projections reconcile independently to the ledger | E2 local pass: 500-item and restart-recovery scenarios independently compared payer/payee projections with authoritative posting sums; zero mismatches |
| INV-12 | V1 personal behavior remains correct | Run the existing regression, PostgreSQL, API, and browser tests after each migration |

No V2 invariant is marked implemented or accepted until its actual database and application behavior is tested. Financial evidence must report database state, not only HTTP responses.
