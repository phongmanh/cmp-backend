-- An avatar can now be uploaded rather than only linked to, so the bytes need somewhere to live.
-- They go in Postgres because this deployment has no object store; ImageRepository is the only
-- thing that reads them, so moving to one later replaces that class and drops the column below
-- without the API or the app noticing.

CREATE TABLE images (
    id           UUID        PRIMARY KEY,
    owner_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    content_type VARCHAR(64) NOT NULL,
    byte_size    INTEGER     NOT NULL,
    width        INTEGER     NOT NULL,
    height       INTEGER     NOT NULL,
    -- Of the normalised output rather than the upload, so it is exactly what the ETag promises.
    -- Deliberately not unique: two people uploading the same picture must get a row each, or one
    -- of them deleting it would blank the other's avatar, and the collision would tell each of
    -- them that somebody else holds that exact file.
    sha256       VARCHAR(64) NOT NULL,
    bytes        BYTEA       NOT NULL,
    created_at   TIMESTAMP   NOT NULL
);

-- The one table here with no deleted_at. Everything a user creates is soft deleted so it can be
-- recovered and audited, but a replaced avatar has neither use, and keeping every one a user ever
-- set would carry the whole pile through every base backup and WAL stream for good. A superseded
-- image is deleted outright, in the same transaction that stops anything pointing at it.

-- The payload is already-compressed JPEG. EXTERNAL stores it out of line without offering it to
-- the compressor, which would spend CPU and hand the same bytes back.
ALTER TABLE images ALTER COLUMN bytes SET STORAGE EXTERNAL;

CREATE INDEX images_owner_id_idx ON images (owner_id);

-- Separate from avatar_url so a picture a provider hosts and one this server stores are never
-- mistaken for each other. Google supplies the first at sign-in without passing through the upload
-- path, and copying it here would mean fetching a URL somebody else chose from inside the server.
ALTER TABLE users
    ADD COLUMN avatar_image_id UUID REFERENCES images (id) ON DELETE SET NULL;

-- At most one source of truth. Without this the pair is a union with four representable states and
-- only three legal ones, which leaves the code that reads it guessing which wins.
ALTER TABLE users
    ADD CONSTRAINT users_one_avatar_source
        CHECK (avatar_url IS NULL OR avatar_image_id IS NULL);

CREATE INDEX users_avatar_image_id_idx ON users (avatar_image_id);
