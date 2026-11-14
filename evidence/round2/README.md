# Round 2 evidence (independent audit and remediation of E0–E3)

Owner: Claude Opus 5.5. Baseline audited: `b584b9570eee2d2b4c08505b19336b08dfc37a77`. Round 1 evidence under
`evidence/e0`–`evidence/e3` is preserved unchanged; Round 2 corrections to its interpretation are new files here.

| Path | Contents |
|---|---|
| `findings/FINDINGS-REGISTER.md` | Every Round 2 finding with severity, reproduction, root cause, remediation, tests, and disposition |
| `reproductions/` | Self-contained SQL reproductions (R2-01, R2-02, R2-08) with their output on the V6 baseline and after V7 |
| `regression/red-baseline-integrity-suite.txt` | The new payroll integrity suite run against the unmodified baseline: 19 tests, 12 failures |
| `regression/green-remediation-suite.txt` | Full `clean verify` after remediation: 75 tests, 0 failures |
| `migrations/` | Read-only V7 preflight script and its zero-violation output on the three Round 1 V6 databases |
| `acceptance/` | Final local acceptance record (`LOCAL-ACCEPTANCE.md`); CI results are in `docs/v2/ROUND-2-HANDOFF.md` |
| `performance/PRE-REGISTRATION.md` | R2-P1–R2-P3 hypotheses and decision rules, committed before measurement (`1b6ed68`) |
| `performance/ROUND-1-ANNOTATIONS.md` | Provenance checks and corrected interpretation of E3 H01–H04 |
| `performance/verdicts/` | R2-P1, R2-P2, R2-P3 verdicts |
| `performance/r2-p*/` | Raw Round 2 measurements, plans, and environment records |
| `performance/exploratory/` | Clearly labeled non-pre-registered measurement (V7 trigger cost) |

All data are synthetic. Every database used was a disposable `ledgercore_r2_*` database on the separate Round 2
PostgreSQL 18.7 cluster (`127.0.0.1:55436`, data directory `work/pg-round2-audit`, not committed). The Round 1 cluster
(55434) was accessed read-only; the persistent service (55432) was never contacted. Raw Surefire XML is not committed
because it embeds host system properties; sanitized summaries are.
