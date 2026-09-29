-- A write that was already applied, so a retry does not apply it twice.
--
-- Applied by hand on 2026-09-29 before this file existed, which is the
-- trap this directory closes: a schema change the deployed code needs
-- had no way to reach the database except somebody remembering. Written
-- with IF NOT EXISTS so it is a no-op on the database that already has
-- it and correct on one rebuilt from schema.sql.
CREATE TABLE IF NOT EXISTS idempotency (
    key      TEXT PRIMARY KEY,
    path     TEXT NOT NULL,
    ts       TEXT NOT NULL,
    status   INTEGER,
    body     TEXT
);
CREATE INDEX IF NOT EXISTS idx_idem_ts ON idempotency(ts);
