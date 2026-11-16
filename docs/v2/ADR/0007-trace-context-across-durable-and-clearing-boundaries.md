# ADR 0007: W3C Trace Context Across Durable and Clearing Boundaries

- Status: Accepted (Round 4, Claude Opus 5.5); implemented in `292976b`, frozen in `2c25ef4`
- Related: E9; ADR 0006; `TraceContext`, `TraceContextFilter`, `SpanRecorder`, `OutboxDispatcher`,
  `ClearingSimulatorClient`; bank migration V14; simulator migration V4 and `SimulatorTrace`

## Context

Round 3 closed E9 metrics and alert rules but left trace propagation and retained spans unimplemented. A clearing
payment crosses three asynchronous boundaries — the approval request commits an outbox event, a dispatcher later calls
the independent simulator, and the simulator later delivers a signed callback — so a single request-scoped trace id
could not explain a stuck settlement. The lab has no tracing backend and must not depend on a paid service, and
identifiers supplied by clients must never become authority.

## Decision

1. Use W3C Trace Context (`traceparent` version `00`, lowercase hex, non-zero ids; `tracestate` passed through up to 512
   characters). Every bank and simulator request opens a SERVER span; invalid headers are ignored, never rejected.
   Responses carry `traceresponse`.
2. Persist the producing request's `traceparent` with each outbox event (bank V14, immutable with the other event facts)
   and with each simulator operation and callback (simulator V4).
3. Each dispatch or delivery attempt is a new trace (CONSUMER in the bank, PRODUCER in the simulator) linked to the
   stored producer context, instead of a child of a request that may have ended long before.
4. Record spans as structured log events (`ledgercore.trace`, `ledgercore.simulator.trace`) with trace, span and parent
   ids, kind, status, duration, links and a few attributes; `assessment/e9/extract-traces.py` rebuilds chains only from
   recorded parents and links.
5. A client-supplied trace or correlation id is never used for authorization, deduplication or tenant selection.
6. Metrics keep low-cardinality tags only; per-payment investigation uses spans, logs and reconciliation facts.

## Consequences

- One settlement can be followed from approval to the after-commit effect across both processes (E12-H04 chain:
  7 steps plus the durable work-item attempt), and every retry is visible as its own trace.
- Span volume is bounded by request and attempt counts; there is no sampling and no backend, which is adequate for the
  laboratory and is recorded as a limitation, not a production design.
- Persisted trace context becomes part of the immutable event facts; changing its format needs a new migration.
