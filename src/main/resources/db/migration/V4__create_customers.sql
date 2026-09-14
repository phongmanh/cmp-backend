-- Customers an account keeps. Each row belongs to exactly one user, and every query the API runs
-- is scoped to that owner: there is no role that reads across accounts.

CREATE TABLE customers (
    id            UUID          PRIMARY KEY,
    owner_id      UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    first_name    VARCHAR(100)  NOT NULL,
    last_name     VARCHAR(100),
    company_name  VARCHAR(200),
    -- Lowercased by the application before it is written, so the unique index below is also
    -- case-insensitive without an expression.
    email         VARCHAR(320),
    -- E.164, which is never longer than a plus sign and fifteen digits.
    phone         VARCHAR(16),
    address_line1 VARCHAR(200),
    address_line2 VARCHAR(200),
    city          VARCHAR(100),
    region        VARCHAR(100),
    postal_code   VARCHAR(20),
    country_code  VARCHAR(2),
    notes         VARCHAR(2000),
    status        VARCHAR(16)   NOT NULL,
    created_at    TIMESTAMP     NOT NULL,
    updated_at    TIMESTAMP     NOT NULL,
    deleted_at    TIMESTAMP,

    -- The same list CustomerStatus publishes. A value outside it can only come from a bug or a
    -- hand-written statement, and either should fail here rather than on the next read.
    CONSTRAINT customers_status_known CHECK (status IN ('lead', 'active', 'inactive')),

    -- An address is either absent or has what it takes to post something to. The API only ever
    -- writes the columns together; this keeps a partial write from leaving half of one behind.
    CONSTRAINT customers_address_complete CHECK (
        (address_line1 IS NULL AND address_line2 IS NULL AND city IS NULL
            AND region IS NULL AND postal_code IS NULL AND country_code IS NULL)
        OR (address_line1 IS NOT NULL AND city IS NOT NULL AND country_code IS NOT NULL)
    )
);

-- Serves the foreign key, and the newest-first keyset pagination every list runs. Not partial:
-- deleting a user has to find that user's soft deleted customers as well.
CREATE INDEX customers_owner_id_created_at_idx ON customers (owner_id, created_at DESC, id DESC);

-- One live customer per address per owner. Partial, so a deleted customer does not keep the address
-- reserved, and NULL emails never collide with each other.
CREATE UNIQUE INDEX customers_owner_email_unique ON customers (owner_id, email) WHERE deleted_at IS NULL;

CREATE INDEX customers_owner_status_idx
    ON customers (owner_id, status, created_at DESC, id DESC) WHERE deleted_at IS NULL;

CREATE INDEX customers_deleted_at_idx ON customers (deleted_at);

-- Prefix search on ?q=. text_pattern_ops is what lets a btree answer LIKE 'abc%' under a
-- non-C collation. The email column is already lowercase, and the unique index above already
-- leads with (owner_id, email), but that one cannot serve LIKE, hence a pattern index of its own.
CREATE INDEX customers_owner_first_name_search_idx
    ON customers (owner_id, lower(first_name) text_pattern_ops) WHERE deleted_at IS NULL;
CREATE INDEX customers_owner_last_name_search_idx
    ON customers (owner_id, lower(last_name) text_pattern_ops) WHERE deleted_at IS NULL;
CREATE INDEX customers_owner_company_name_search_idx
    ON customers (owner_id, lower(company_name) text_pattern_ops) WHERE deleted_at IS NULL;
CREATE INDEX customers_owner_email_search_idx
    ON customers (owner_id, email text_pattern_ops) WHERE deleted_at IS NULL;
