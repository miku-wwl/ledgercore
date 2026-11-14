# E7 local validation — independent reconciliation

**Verdict: PARTIAL.** Implementation, a clean healthy path, one controlled projection anomaly, one cross-process missing-callback anomaly, and the authorized exception workflow were exercised. The remaining pre-registered corruption cases are listed below and were not inferred from the cases that did run. Code/evidence commit `19ddf2140ff9efd0d3f761da2680e78ed81afd4e` was pushed directly to `main`; [GitHub Actions run 43591228075](https://github.com/miku-wwl/ledgercore/actions/runs/43591228075) passed full-stack acceptance and OSV.

## Implementation

The bank-side reconciliation code uses organization-scoped SQL to independently compare ledger postings with projections, transfer/journal/receipt linkage, payroll items/results and terminal batch state, corporate payment approval digests and journals, required outbox event keys, and ownership boundaries. When configured, it queries the separate clearing simulator using authenticated inquiry and compares operation identity, digest, sequence, state, and reference. Operations endpoints persist findings and allow authorized listing, assignment, and resolution with actor-attributed audit events. Expected and observed facts remain unchanged after resolution.

No E7 migration was needed. V1–V9 remain unchanged; the implementation uses the schema already present in V8/V9. The source tree contains no E7 schema migration.

## Local verification

- Backend: `backend\mvnw.cmd --batch-mode --no-transfer-progress -f backend/pom.xml clean verify`, Java 21.0.13, fresh disposable PostgreSQL 18.7 database `ledgercore_e7_finalverify_20261115` on port 55438. Result: **83 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**. Flyway applied and validated V1–V9. Per-class Surefire summaries are preserved under the ignored `work/e7-lab/runs/20261115-final-backend-verification/` directory.
- Reconciliation integration test: healthy organization with a completed synthetic payroll batch returned zero findings; after changing only the customer balance projection by +1 minor unit, the scan returned exactly one `LEDGER_PROJECTION_MISMATCH`. The expected ledger amount was 99,000 minor units and the observed projection was 99,001. The test rebuilt only the derived projection from immutable postings, rescanned to zero findings, and verified the journal count and original exception facts were unchanged.
- Exception authorization: an Operations actor listed, assigned, and resolved the exception; assignment and resolution audit rows carry that actor. A Finance Viewer was denied listing, scanning, assignment, and resolution (403); an outsider received 404. Status filters returned the expected assigned/open results.
- Frontend: OpenAPI TypeScript generation succeeded; `npm run lint` and `npm run build` passed against the generated reconciliation contract.
- Preservation: `git diff --exit-code HEAD -- backend/src/main/resources/db/migration` passed; V1–V9 were not changed. `evidence/round3/e7/PRE-REGISTRATION.md` has the same Git blob as the committed pre-registration.

## Controlled outcomes

| Run | Independent evidence | Result |
|---|---|---|
| Healthy application fixture | Completed synthetic payroll, bank-side scan | 0 findings. The scan reported that no external simulator was configured for that test process. |
| Projection offset | Test-side SQL plus independent posting sum | Exactly one projection finding at +1 minor unit; projection-only repair restored a zero-finding scan; no journal entries or postings changed. |
| Healthy external payment | Two independently started Java processes, two PostgreSQL databases/roles, authenticated clearing inquiry; `work/e7-lab/runs/20261115-two-process-current/` | Corporate payment `13a02dd6-22ca-4523-9c16-a8d8432780db` completed/settled for 1,000 synthetic minor units; bank and simulator identities/digests/references matched; both journals balanced 1,000 debit to 1,000 credit; zero reconciliation findings and zero projection mismatches. |
| Missing callback | Separate bank/simulator processes and databases; `work/e7-lab/runs/20261115-missing-callback/` | After the bank instruction reached `SENT`, authenticated inquiry found simulator `SETTLED`, sequence 1, while the bank inbox had no callback: exactly one `CLEARING_CALLBACK_MISSING`, no unrelated finding. The simulator callback remained `PENDING`; the bank had one balanced booking journal and no settlement or refund journal. No automatic financial correction occurred. |

The missing-callback run's raw API response, bank SQL, simulator SQL, and both process logs are preserved in its ignored run directory. SQL independently shows the same operation ID and intent digest in each database, the simulator callback outbox at sequence 1/`PENDING`, the bank inbox empty, and the reconciliation exception `OPEN`. The scan did not treat the missing message as a rejection or refund.

An initial scan in that run began before asynchronous dispatch had completed. Its response was not preserved, so it is excluded from the measured outcome. The recorded outcome is the later scan performed after the bank instruction was observed as `SENT` and the simulator operation existed.

## Remaining pre-registered work

These cases were not run and remain outside the evidence-backed claims: a healthy customer-to-customer transfer fixture in the same reconciliation suite; missing payroll result metadata; an incomplete terminal payroll batch; wrong-beneficiary or altered booking facts; duplicate/missing required outbox event corruption; stale clearing state; out-of-order external results; and a cross-organization database fact mismatch. The test covers unauthorized cross-tenant reads, not corrupted ownership rows. No constraints or migrations were weakened to manufacture impossible states.

The E7 stage remains **PARTIAL** because those declared gaps remain unrun. The exact pushed commit's CI succeeded; this record does not claim comprehensive anomaly coverage.
