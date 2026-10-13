-- Where to send a push, and who it belongs to.
--
-- One row per device per person: Matt's phone, his tablet, Kayla's
-- phone. The token is Firebase's and it rotates on its own — on a
-- reinstall, a restore, sometimes for no reason — so the device tells
-- us its current one every time the app starts and the row is replaced
-- rather than added to.
--
-- `user_id` is who gets the notification. Only admins are sent task
-- news, and the join decides that at send time rather than freezing it
-- here, so demoting somebody stops their pushes without a migration.
CREATE TABLE IF NOT EXISTS devices (
  token      TEXT PRIMARY KEY,
  user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  platform   TEXT NOT NULL,
  label      TEXT,
  created_at TEXT NOT NULL,
  seen_at    TEXT NOT NULL,
  -- Set when Firebase tells us the token is dead, so a device that was
  -- uninstalled stops being tried on every task without the row having
  -- to be chased down by hand.
  dead_at    TEXT
);
CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id);
