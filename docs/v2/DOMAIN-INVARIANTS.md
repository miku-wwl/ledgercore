# Financial and Authorization Invariants

| ID | Invariant | V1 evidence / V2 acceptance rule |
|---|---|---|
| INV-01 | Each committed journal entry balances | V1 deferred PostgreSQL constraint trigger and ledger tests; extend with corporate and payroll cases |
| INV-02 | Committed journal entries and postings are immutable | V1 append-only database triggers; corrections use reversing entries |
| INV-03 | Money uses exact integer minor units | V1 Money domain tests and BIGINT amount_minor columns; validate overflow and payroll input precision |
| INV-04 | No unauthorized overdraft | V1 locks deposit ledgers in sorted UUID order, then reads authoritative ledger balance before posting; test corporate concurrency |
| INV-05 | Business operations are idempotent | V1 transfer key is scoped by actor/customer, operation, and key; payroll must have a stable item identity and durable result |
| INV-06 | Each payroll item is atomic | Not implemented. Item postings, progress state, and receipt must share one clearly defined transaction boundary |
| INV-07 | Approval is valid and independent | Not implemented. Submitted maker and approver identities must differ; only authorized approvers may decide |
| INV-08 | Approved payroll intent cannot be silently changed | Not implemented. Bind approval to immutable snapshot, version, or canonical digest |
| INV-09 | Organization resources are isolated | Not implemented. Every corporate object query and command must check active membership and role |
| INV-10 | Partial execution is recoverable | Not implemented. Durable item states and safe claiming/retry must prevent duplicate committed posting after restart |
| INV-11 | Projections reconcile independently to the ledger | V1 projection is rebuilt from authoritative postings; retain independent reconciliation and test it after payroll |
| INV-12 | V1 personal behavior remains correct | Run the existing regression, PostgreSQL, API, and browser tests after each migration |

No V2 invariant is marked implemented or accepted until its actual database and application behavior is tested. Financial evidence must report database state, not only HTTP responses.
