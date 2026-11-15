# ADR 0006: Durable Ownership of Clearing Financial Effects, Finite Reserve and Verified Resolution

- Status: Accepted (Round 4). Remediation commit `d52920840452fabfab808c8801b0f21fdd08a846`, CI run 43683368640; post-fix
  lab evidence in `evidence/round4/remediation/`.
- Date: 2026-11-15.
- Refines: ADR 0005 decisions 4, 6 and 7 (the effect of a terminal clearing outcome). Does not change Policy B.

## Context

Round 4 reproduced, with real process kills and a terminated database session, that an authenticated `SETTLED`
callback could be committed and acknowledged while its settlement journal was never posted (R4-01). The only code that
applied the effect was an `@TransactionalEventListener(AFTER_COMMIT)`; Spring 7.0.10 runs such listeners inside
`afterCompletion` and logs and swallows their exceptions, so the HTTP answer was still `200 ACCEPTED`. The durable
`clearing.callback.accepted` outbox event written in the same transaction was consumed by a receipt-only observer.
Reconciliation did not look for terminal outcomes without their journal (R4-03). The insufficient-reserve branch could
not occur at all (R4-02), and callbacks that exhausted the simulator's delivery budget, or were delivered to a bank that
was later restored, could never be delivered again (R4-04, R4-07).

## Options considered

1. **A second, dedicated effect queue table.** Clear semantics, but a second claim/lease/retry mechanism next to the
   existing outbox, two places to monitor and two retry policies to keep consistent.
2. **A periodic recovery scanner** looking for `SETTLED` payments without settlement. Simple, but polling the payment
   table every few seconds, no per-item attempt history and no dead-letter state; easy to make a busy loop.
3. **Reuse the durable outbox event that is already committed with the callback** and give it a real consumer. The
   work item exists atomically with the outcome by construction; the outbox already provides `SKIP LOCKED` claims, a
   30-second lease, exponential backoff, an attempt limit, `DEAD` state, governed retry and metrics.

## Decision

1. Option 3. `ClearingCallbackEffectOutboxHandler` consumes `clearing.callback.accepted` (and `V12`'s
   `clearing.financial-effect.backfill`) and applies the effect through `ClearingFinancialEffects`. The handler is
   registered even when the simulator integration is disabled.
2. The decision what to post is derived only from the locked payment row (`ClearingFinancialEffectProcessor`): the
   after-commit fast path, the durable worker, a duplicate callback, a second worker after a lost lease and an operator's
   dead-letter retry are therefore idempotent against each other. A `DELIVERED` outbox event means "the handler finished",
   never "money settled"; reconciliation checks journals, not event states.
3. The after-commit listener stays as a low-latency fast path that never throws; the durable event remains the owner.
4. A valid but currently impossible effect (reserve shortage) throws `ClearingEffectDeferredException`, records one
   CRITICAL `CLEARING_RESERVE_INSUFFICIENT` exception and is retried until it dead-letters. No compensating journal and no
   refund are ever produced automatically; a settled payment is never refunded.
5. `V13` gives the bank a second simulated asset (`SIMULATION_TREASURY`) and a balanced `RESERVE_LIQUIDITY_TRANSFER` so
   the settlement reserve is genuinely finite. Only the guarded `reserve-lab` command can move liquidity; there is no HTTP
   route and every movement is audited. Real production liquidity management is out of scope.
6. Reconciliation reports `CLEARING_FINANCIAL_EFFECT_PENDING` and `CLEARING_INSTRUCTION_NOT_DISPATCHED`; severity may only
   rise; an exception is resolved only after an independent recheck (SQL, authenticated simulator inquiry, or a completed
   settlement), and the recheck is written into the actor-attributed audit.
7. The simulator re-arms callbacks an authenticated inquiry shows the bank cannot hold (`DEAD`, or `DELIVERED` after the
   sequence the bank reports), keeping callback id, sequence and signed payload and counting the redelivery.
8. `V15` binds terminal payment states to their journals in PostgreSQL (R4-06).

## Consequences

- A crash at any point after the callback commit, a failed fast path, a lost lease or a restore no longer loses money
  movement; recovery needs neither simulator redelivery nor an operator, except for causes the system cannot fix itself
  (a reserve shortage needs replenishment and a governed retry).
- The effect's retry budget is the outbox's (5 attempts, backoff 1–8 s, then `DEAD`). A longer outage of a dependency
  dead-letters the effect; it stays visible as CRITICAL and is retried by an `OPERATIONS` member. Tuning a per-type budget
  is a possible later refinement.
- `DELIVERED` callbacks are re-armed only on an authenticated inquiry, so a peer cannot trigger redelivery storms.
- `FAILED` corporate payments are no longer representable; a future failure path must refund the booking first.
