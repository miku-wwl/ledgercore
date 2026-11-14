# ADR 0001: Corporate ownership and actor identity

- Status: Proposed for E1 validation.
- Context: V1 accounts require owner_customer_id. Transfers authorize the source against the authenticated customer, and transfer/idempotency records also retain the initiating customer identity. Corporate accounts must be owned by organizations while human actors remain authenticated customers.
- Decision direction: Reuse accounts and the existing customer-deposit ledger. Add a corporate organization owner alongside the personal owner with a database-enforced exclusive-owner constraint and separate foreign keys. Keep the authenticated human as the actor and resolve organization authorization from active membership and role records. Preserve personal semantics and forward-only Flyway history.
- Authorization: Backend services derive the actor from CustomerPrincipal. Organization ID in a route or request is only a selector; it never proves membership. Membership roles are additive, while Maker/Checker is a separate actor-identity rule. Operations permissions do not bypass approval.
- Transaction direction: Each payroll item uses a stable item identity and performs ledger posting plus durable result within one PostgreSQL transaction. Batch orchestration is not one long financial transaction.
- Consequences to test: Existing V1 account reads and transfers; foreign-key and XOR constraints; corporate account authorization; actor and account-owner distinction; idempotency scope; membership revocation behavior; Modulith dependencies; migration on populated V1 data.
- Open questions for implementation and Opus review: Whether to represent roles as a join table or separate membership grants; how actor identity is retained on corporate transfers; the exact policy when membership changes after batch approval; and the durable claim/lease model for payroll workers.

This is a direction to validate, not a claim that the proposed schema or behavior is implemented.
