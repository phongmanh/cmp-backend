-- Timestamps are stored as UTC instants in TIMESTAMP columns; the application never writes a
-- local time, and every value that leaves the API is rendered as ISO-8601 in UTC.

CREATE TABLE users (
    id                UUID PRIMARY KEY,
    email             VARCHAR(320),
    password_hash     VARCHAR(255),
    display_name      VARCHAR(120),
    is_email_verified BOOLEAN   NOT NULL DEFAULT FALSE,
    is_active         BOOLEAN   NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP NOT NULL,
    updated_at        TIMESTAMP NOT NULL,
    deleted_at        TIMESTAMP
);

-- Partial, so a soft deleted account does not keep its address reserved forever.
CREATE UNIQUE INDEX users_email_unique ON users (email) WHERE deleted_at IS NULL;
CREATE INDEX users_deleted_at_idx ON users (deleted_at);

CREATE TABLE user_identities (
    id               UUID PRIMARY KEY,
    user_id          UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider         VARCHAR(32)  NOT NULL,
    provider_user_id VARCHAR(191) NOT NULL,
    created_at       TIMESTAMP    NOT NULL
);

-- One provider account can back exactly one user.
CREATE UNIQUE INDEX user_identities_provider_unique ON user_identities (provider, provider_user_id);
CREATE INDEX user_identities_user_id_idx ON user_identities (user_id);

CREATE TABLE refresh_tokens (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id  UUID        NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP   NOT NULL,
    created_at TIMESTAMP   NOT NULL,
    revoked_at TIMESTAMP
);

CREATE UNIQUE INDEX refresh_tokens_token_hash_unique ON refresh_tokens (token_hash);
CREATE INDEX refresh_tokens_user_id_idx ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_family_id_idx ON refresh_tokens (family_id);
