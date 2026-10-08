# Plan: see the status of ongoing tasks in the app (revision 4)

## Up front, for the PR's first line

Matt named "hold" as an example status. It will not appear: held and
queued requests exist only on the laptop, and the two request files on
main are stale accidents. What shows is what GitHub can see: building,
in review, done, closed.

## What "task" means

A task is an intake request: a file under `requests/`, built by a
request-builder agent on a branch `request/<slug>-<digest>`, ending in a
pull request that the builder merges. Matt's words: "the literal status
like hold or done or whatever statuses you assign. I want the done ones
minimized by default but still browsable, ordered by the time which they
completed, most recent first".

## Where the status comes from: GitHub, read-only, nothing new in production

The repository is public. The app already reads GitHub directly for the
release notes on Admin Settings (`GitHubReleases` in `:core-net`, no
Worker, no D1). Tasks are read the same way. **No Worker change, no
schema change, no migration, no new deploy surface, no new credential.**
Production is touched only by the two app bundles that already deploy.

Three unauthenticated GET calls, all CORS-open:

1. `GET api.github.com/repos/mattshoe/mtg-api/pulls?state=all&sort=updated&direction=desc&per_page=100`
   — keep PRs whose `head.ref` starts with `request/`. Sorted by update so
   the oldest finished ones are what falls off past 100, not live ones.
2. `GET api.github.com/repos/mattshoe/mtg-api/git/matching-refs/heads/request/`
   — only the `request/*` refs, unpaginated, CORS-open. Not `/branches`:
   that lists every branch by name, merged request branches are never
   deleted, and past 100 live ones would fall off page 1. `refs/heads/`
   is stripped in the decoder.
3. `GET api.github.com/repos/mattshoe/mtg-api/contents/requests/done`
   — the names of every finished request. Merged request branches are
   never deleted (`delete_branch_on_merge` is off and the builder is told
   not to `--delete-branch`), so once a merged PR ages out of the 100
   most recently updated, its ref alone would read `building` forever.
   A ref whose slug matches a `requests/done/<name>.md` (slug by the
   `branchFor` rule in `scripts/intake.mjs`: lowercase, runs of non-alnum
   → `-`, trimmed) is finished and is not shown as `building`. This keeps
   the fix inside the app: no repo setting, no builder workflow change, no
   deleting anybody's branches.

Request files are NOT read. Live request files are never meant to be on
main (README forbids committing them); the two that are there got in by
accident and are stale, and the real queue lives only on Matt's laptop.
Reading them would show statuses that are mostly missing and sometimes
false. So `queued` and `hold` are not shown, and the PR says so: showing
them needs the laptop to publish its state, which is the same channel a
later "submit from the app" would need, and is designed then.

Rate limit: 60/hour/IP unauthenticated. One visit = 3 calls (+1 for
releases, which load once per session).

**Reloaded every time the Admin Settings list is entered**: guarded by
`route.rest.isEmpty() && !busy` on both platforms, so opening a person's
page (same view, `rest` set) does not refetch,
unlike releases, which load once per session: task status changes
minute to minute.

## The statuses (one word each, assigned in `:core`)

A task is keyed by its branch name (`head.ref` == branch name, both
`request/...`). Several PRs on one ref (a request rebuilt) collapse to
one task: the highest-precedence status, then the latest finish.

| status | means | from |
|---|---|---|
| `done` | PR merged | PR `merged_at` |
| `in review` | PR open (builder waiting on CI) | PR state open |
| `closed` | PR closed without merging | PR closed, no `merged_at` |
| `building` | branch pushed, no PR yet, not filed under done/ | branch only |

Precedence: done > in review > closed > building.

Title: the PR title (latest PR on that ref) when there is one, else the
branch name with `request/` and an optional trailing `-<7 hex>` removed,
dashes as spaces (old refs like `request/orphan-second-matt-account`
have no digest).

## The screen

A "Tasks" panel on Admin Settings, above "Release notes", on both
platforms. Admin Settings is already the operator screen and already
loads GitHub data on open. No new view, route or nav entry.

- Active tasks (`building`, `in review`) listed first, each row: title,
  status word.
- Below, a "Done (N)" toggle, **collapsed by default**. Expanded, it lists
  `done` and `closed` tasks ordered by when they finished (`merged_at` or
  `closed_at`) most recent first, each with status word and date+time.
- Loading / error ("Could not load tasks: <GitHub's message>") / empty
  states the same as release notes.

## Code, by where it lives

- `:core` `Tasks.kt`: `Task(ref, title, status, finishedAt)`, `Tasks`
  state (`rows`, `busy`, `error`, `showDone`, `active`, `done`,
  `toggleDone()`, `loading/loaded/failed`), and a pure decoder
  `Tasks.decode(pulls: String, refs: String, done: String)`. `AppState.tasks`.
- `:core-net`: `GitHubReleases` renamed `GitHub` (mechanical; it now
  answers two questions) with a `tasks()` method beside `releases()`
  (the three GETs, throws with GitHub's message on refusal). Both
  shells already inject that class for tests (`useGitHubForTesting`, the
  web driver), so no new seam or field.
- web `AdminPage.kt` draws the panel; `Main.kt` loads it beside releases.
- Android `AdminScreen.kt` draws it; `MainActivity` loads it beside
  releases, job on `MtgViewModel`; `AppShell` passes state and toggle.
- `app.css` for the few rows' styles.

## Tests (red first, each)

- `:core` `TasksTest`: statuses per source, precedence, rebuilt ref
  collapses, title fallback with and without digest, a ref filed under done/ with no PR in the list is not `building`, done ordering newest
  first, done collapsed by default, toggle, unreadable input → empty,
  failed load leaves nothing stale.
- `:core-net` `GitHubTest` cases: asks the right URLs, reads them, a
  refusal throws with GitHub's words.
- web `TasksPanelTest` (real shell, real browser): active rows visible,
  done hidden until the toggle is pressed, then newest first; error text.
- Android `TasksParityTest` (sharedTest, through `AppShell`): same.
- Load tests (including: a second visit to the Admin Settings list asks again, opening a person does not):
  web `AppDriverTest` case, Android `TasksLoadTest` through real
  `MainActivity` over a stub, like `ReleasesLoadTest`.

## Known gaps, said plainly

- Held and queued requests are invisible: they live only on the laptop.
  A task appears once its builder pushes its branch (builders push early).
- A branch whose builder died, or whose PR was closed unmerged and has
  aged out of the 100 most recent, reads as `building` forever.
- Later features (submit/cancel from the app) need writes, which would go
  through the Worker or GitHub with credentials; this read path does not
  block either.
