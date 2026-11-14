# LedgerCore V2 Plan B — Round 3 Handoff to Claude Opus 5.5

**Owner:** GPT-6 Luna
**Round:** 3 — E4–E11 engineering implementation and evidence
**Repository:** https://github.com/miku-wwl/ledgercore
**Branch policy:** `main` only; no branch, worktree, or PR
**Accepted Round 2 baseline:** `0db728171f7a28119ed4c4f19437896878f03fc6`
**Next owner:** Claude Opus 5.5 — Round 4

**E11 feature/evidence SHA:** `fdd4e0d7ef7aef7f944b24db5463efe9c34141b4`.
**E11 CI-fix and validated source SHA:** `de0624c34cbd11f7d847e5dc5c48ee5efc20a475`.
**Exact E11 CI:** [run 43626858589](https://github.com/miku-wwl/ledgercore/actions/runs/43626858589), both jobs passed.
**Round 3 result:** handoff ready. E4, E10, and E11 are VALIDATED; E5–E9 remain PARTIAL with their limits below.

## A. Repository state

- Repository URL: https://github.com/miku-wwl/ledgercore
- E11 feature/evidence commit: `fdd4e0d7ef7aef7f944b24db5463efe9c34141b4`. CI-fix and validated source SHA: `de0624c34cbd11f7d847e5dc5c48ee5efc20a475`; this exact `main` commit passed [43626858589](https://github.com/miku-wwl/ledgercore/actions/runs/43626858589).
- The acceptance and handoff records are a documentation-only follow-up committed directly on `main` after the validated source SHA; the final repository SHA and clean/synchronized state are reported after this commit's CI verification.
- E10 evidence commit `8f2dbee863d85978ed31aa0cc9b21e0ef0c5db05` passed exact GitHub Actions run [43624499785](https://github.com/miku-wwl/ledgercore/actions/runs/43624499785). E10 status-record commit `4052f1bd2c9760ff6d21dc95ca47bfce7839cb91` passed both jobs in exact run [43625116899](https://github.com/miku-wwl/ledgercore/actions/runs/43625116899).
- E11 implementation and CI setup are committed and pushed directly to `main`; both CI jobs passed, including backend and simulator verification, lint/build/OpenAPI, all 8 Chromium tests, and Maven OSV.
- No tag was created. The working tree was clean after the E11 commit; verify final `main`, `origin/main`, and clean status after publishing this handoff.
- Round 1 and Round 2 evidence, V1–V7 migrations, and the separate DevOps Delivery laboratory were not changed.

## B. Completed stages and evidence

| Stage | Status | Implementation, migrations, and tests | Evidence and limitations |
|---|---|---|---|
| E4 — durable events and approved-operation boundary | VALIDATED | V8 introduces Policy B payment intents and durable events. Payment approval and event facts are written transactionally; delivery is at least once with stable identity and idempotent processing. 78 backend tests; restart/SQL and browser verification. | Commit `0dfcb0c6afddb4bc10af98aa16eba4764a6eb02a`; CI [43574289360](https://github.com/miku-wwl/ledgercore/actions/runs/43574289360). `evidence/round3/e4/LOCAL-VALIDATION.md`. |
| E5 — independent clearing | PARTIAL | V9 hardens callbacks; separate simulator process/database persists operations and callback outbox. Authenticated stable-identity settlement, rejection, UNKNOWN inquiry, restart, and two-process recovery were exercised. Bank 81 tests; simulator 4 tests. | CI [43580338058](https://github.com/miku-wwl/ledgercore/actions/runs/43580338058). `evidence/round3/e5/LOCAL-VALIDATION.md`. Naturally underfunded reserve behavior remains unexercised. |
| E6 — fault injection | PARTIAL | Three actual personal-transfer process-kill windows: before commit, after commit/before response, and durable outbox/before dispatch. Each used independent PostgreSQL grading followed by process restart/replay; post-fix backend clean verify 82/82. | Commit `e7c2bafb8b0eedf10d725c63568e2dc52529ea19`; CI [43584590857](https://github.com/miku-wwl/ledgercore/actions/runs/43584590857). Payroll failure-mark and broader clearing/database/lock/worker/migration faults remain unrun. `evidence/round3/e6/`. |
| E7 — reconciliation | PARTIAL | Ledger/projection, payroll-result, durable-event, organization-ownership, and independent simulator checks; audited exception assignment/resolution. Tests detected exact projection drift and exact missing callback; settled two-process fixture returned clean. | Commit `19ddf2140ff9efd0d3f761da2680e78ed81afd4e`; CI [43591228075](https://github.com/miku-wwl/ledgercore/actions/runs/43591228075). 83 backend tests. Missing-result, incomplete-terminal, event-corruption, stale/out-of-order, wrong-beneficiary, and corrupted-ownership fixtures remain unrun. `evidence/round3/e7/`. |
| E8 — security and audit | PARTIAL | Policy B roles, immutable approval, role redaction, tenant concealment, suspension/reactivation, CSRF/session rotation, and constrained Operations payroll recovery were exercised. 87 backend tests plus 3 focused live API/SQL probes. | Commit `3a1beed048a878077865d571d50dac0060470e87`; CI [43596797609](https://github.com/miku-wwl/ledgercore/actions/runs/43596797609). Full role × route/callback/logging matrix remains incomplete. `evidence/round3/e8/`. |
| E9 — observability/performance | PARTIAL / CLOSED | Pool-wait and database-lock observations; structured correlation logs; R2-P2 projection-read supplement; R2-P3 index candidate tested and rejected; five Prometheus rules fired and cleared on synthetic fixtures. V10 adds the candidate index; V11 removes it. | CI [43603381726](https://github.com/miku-wwl/ledgercore/actions/runs/43603381726), [43605692774](https://github.com/miku-wwl/ledgercore/actions/runs/43605692774), and [43606784685](https://github.com/miku-wwl/ledgercore/actions/runs/43606784685). W3C trace propagation and retained spans remain unimplemented. `evidence/round3/e9/`. |
| E10 — PostgreSQL recovery | VALIDATED | Plain physical backup, SHA-256 manifest, `pg_verifybackup`, base restore/app, post-backup named-target PITR, independent SQL grades, and separate simulator settlement/callback replay. No schema migration added. | Evidence commit `8f2dbee863d85978ed31aa0cc9b21e0ef0c5db05`; CI [43624499785](https://github.com/miku-wwl/ledgercore/actions/runs/43624499785). RPO 118.922s; RTO 670.008s. One local run only. Raw artifacts: `evidence/round3/e10/raw/`. |
| E11 — full stack | VALIDATED | Corporate Payments and Operations Console, role-aware responsive navigation and role management, API-backed metrics/search/audit/timeline/retry/inquiry/reconciliation, simulator callback-sequence regression fix. Local backend 95/95; simulator 6/6; frontend lint/build/OpenAPI; real-API Chromium 8/8. | Feature/evidence commit `fdd4e0d7ef7aef7f944b24db5463efe9c34141b4`; CI-fix/validated commit `de0624c34cbd11f7d847e5dc5c48ee5efc20a475`; exact CI [43626858589](https://github.com/miku-wwl/ledgercore/actions/runs/43626858589) passed both jobs. The first CI attempt [43625867646](https://github.com/miku-wwl/ledgercore/actions/runs/43625867646) exposed missing simulator startup in the workflow; the validated commit starts it with its isolated database and callback configuration. Genuine screenshots and source register: `evidence/round3/e11/`. Local accessibility checks cover semantic labels/roles, Tab focus-visible, and 390px overflow. No screen-reader/axe or broad browser/device matrix. |

## C. Financial accounting design

Policy B is specified in [`ADR/0005-corporate-payment-approval-and-clearing.md`](ADR/0005-corporate-payment-approval-and-clearing.md). The authoritative ledger and all booking effects remain in the bank PostgreSQL database.

- **Personal transfer:** one bank transaction locks/validates the source, posts balanced debit/credit journals, updates its projection, records the idempotency receipt, and creates durable outbox facts. A retry with the same key returns the original result; a new operation is not inferred from a lost response.
- **Payroll:** the approved immutable batch/item determines debit authority. Each item uses `payroll-{batchId}-{itemId}` and atomically commits its transfer postings, receipt, and succeeded result. Completion is bounded to the batch's complete result set.
- **Corporate outgoing payment:** creator and submitter cannot self-approve. An independently approved immutable payment digest is validated under the bank transaction before a single booking: debit `CUSTOMER_DEPOSIT`, credit `CLEARING_PAYABLE`. The generic transfer API cannot debit a corporate source account.
- **Accepted/pending clearing:** the customer debit has already been booked, but the clearing payable remains until a definite external result. `ACCEPTED`, `PENDING`, timeout, and `UNKNOWN` do not book a second payment, refund, or settlement.
- **Settlement:** one journal debits `CLEARING_PAYABLE` and credits the finite debit-normal `SIMULATION_RESERVE` asset. Settlement is refused if the authoritative reserve is insufficient. Duplicate or replayed callbacks have no second financial effect.
- **Definitive rejection before settlement:** reverse the payable to the customer's deposit liability in a balanced journal. A rejection after settlement is an exception; compensation requires a separately authorized reversal and is never automatic.
- **UNKNOWN outcome:** inquire against the original simulator operation ID, payment ID, amount, and intent digest. Resolve under that stable identity; never retry with a new financial identity or refund while the external state is unknown.

E10 reproduced the critical cross-system boundary: the bank restored to a point before a later personal transfer, while the independent simulator retained `SETTLED`. After bank recovery, callback sequence 1 `PENDING` and sequence 2 `SETTLED` were delivered in order. The payment finished `COMPLETED` once, with one booking journal, one settlement journal, no reversal, no clearing payable, and zero unbalanced journals/projection mismatches.

## D. Transaction and recovery boundaries

1. **Bank business transaction:** payment/batch state, authoritative journals, idempotency/result rows, and durable event rows commit or roll back together. The event is a fact of that commit; it never posts money by itself.
2. **Bank outbox dispatch:** after commit, the dispatcher claims and sends persisted events. Delivery is at least once; retry/dead-letter state is durable, IDs are stable, and consumers make repeat delivery idempotent.
3. **External clearing:** the simulator has a separate process, database, and operation lifecycle. The bank sends a stable identity and immutable intent digest. A transport timeout remains UNKNOWN until an inquiry resolves it.
4. **Callback path:** simulator callback outbox delivery is independent of bank availability. The bank authenticates and records callback inbox identity/sequence, then applies valid transitions and any settlement journal in a bank transaction. Duplicate/stale callbacks cannot post twice.
5. **Reconciliation:** independent SQL and API checks compare ledger-derived balances, projections, payment state, simulator state, callbacks, and durable events. Corrections are role-gated, assigned, and audited; resolving an exception requires a fresh clean scan.
6. **PITR:** restore only the bank physical backup/WAL chain. The simulator is not rewound. After bank app readiness, re-observe external state using the same operation identity and deliver its persisted callback sequence; do not re-book the original payment.

## E. Security and audit

- Policy B separates `PAYMENT_MAKER` and `PAYMENT_APPROVER`; creator/submitter cannot approve their own immutable intent.
- `ORG_ADMIN` cannot self-grant sensitive roles. Sensitive grants/revocations are attributable. A suspended membership is not silently reactivated by add-member.
- Active organization membership scopes accounts, operations, audit, and reconciliation. Cross-organization Operations queries conceal the resource. A non-Operations member is denied by the backend, not just by hidden UI controls.
- Payroll remains separately governed by `PAYROLL_MAKER`/`PAYROLL_APPROVER` and the approved batch/item; generic transfers cannot claim the reserved `payroll-` idempotency namespace.
- Audit output redacts internal details; correlation search uses exact payment, operation, event, or correlation IDs.
- E8 is PARTIAL: do not treat exercised routes as proof of a complete role × endpoint × callback × logging matrix. The remaining matrix and threat-model gaps are in `evidence/round3/e8/THREAT-MODEL.md` and `LOCAL-VALIDATION.md`.

## F. Tests, measurements, and evidence

- **Backend:** current E11 source `mvn clean verify` passed 95 tests (fresh `ledgercore_e11_verify`, 0 failures/errors/skips). Earlier stage counts are in the stage-local validation files.
- **Simulator:** current E11 simulator `mvn clean verify` passed 6 tests (fresh `ledgercore_e11_sim_verify`, 0 failures/errors/skips); includes repeated terminal inquiry/replay sequence stability.
- **Frontend:** `npm run lint`, `npm run build`, and OpenAPI TypeScript generation from the running V11 API passed.
- **Browser:** 8 Chromium tests passed, covering personal banking; corporate onboarding, approval, settlement and self-approval rejection; payroll creation/approval/recovery; UNKNOWN inquiry; reconciliation exception; cross-organization isolation; unauthorized Operations action; and role revocation during a pending operation. The final transcript is `evidence/round3/e11/logs/playwright-e11-final.log`.
- **UI review:** inspected genuine 1280×720 corporate payments/operations screenshots and 390×844 operations mobile screenshot. Mobile navigation was visible, document width did not exceed 390px, and Tab focus had a visible `:focus-visible` style. Automated axe/WCAG and screen-reader testing were not run.
- **E6 fault injection:** three personal-transfer process-kill cases passed with independent SQL grades and replay. Broader registered failure types remain unexecuted.
- **E9 performance:** three fresh candidate databases completed 19,500 synthetic payroll items without unbalanced journals or projection mismatches. The V10 pending-only index was rejected under its preregistered keep rule and removed in V11. The R2-P2 projection-read supplement completed 12 fixtures with zero projection mismatches; at 100,000 postings authoritative SQL p50/p95 was 39.165/51.389 ms and projection-read p50/p95 was 0.330/0.558 ms. These are local measurements, not capacity claims.
- **E10 recovery:** 1,379-file/35,161,072-byte physical base backup completed in 3.958s; `pg_verifybackup` passed in 0.797s. The named-target recovery point was LSN `0/2A0000C8`; observed RPO 118.922s and RTO 670.008s follow the frozen definitions. Final grade: 0 unbalanced journals, 0 projection mismatches, 10/10 bank outbox events and 2/2 simulator callbacks delivered, post-target transfer absent, one settlement.
- **CI:** E10 full-stack/security plus Maven OSV run [43624499785](https://github.com/miku-wwl/ledgercore/actions/runs/43624499785) passed, followed by status-record run [43625116899](https://github.com/miku-wwl/ledgercore/actions/runs/43625116899). E11 initial run [43625867646](https://github.com/miku-wwl/ledgercore/actions/runs/43625867646) failed because the browser-check job did not start the independent clearing simulator; the corrected workflow in `de0624c` starts it against its isolated PostgreSQL database and configures the shared HMAC/callback path. Exact rerun [43626858589](https://github.com/miku-wwl/ledgercore/actions/runs/43626858589) passed both jobs; Playwright reported 8 passed in 1.5 minutes.

## G. Known problems, limits, and remaining scope

- E5 has no naturally underfunded reserve workflow; injected reserve-insufficient handling is not a substitute for a business path that drains reserve.
- E6 process-kill evidence covers three personal-transfer windows. Payroll failure marking and broader clearing/database/worker/migration faults are unrun.
- E7's listed anomaly classes remain untested; do not imply exhaustive reconciliation coverage.
- E8's broad role/route/callback/logging matrix and complete threat matrix remain incomplete.
- E9 has structured correlation logs and metrics but no W3C trace propagation/retained spans. The candidate index was rejected; do not describe it as an optimization.
- E10 is one local Windows/PostgreSQL 18.7 recovery. It does not establish production RPO/RTO, off-host backup durability, repeated-run variance, or recovery from arbitrary storage corruption.
- E11 accessibility review did not include screen reader, axe/WCAG automation, or a broad browser/device matrix. The Operations audit/history view is intentionally long and should be challenged for usability and pagination in Round 4.
- E11 CI proves the checked browser journeys against the independent simulator in CI; it does not close the explicitly listed accessibility, matrix-coverage, performance, or recovery limitations.
- Round 4 scope remains E12, independent final engineering audit and remediation, and Chinese professional V2 PDF publication. Nothing in Round 3 claims E12 or textbook publication is complete.

## H. Independent review priorities for Opus 5.5

Please independently challenge:

1. Financial accounting for pending clearing.
2. Exactly-once financial effect despite duplicate messages.
3. UNKNOWN outcome handling.
4. Cross-system reconciliation.
5. Corporate payment approval enforcement.
6. Role self-grant prevention.
7. Transaction boundaries.
8. Event commit/dispatch recovery.
9. Database PITR validity.
10. Negative test sufficiency.
11. Financial compensation semantics.
12. Evidence authenticity.
13. Performance experiment validity.
14. API and React workflow consistency.
15. Publication source and evidence readiness.

## I. Next-round instruction

> Round 3 was executed by GPT-6 Luna and covered E4–E11. The next owner is Claude Opus 5.5. Round 4 is responsible for independent final engineering audit, reproduction and remediation of significant defects, E12 comprehensive practical assessment, and publication of the professional Chinese V2 PDF textbook. Round 4 must use the actual accepted source and preserved experimental evidence. Do not claim success for unexecuted work.

Round 3 stopped before E12, textbook authoring, or final PDF generation, as required.
