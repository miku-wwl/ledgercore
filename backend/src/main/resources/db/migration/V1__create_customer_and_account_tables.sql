CREATE TABLE customers (
    id UUID PRIMARY KEY,
    email_normalized VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_customers_email_not_blank CHECK (length(trim(email_normalized)) > 0),
    CONSTRAINT ck_customers_password_hash_not_blank CHECK (length(trim(password_hash)) > 0),
    CONSTRAINT ck_customers_display_name_not_blank CHECK (length(trim(display_name)) > 0),
    CONSTRAINT ck_customers_version_non_negative CHECK (version >= 0)
);

CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    owner_customer_id UUID NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    display_name VARCHAR(80) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    currency CHAR(3) NOT NULL DEFAULT 'NZD',
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    closed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_accounts_display_name_not_blank CHECK (length(trim(display_name)) > 0),
    CONSTRAINT ck_accounts_status CHECK (status IN ('OPEN', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT ck_accounts_currency_nzd CHECK (currency = 'NZD'),
    CONSTRAINT ck_accounts_closed_at CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    CONSTRAINT ck_accounts_version_non_negative CHECK (version >= 0)
);

CREATE INDEX ix_accounts_owner_created
    ON accounts (owner_customer_id, created_at DESC, id DESC);
