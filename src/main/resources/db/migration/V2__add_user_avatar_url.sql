-- V1 has already run everywhere it exists, so the column arrives in its own file rather than as an
-- edit to a migration Flyway has a checksum for.
--
-- Nullable on purpose: most accounts never set a picture, and a social sign-in only fills it when
-- the provider hands one over. No index, because an avatar is only ever read alongside its row.
ALTER TABLE users
    ADD COLUMN avatar_url VARCHAR(512);
