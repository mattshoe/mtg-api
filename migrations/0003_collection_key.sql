-- The public identifier a collection is shared by.
--
-- Its own migration rather than a line added to 0002, which had
-- already been applied: wrangler records a migration as done by name,
-- so editing one that has run changes nothing on the live database
-- and runs twice on a fresh one. Production took the deploy with the
-- column missing and every sign-in failed on the INSERT.
--
-- Added nullable with a unique index rather than `NOT NULL UNIQUE` in
-- the column, because SQLite cannot add a NOT NULL column without a
-- constant default and there is no constant that would be unique. The
-- index does the work: SQLite allows many NULLs in a unique index, and
-- the backfill below leaves none.
ALTER TABLE users ADD COLUMN key TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_key ON users(key);

-- Anybody who signed in before keys existed. `randomblob(4)` in hex is
-- eight characters of 0-9a-f, which is inside the alphabet the
-- generator uses and the same length, so a backfilled key is
-- indistinguishable from a fresh one.
UPDATE users SET key = lower(hex(randomblob(4))) WHERE key IS NULL;
