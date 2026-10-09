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

**5. `deployed` is a status nothing writes.** Matt: "What the fuck is the
deployed status of nothing fucking uses it?!?!" He is right — it is in the
vocabulary, the UI counts it as finished, and no code path ever sets it.
Every task stops at `merged`.

Make it real rather than removing it, because "done means deployed on both
platforms" is already a hard requirement in `.claude/skills/mtg/SKILL.md`
and this is the only place it could ever be visible.

A merge triggers `pages.yml` (the site), `release.yml` (the APK) and
`worker.yml` (the API), on paths. So after a task merges, something has to
watch the deploy runs for that merge commit and move the row to `deployed`
when they finish — and say so when one fails, rather than leaving the row at
`merged` forever with no explanation.

A task whose change triggers no deploy at all — a test-only or
documentation-only change — must not sit at `merged` waiting for a deploy
that will never run. Decide what that case reads as and make it say that.

If, having tried, writing `deployed` honestly is not possible, then delete
the status and say why in the pull request. A word in the vocabulary that
nothing can produce is worse than not having it.
