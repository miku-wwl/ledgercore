# E6 Financial Fault Laboratory Pre-registration

- Registered: 2026-11-14, before E6 fault runs.
- Status: pre-registered; no E6 fault run has occurred.

## Question

Do representative crashes and transport/database failures leave a recoverable business state while preserving all financial invariants and audit identity?

## Safety and controls

Each case must verify an isolated disposable DB name/port and explicit opt-in test configuration before mutation; snapshot balances, postings, receipts, outbox, clearing state and audit records; inject one named fault; restart/restore only that case's process/data; independently grade with PostgreSQL queries; emit JSON verdict; and clean only a verified target under the task's dedicated work directory. If intended injection did not occur, verdict is NOT_INJECTED, never PASS. Production hooks remain off by default.

## Case inventory to implement

Pre-commit crash; post-commit/pre-response; post-commit/pre-dispatch; duplicate callback; clearing timeout before acceptance; timeout after acceptance; database connection loss; lock wait/timeout; worker interruption between transfer rollback and payroll failure marking; migration failure in an isolated schema; unknown-outcome inquiry/recovery.

## Verdict rules

PASS requires balanced journal entries, no duplicate effective payment, no overdraft, correct durable business/event/clearing state, attributable action and successful recovery. Preserve both successful and failed case artifacts. This is a plan, not an executed result.
