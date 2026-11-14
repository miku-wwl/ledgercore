# Architecture Audit and V2 Direction

## Verified V1 structure

LedgerCore is a Java 21 / Spring Boot modular monolith with Spring Modulith, Spring Security, JPA, JdbcTemplate, Flyway, PostgreSQL, and a React / TypeScript / Vite client.

Existing modules include identity, accounts, ledger, transfers, transactionhistory, and reconciliation. Corporate features belong in new organizations and payroll modules; corporate users must continue to post through the existing ledger. A second ledger is prohibited.

The current account row has a mandatory owner_customer_id foreign key. AccountEntity and the Account domain type also require a customer owner. A CUSTOMER_DEPOSIT ledger row references accounts through ledger_accounts.customer_account_id. This preserves one deposit ledger per account. V1 migrations are V1–V4.

Authenticated HTTP operations use a server-side CustomerPrincipal. Security permits only authentication bootstrap and API documentation anonymously; other routes require an authenticated session and state-changing requests use CSRF protection.

## Existing transfer boundary

TransferService.transfer is a READ_COMMITTED transaction. It checks the authenticated customer against the source account, reserves an idempotency key under (owner_customer_id, operation, idempotency_key), locks source and target ledger accounts in UUID order, verifies available balance, writes the journal and transfer record, and finalizes a durable success receipt in the same database transaction.

LedgerWriter uses sorted ledger-account row locks. It derives balance from authoritative postings and rebuilds account_balance_projection within the same transaction. PostgreSQL constraints/triggers enforce balanced entries, transfer-to-journal agreement, append-only financial history, and the idempotency success lifecycle.

## V2 design direction to validate during E1

- Keep accounts and the ledger unified. Add organizations and memberships as first-class entities.
- Extend account ownership with mutually exclusive personal-customer and corporate-organization owners, enforced by foreign keys and a database XOR constraint. Preserve existing personal rows and APIs through a forward-only Flyway migration.
- Continue to identify the authenticated human as a customer principal. Resolve corporate access from active organization membership and specific roles in the backend; never accept a caller-supplied owner ID as authorization.
- Preserve the current transfer path while replacing its personal-only source authorization with an explicit account-authorization service. Record the human actor separately from the account owner.
- Allow multiple membership roles as additive grants, but never let an operations role override financial approval. Enforce Maker/Checker by comparing the submitting actor with the approving actor, even if a person holds both roles.
- Payroll items should use stable batch/item identity and durable database state. Each approved item posts in its own transaction through shared ledger machinery. Batch orchestration must not hold one transaction across the whole payroll.

These are design constraints, not claims that V2 implementation is complete. Record final schema and transaction choices in ADRs after implementation and tests.
