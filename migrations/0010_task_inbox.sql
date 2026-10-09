-- New tasks, written in the app and collected by the laptop that
-- dispatches requests. See src/tasks.js. A row is kept once received,
-- as a record of what was asked; its files are deleted then.
CREATE TABLE IF NOT EXISTS task_inbox (
  id           INTEGER PRIMARY KEY,
  key          TEXT NOT NULL UNIQUE,
  title        TEXT NOT NULL,
  details      TEXT NOT NULL,
  created_by   INTEGER REFERENCES users(id),
  created_at   TEXT NOT NULL,
  received_at  TEXT
);

CREATE TABLE IF NOT EXISTS task_files (
  id        INTEGER PRIMARY KEY,
  task_id   INTEGER NOT NULL REFERENCES task_inbox(id) ON DELETE CASCADE,
  position  INTEGER NOT NULL,
  name      TEXT NOT NULL,
  type      TEXT NOT NULL,
  bytes     BLOB NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_task_files_task ON task_files(task_id);
