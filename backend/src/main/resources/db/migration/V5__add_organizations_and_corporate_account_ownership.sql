CREATE TABLE organizations (
    id UUID PRIMARY KEY,
    display_name VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_organizations_name_not_blank CHECK (length(trim(display_name)) > 0),
    CONSTRAINT ck_organizations_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT ck_organizations_version_non_negative CHECK (version >= 0)
);

CREATE TABLE organization_memberships (
    organization_id UUID NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    customer_id UUID NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, customer_id),
    CONSTRAINT ck_organization_memberships_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

CREATE INDEX ix_organization_memberships_customer
    ON organization_memberships (customer_id, organization_id);

CREATE TABLE organization_membership_roles (
    organization_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (organization_id, customer_id, role),
    CONSTRAINT fk_membership_roles_membership
        FOREIGN KEY (organization_id, customer_id)
        REFERENCES organization_memberships (organization_id, customer_id) ON DELETE RESTRICT,
    CONSTRAINT ck_organization_membership_role CHECK (
        role IN ('ORG_ADMIN', 'PAYROLL_MAKER', 'PAYROLL_APPROVER', 'FINANCE_VIEWER', 'OPERATIONS')
    )
);

ALTER TABLE accounts ADD COLUMN owner_organization_id UUID;
ALTER TABLE accounts ALTER COLUMN owner_customer_id DROP NOT NULL;
ALTER TABLE accounts
    ADD CONSTRAINT fk_accounts_owner_organization
    FOREIGN KEY (owner_organization_id) REFERENCES organizations (id) ON DELETE RESTRICT;
ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_exactly_one_owner
    CHECK ((owner_customer_id IS NOT NULL) <> (owner_organization_id IS NOT NULL));

CREATE INDEX ix_accounts_owner_organization_created
    ON accounts (owner_organization_id, created_at DESC, id DESC)
    WHERE owner_organization_id IS NOT NULL;

ALTER TABLE accounts
    ADD CONSTRAINT uq_accounts_id_owner_organization UNIQUE (id, owner_organization_id);

ALTER TABLE transfers
    DROP CONSTRAINT fk_transfers_owned_source;
ALTER TABLE transfers
    ADD COLUMN source_owner_customer_id UUID;
ALTER TABLE transfers
    ADD COLUMN source_organization_id UUID;

UPDATE transfers
SET source_owner_customer_id = owner_customer_id;

ALTER TABLE transfers
    ADD CONSTRAINT ck_transfers_exactly_one_source_owner
    CHECK ((source_owner_customer_id IS NOT NULL) <> (source_organization_id IS NOT NULL));
ALTER TABLE transfers
    ADD CONSTRAINT fk_transfers_personal_source_owner
    FOREIGN KEY (source_account_id, source_owner_customer_id)
    REFERENCES accounts (id, owner_customer_id) ON DELETE RESTRICT;
ALTER TABLE transfers
    ADD CONSTRAINT fk_transfers_corporate_source_owner
    FOREIGN KEY (source_account_id, source_organization_id)
    REFERENCES accounts (id, owner_organization_id) ON DELETE RESTRICT;

COMMENT ON COLUMN transfers.owner_customer_id IS
    'Authenticated human actor who initiated the transfer; source ownership is recorded separately.';
COMMENT ON COLUMN transfers.source_owner_customer_id IS
    'Personal source account owner, null when the source is corporate.';
COMMENT ON COLUMN transfers.source_organization_id IS
    'Corporate source account owner, null when the source is personal.';
