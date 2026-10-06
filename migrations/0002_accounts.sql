-- Accounts, and the sessions that stand for them.
--
-- `users.slug` is the join between an account and its collection:
-- `cards.owner` and `decks.owner` have held a slug since the first
-- day, so nothing in the collection moves when accounts arrive. An
-- account whose slug is `matt` owns every row that already says
-- `matt`.
--
-- `IF NOT EXISTS` throughout, so this is a no-op on a database that
-- already has them and correct on one rebuilt from schema.sql.
CREATE TABLE IF NOT EXISTS users (
  id            INTEGER PRIMARY KEY,
  slug          TEXT NOT NULL UNIQUE,
  display_name  TEXT,
  email         TEXT,
  avatar_url    TEXT,
  -- Running the server — the log, the maintenance job — and not
  -- owning cards. Every account owns its own collection with no role
  -- at all.
  role          TEXT NOT NULL DEFAULT 'user',
  created_at    TEXT NOT NULL
);

-- One row per way of signing in. Keyed on the provider's own stable
-- subject and never on the email address, which a provider may let
-- change and which somebody else may later be issued.
CREATE TABLE IF NOT EXISTS identities (
  provider    TEXT NOT NULL,
  subject     TEXT NOT NULL,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at  TEXT NOT NULL,
  PRIMARY KEY (provider, subject)
);
CREATE INDEX IF NOT EXISTS idx_identities_user ON identities(user_id);

-- Only the hash. The token itself exists in the browser's cookie and
-- nowhere else, so reading this table gets you nobody's session.
CREATE TABLE IF NOT EXISTS sessions (
  token_hash  TEXT PRIMARY KEY,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at  TEXT NOT NULL,
  expires_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);
