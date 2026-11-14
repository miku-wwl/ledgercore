# ADR 0005: Independently Approved Corporate Payments and External Clearing

- Status: Accepted for Round 3 implementation. E4 Policy B and durable-outbox core passed local and CI verification (commit `0dfcb0c`, run `43574289360`); clearing simulator and callback verification remains E5/E8.
- Date: 2026-11-14.
- Supersedes: ADR 0002 decision 5 only insofar as it allowed immediate corporate debit by `PAYROLL_MAKER` without an approved payment intent. Round 2's historical Policy A audit remains unchanged.

## Context

Round 2 found that a corporate `PAYROLL_MAKER` could debit an organization account through the general transfer API without independent approval. A first `ORG_ADMIN` could also self-grant payment roles, and `addMember` could silently reactivate a suspended membership. Those are unacceptable for the enterprise control model selected for Round 3.

The project uses one PostgreSQL ledger. V1–V7 are published and immutable. The ledger's posting transaction, append-only history, balanced-journal triggers, durable receipts, and payroll's approved-batch invariants remain authoritative.

Spring Modulith is pinned at 2.0.8. Its event publication registry is a viable transactional option: its JDBC implementation records listener publications in the original business transaction and supports persisted publication lifecycle/restart handling. Round 3 also needs one durable external dispatch record with actor/correlation attribution, retry scheduling, visible exhaustion, idempotent simulator delivery, and an HTTP boundary to a separately owned clearing simulator. To avoid two overlapping reliability stores, select a purpose-built PostgreSQL outbox as the single delivery queue. The evaluation is recorded here; no Kafka/RabbitMQ or duplicate Modulith registry is added.

## Decision

1. Adopt **Policy B**: every corporate outgoing payment requires an immutable independently approved intent. Payroll remains governed by its approved batch and item state.
2. Distinguish `PAYMENT_MAKER` / `PAYMENT_APPROVER` from `PAYROLL_MAKER` / `PAYROLL_APPROVER`. The approver must be a different actor from the creator and submitter. Approval binds to an immutable version and digest.
3. A regular transfer request cannot debit a corporate source account. The execution service derives permission by locking and validating the persisted approved intent. Payroll execution derives permission by validating the approved batch and exact item. Do not add caller-controlled approval booleans.
4. Record event facts in one transactional outbox, in the same transaction as the business fact that makes them true. Delivery is at least once, with stable IDs, bounded retries, visible dead-letter state, restart recovery and consumer idempotency. Events and callbacks never create the original payment booking.
5. Keep the clearing simulator independently restartable and persist its operation state in a separate database/connection. A transport timeout is UNKNOWN and is resolved under the original operation identity.
6. Book an external payment once, after independent approval and before dispatch: debit the customer's deposit liability and credit a dedicated `CLEARING_PAYABLE` liability. This makes funds unavailable before the external outcome. On settlement, debit that payable and credit the existing debit-normal `SIMULATION_RESERVE` asset, which represents the simulation's finite settlement funding. Refuse settlement if its authoritative ledger balance is insufficient. On a definitive rejection before settlement, reverse the payable to the customer's deposit liability. Never edit projections directly.
7. Duplicate callbacks and retries are idempotent by stable payment/clearing identity. A rejection after settlement is an exception, never an automatic refund. Compensation uses a separately authorized reversing journal entry.
8. All sensitive membership/role changes are audited. An administrator cannot grant a sensitive role to their own membership. Add-member does not reactivate a suspended member; reinstatement requires an explicit attributable action. The creator's initial `ORG_ADMIN` remains usable and can appoint a second administrator, who can establish distinct maker/approver identities.

## Consequences

- Corporate debit authorization moves from a role-only check to durable approved business state.
- Policy B is a new Round 3 decision, not a claim about Round 2 behavior.
- Outgoing external settlement consumes finite simulation reserve; tests must fund it and verify insufficient-funding behavior.
- Unknown results require inquiry/reconciliation and cannot be retried with a new financial identity.
- New schema objects must be introduced in V8 or later. V1–V7 remain unchanged and their checksums are pinned.
- Event delivery is at least once, not exactly once. Financial effects remain protected by database transactions and stable idempotency.

## Evaluation references

Spring Modulith 2.0 reference: [Working with Application Events](https://docs.spring.io/spring-modulith/reference/2.0-SNAPSHOT/events.html), [Spring Modulith 2.0.8 JDBC API](https://docs.spring.io/spring-modulith/docs/2.0.x/api/org/springframework/modulith/events/jdbc/package-summary.html). The chosen custom outbox is justified by the external HTTP delivery, retry/dead-letter visibility and actor attribution requirements; revisit only if the pinned-version implementation can satisfy them without a second queue.

## Verification required before acceptance

- Direct transfer API rejects an unapproved corporate debit; an approved payment succeeds; payroll and personal transfers still work.
- Maker/checker and immutable approval digest are enforced in application and PostgreSQL.
- Outbox rows roll back with their business transaction; committed rows survive restart; duplicate delivery has one effect.
- Clearing booking, settlement and rejection use balanced immutable journals and independent ledger-derived projections.
- Role self-grant, suspended-member reactivation, callback forgery, cross-organization access and operational retry are tested.
