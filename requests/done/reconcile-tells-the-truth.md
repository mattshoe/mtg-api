---
status: ready
---

# The task list is lying in four separate ways

Matt: "Both of those are done already??!! And what hairbrush to the task
details page?! And the fucking elapsed time is gone from the completed
ones!!!"

Four defects, all observed on the live list after #73 and #74 shipped:

**1. A task with an agent running on it says `pending`.**
`card-page-shows-everything` and `rename-the-colour-charts` both have a live
`claude -p` on them right now, and both read `pending`. `in progress` is
supposed to mean a process is alive on it this second, and reconcile never
writes it — it only computes `stalled` (a worktree with no agent) and has no
branch that says "a worktree WITH an agent is in progress".
See scripts/intake/task-status.mjs:128-132.

**2. A request filed under `requests/done/` with no pull request is called
`cancelled (request withdrawn)`.** `admin-role-for-mattshoe81` was done by
hand — one `POST /admin/role` — and filed under done. It is finished, not
withdrawn. Being filed under `done/` is the strongest evidence a task
completed; it must never read as cancelled.

**3. A task can be missing from the list entirely.** `task-details-page` has
a worktree, a branch and 2 commits, and there is no row for it at all. A task
that exists anywhere — a request file, a worktree, a branch, a pull request —
must have a row.

**4. The done ones lost their elapsed time.** They used to show how long they
took (that was #71, "Done tasks say how long they took, not a UTC
timestamp"). After the move to D1 the done rows show nothing. Whatever the
reconcile writes for a merged task has to carry the duration, and backfilling
from the pull request's own timestamps is fine where the row never had one.

The through line: reconcile writes a status from an incomplete picture and
guesses when it should look. Make it derive each row from everything
available — the request file and its status, the worktree, whether a process
is alive on it, the branch, the pull request and whether it merged, and
`requests/done/` — and never emit a status it cannot justify from one of
those.
