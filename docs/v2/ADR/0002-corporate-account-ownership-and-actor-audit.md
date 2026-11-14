# ADR 0002: Corporate Account Ownership and Transfer Actor Audit

- Status: Accepted for E1; independent Round 2 audit pending.
- Date: 2026-11-14.

## Context

V1 accounts have one personal customer owner. LedgerCore V2 needs organization-owned accounts while retaining the existing account, deposit-ledger, transfer, and authenticated-customer identity model. Transfer idempotency is scoped to the human actor, but audit and referential integrity also need to identify the source account's legal owner.

## Decision

1. Add `organizations`, `organization_memberships`, and `organization_membership_roles` in forward-only migration V5. Membership roles are additive grants.
2. Keep organization and personal accounts in the existing `accounts` and `ledger_accounts` structures. Add nullable `owner_organization_id`; enforce exactly one owner using `ck_accounts_exactly_one_owner` and foreign keys.
3. Keep `CustomerPrincipal` as the authenticated human. Do not accept a request-supplied owner ID. Organization access comes from active memberships and the backend role policy.
4. Preserve `transfers.owner_customer_id` as the human actor and idempotency owner. Add mutually exclusive source-owner snapshot fields and composite foreign keys tying the recorded owner to the actual source account.
5. Reuse the existing `CUSTOMER_DEPOSIT` ledger and transfer transaction. An `ORG_ADMIN` role alone is insufficient to debit; corporate source authorization requires `PAYROLL_MAKER`.
6. Keep the Maker/Checker identity rule as a separate authorization policy: the approver must have `PAYROLL_APPROVER` and must not be the maker. E2 will bind this rule to a persisted payroll approval workflow.

## Consequences

- Existing V1 accounts are preserved as personal accounts, and existing transfer actor/idempotency semantics are unchanged.
- The migration enforces owner exclusivity and source-owner referential integrity in PostgreSQL, not only in Java.
- Corporate account creation uses the shared account service and records its simulated opening funds through the existing ledger writer.
- Transfer rows can identify both who initiated a transfer and which organization owned its source account.
- E1 does not implement batch approval, immutable payroll snapshots, per-item execution, or restart recovery.

## Evidence and follow-up

- Migration: `backend/src/main/resources/db/migration/V5__add_organizations_and_corporate_account_ownership.sql`.
- PostgreSQL integration coverage: `backend/src/test/java/nz/ac/ledgercore/organizations/OrganizationPostgresIntegrationTests.java`.
- Local validation: `evidence/e1/VALIDATION.md`.
- Round 2 should challenge migration constraint behavior, role composition, authorization/query isolation, and source-owner audit semantics.
