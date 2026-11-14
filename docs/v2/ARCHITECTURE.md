# Architecture Audit and V2 Direction

## Verified V1 structure

LedgerCore is a Java 21 / Spring Boot modular monolith with Spring Modulith, Spring Security, JPA, JdbcTemplate, Flyway, PostgreSQL, and a React / TypeScript / Vite client.

Existing modules include identity, accounts, ledger, transfers, transactionhistory, and reconciliation. Corporate features belong in new organizations and payroll modules; corporate users must continue to post through the existing ledger. A second ledger is prohibited.

The current account row has a mandatory owner_customer_id foreign key. AccountEntity and the Account domain type also require a customer owner. A CUSTOMER_DEPOSIT ledger row references accounts through ledger_accounts.customer_account_id. This preserves one deposit ledger per account. V1 migrations are V1–V4.

Authenticated HTTP operations use a server-side CustomerPrincipal. Security permits only authentication bootstrap and API documentation anonymously; other routes require an authenticated session and state-changing requests use CSRF protection.

## Existing transfer boundary

TransferService.transfer is a READ_COMMITTED transaction. It checks the authenticated customer against the source account, reserves an idempotency key under (owner_customer_id, operation, idempotency_key), locks source and target ledger accounts in UUID order, verifies available balance, writes the journal and transfer record, and finalizes a durable success receipt in the same database transaction.

LedgerWriter uses sorted ledger-account row locks. It derives balance from authoritative postings and rebuilds account_balance_projection within the same transaction. PostgreSQL constraints/triggers enforce balanced entries, transfer-to-journal agreement, append-only financial history, and the idempotency success lifecycle.

## E1 corporate identity and authorization implementation

V5 adds organizations, active memberships, and additive membership-role grants. Accounts remain in the existing account and ledger tables; `ck_accounts_exactly_one_owner` plus the owner foreign keys enforce exactly one personal or organization owner. The migration is forward-only and leaves the V1–V4 migration files unchanged.

The authenticated human remains `CustomerPrincipal`. Organization administration requires `ORG_ADMIN`; viewing accounts requires active membership; corporate debit requires `PAYROLL_MAKER`. `ORG_ADMIN` alone does not grant debit authority. A nonmember receives the same not-found response for organization resources, while a member lacking an administrative role receives a forbidden response for member/account-management actions. The UI follows the same debit rule.

`transfers.owner_customer_id` remains the authenticated actor and continues to scope idempotency. V5 adds an XOR pair `source_owner_customer_id` / `source_organization_id`; composite foreign keys require the recorded source owner to match the actual source account. Transfers still use the existing transaction and ledger writer. Personal account transfers and opening-fund posting remain on the V1 path.

Maker/Checker identity comparison is implemented as an authorization policy and tested. The payroll batch workflow, approval snapshot, durable item identity, restart recovery, and transaction-per-item boundary remain E2 work and are not yet implemented.

See `docs/v2/ADR/0002-corporate-account-ownership-and-actor-audit.md` for the schema and identity decision. These facts describe the E1 implementation; E2/E3 invariants are not claims of completion.

These are design constraints, not claims that V2 implementation is complete. Record final schema and transaction choices in ADRs after implementation and tests.

## Round 2 independent audit changes

- V7 (`V7__harden_payroll_intent_and_result_integrity.sql`) makes PostgreSQL enforce payroll intent and result integrity
  independently of Java: item identity and batch membership are immutable; results are created PENDING in a DRAFT
  batch, retained while their item exists, change only while the batch is PROCESSING, and a SUCCEEDED result must
  reference the transfer and receipt that pay its own item; terminal batch states require a complete, consistent
  result set. `payroll_item_results.batch_id` makes progress queries batch-bounded.
- `LegacyTransferOwnerBackfillCallback` lets the unmodified V5 upgrade V4 databases that hold transfers.
- Payroll execution evaluates the batch creator's debit authority per item and records its loss as
  `DEBIT_AUTHORITY_REVOKED`; customer-supplied idempotency keys cannot use the `payroll-` namespace.
- The corporate payment policy is still Policy A (ADR 0002); owner decision OD-1 is open (`ROUND-2-AUDIT.md` §8).
- Decisions and the owner recovery procedure: `ADR/0004-payroll-integrity-hardening.md`.

