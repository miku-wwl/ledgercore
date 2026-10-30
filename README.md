# LedgerCore

**Java Full-Stack Banking Application**

A full-stack banking simulation built with Spring Boot, React, shadcn/ui, and PostgreSQL, focused on double-entry ledgers, idempotent transfers, and transaction consistency.

## 1. Overview

LedgerCore is an independently designed full-stack banking simulation inspired by Google Cloud's Bank of Anthos.

The project explores the design and implementation of reliable transaction processing through financial domain modelling, Clean Architecture, database consistency, concurrency control, and automated testing.

LedgerCore uses simulated funds only. It is not intended for production banking or real financial transactions.

## 2. Objectives

- Design a modular Java backend with Clean Architecture and domain-driven design.
- Implement double-entry bookkeeping with verifiable accounting invariants.
- Build reliable, atomic, and idempotent account transfers.
- Study transaction isolation, locking, and concurrency in PostgreSQL.
- Implement balance queries, transaction history, and reconciliation.
- Develop a React frontend using official shadcn/ui components.
- Establish comprehensive automated tests, observability, and reproducible deployment.
- Evaluate engineering decisions using measurable experiments.

## 3. Technology Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Backend | Spring Boot 4.x |
| Architecture | Clean / Hexagonal Architecture |
| Modular Design | Spring Modulith |
| Database | PostgreSQL |
| Persistence | Spring Data JPA / Hibernate |
| Database Migration | Flyway |
| Security | Spring Security |
| API | REST, OpenAPI |
| Frontend | React 19, TypeScript, Vite |
| UI | Official shadcn/ui, Tailwind CSS |
| Server State | TanStack Query |
| Backend Testing | JUnit, Mockito, Testcontainers |
| E2E Testing | Playwright |
| CI/CD | GitHub Actions |
| Local Runtime | Docker Compose |

## 4. Business Modules

### Accounts
Account creation, ownership, status management, and balance retrieval.

### Ledger
Double-entry journal entries, immutable postings, and accounting invariant enforcement.

### Transfers
Internal fund transfers, idempotency keys, transaction boundaries, concurrency control, and failure handling.

### Transaction History
Consistent and paginated transaction history with efficient database queries.

### Reconciliation
Verification of materialized balances against ledger-derived balances and detection of discrepancies.

## 5. Architecture

LedgerCore will initially use a modular monolith.

Business modules will define their domain models, application use cases, ports, and infrastructure adapters.

Domain logic must remain independent of Spring Web, JPA, PostgreSQL, and frontend implementation details.

Architecture and module dependencies will be verified through automated tests.

## 6. Financial Invariants

The system must enforce:

- Balanced debit and credit postings for each journal entry and currency.
- Atomic posting of financial transactions.
- No unauthorized overdrafts.
- Idempotent handling of transfer requests.
- Immutability of posted journal entries.
- Explicit reversal entries rather than modification of posted history.
- Consistency between materialized balances and the authoritative ledger.
- Account-level authorization.

## 7. Engineering Experiments

| ID | Experiment |
|---|---|
| E01 | Architecture dependency verification |
| E02 | Double-entry accounting invariant tests |
| E03 | Concurrent transfer correctness |
| E04 | Persistent idempotency and request replay |
| E05 | Transaction rollback and failure injection |
| E06 | Account balance reconciliation |
| E07 | PostgreSQL indexing and query performance |
| E08 | Authentication and resource authorization |
| E09 | React API error recovery and state synchronization |
| E10 | Reliable module events and failure recovery |

Each experiment will include its objective, implementation, environment, test procedure, results, and technical conclusions.

## 8. Development Roadmap

- **P0 — Upstream Source Audit:** Review the relevant Bank of Anthos modules and establish the reference baseline.
- **P1 — Architecture Foundation:** Initialize the Spring Boot modular monolith, database, migrations, and architecture tests.
- **P2 — Ledger Domain:** Implement accounts, journal entries, postings, and accounting invariants.
- **P3 — Transaction Correctness:** Implement transfers, idempotency, locking, concurrency tests, and failure recovery.
- **P4 — Read Models:** Implement account balances, transaction history, reconciliation, and query optimization.
- **P5 — Full-Stack Integration:** Implement React and official shadcn/ui with typed REST API integration.
- **P6 — Engineering Quality:** Complete security, reliability, performance, observability, and CI/CD.
- **P7 — Final Evaluation:** Produce architectural evaluations, benchmark results, technical documentation, and a reproducible final implementation.

## 9. Non-Goals

The initial scope excludes:

- Real-money transactions
- Connections to personal bank accounts
- External settlement networks
- Foreign exchange and multi-currency conversion
- Loans, credit cards, and interest calculation
- Regulatory certification or production banking compliance
- Blockchain or cryptocurrencies
- AI agents
- Kubernetes platform engineering
- Premature microservice decomposition

## 10. Documentation

Planned documentation:

- docs/upstream/BANK-OF-ANTHOS-REVIEW.md
- docs/architecture/ARCHITECTURE.md
- docs/architecture/adr/
- docs/domain/LEDGER-INVARIANTS.md
- docs/database/SCHEMA.md
- docs/api/API-CONTRACT.md
- docs/testing/TEST-STRATEGY.md
- docs/experiments/
- docs/performance/
- docs/learning/LEARNING-JOURNAL.md

## 11. Attribution

LedgerCore is inspired by Google Cloud's Bank of Anthos:

https://github.com/GoogleCloudPlatform/bank-of-anthos

The upstream project is licensed under Apache License 2.0.

LedgerCore is not affiliated with or endorsed by Google. Any reused upstream code must comply with the applicable license and attribution requirements.

## 12. Current Status

**Planning — Repository Initialization**

Implementation, automated verification, and deployment have not yet been completed.
