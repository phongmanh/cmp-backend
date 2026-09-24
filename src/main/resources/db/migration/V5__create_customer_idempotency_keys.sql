-- Remembers which customer an Idempotency-Key created, so a retried POST /api/v1/customers returns
-- that customer instead of making a second one. Keys are scoped per owner: two accounts may pick the
-- same key without seeing each other's customers.

CREATE TABLE customer_idempotency_keys (
    owner_id        UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    idempotency_key UUID         NOT NULL,
    -- SHA-256, in hex, of the normalized request, so a key sent again with a different body is
    -- refused rather than answered with a customer the caller did not ask for.
    request_hash    VARCHAR(64)  NOT NULL,
    -- Deferred because the key row is written before the customer row in the same transaction: the
    -- key's primary key has to be claimed first, or a racing retry that also carries an email would
    -- trip the email unique index and answer 409 instead of replaying.
    customer_id     UUID         NOT NULL REFERENCES customers (id) ON DELETE CASCADE
                                 DEFERRABLE INITIALLY DEFERRED,
    created_at      TIMESTAMP    NOT NULL,

    CONSTRAINT customer_idempotency_keys_pkey PRIMARY KEY (owner_id, idempotency_key)
);

-- Serves the foreign key. The primary key already covers owner_id.
CREATE INDEX customer_idempotency_keys_customer_id_idx ON customer_idempotency_keys (customer_id);
