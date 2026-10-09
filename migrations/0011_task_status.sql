-- Where a task is, in D1. It used to be inferred in the app from
-- GitHub's branch names and pull requests. Now the dispatcher writes
-- each transition to the task's own row, and the inbox is the same
-- table rather than a second one. See src/tasks.js.
ALTER TABLE task_inbox ADD COLUMN name TEXT;
ALTER TABLE task_inbox ADD COLUMN status TEXT NOT NULL DEFAULT 'queued';
ALTER TABLE task_inbox ADD COLUMN pr TEXT;
ALTER TABLE task_inbox ADD COLUMN started_at TEXT;
ALTER TABLE task_inbox ADD COLUMN finished_at TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_task_inbox_name ON task_inbox(name);

-- The release notes, kept by the Worker so the app need not ask GitHub.
CREATE TABLE IF NOT EXISTS github_releases (
  id          INTEGER PRIMARY KEY CHECK (id = 1),
  body        TEXT NOT NULL,
  fetched_at  TEXT NOT NULL
);
