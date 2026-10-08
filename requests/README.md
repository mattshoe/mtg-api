# requests/

One file per thing Matt wants. A file lands here, an agent builds it and
opens a pull request, and nobody has to be sitting there while it happens.

## Who writes the file

Matt asks for something in conversation. **The coordinating Claude session
writes the file** — that is the normal path, and it is the reason
`status: ready` means anything: a coordinator writes a request complete, in
one Write, rather than saving a half-finished one and coming back to it.

```
requests/edhrec-rank-on-tiles.md
```

```markdown
---
status: ready
merge: auto
---

# EDHREC rank on the grid tiles

The carousel shows it now. Matt wants it on the Library tiles too,
small, under the price.
```

That is the whole format: frontmatter, a title, and what Matt said, in his
words.

**Do not pre-process it.** No plan, no sizing, no splitting, no deciding which
files it touches, no judging whether it is risky. The agent does all of that,
and it has the repository in front of it when it does. A coordinator guessing
at scope ahead of the agent has been wrong every time it has tried.

- `status: ready` is the only gate. Anything else — `needs-matt`, `blocked`,
  a typo, no `status:` line at all — is held, never built. So a file that is
  not finished is safe as long as it does not say `ready`.
- `merge: ask` stops at a green pull request. Leave the line out and the agent
  merges its own work on green CI, which is the normal case and the point of
  having CI. Matt is the only one who asks for `ask`, when he says he wants to
  look first.
- The name becomes the branch, so `fix-the-thing.md` is easier to live with
  than `asdf.md`.

**Writing the file is what starts the build.** The coordinator used Write
or Edit on a path under `requests/`, which fires the PostToolUse hook in
`.claude/settings.json`, which runs the dispatcher. The launchd watcher
covers files that appear while nobody is here.

Matt can still drop a file in himself, and nothing cares which of them did
it.

## What happens to it

```
requests/thing.md          written, with status: ready
  → dispatcher             takes the lock, fetches, makes ONE worktree
                           under `.intake/wt/thing` off origin/main and
                           runs ONE agent in it. Then it logs a line and
                           releases the lock. That is all it does
  → builder                plans it, builds it test-first on both
                           platforms, opens a pull request, blocks on
                           `gh pr checks --watch --fail-fast`
  → requests/done/thing.md moved there by the builder, in a commit of its
                           own on its own branch, so it rides the PR
  → merged                 by the builder itself, on all-green. That
                           deploys the site and cuts an APK
```

The builder owns all of that: the plan, the tests, both platforms, the
pull request, the CI wait, the merge and the filing. Green CI is the gate —
not Matt, and not the dispatcher.

That division was the other way round for a while and it was the wrong fix
for a real problem. Builders were told to "watch CI", the `apps` job takes
thirteen to seventeen minutes, and three of four ended their turn waiting
for a notification that a headless run can never receive. Moving the wait
into the dispatcher fixed the symptom and cost 2,000 lines of bash that
three audit rounds found seven criticals in, four of them in that
machinery. Naming a command that blocks fixed the cause.

If CI comes back red, the same builder fixes it and watches again. Nothing
re-dispatches a second builder at a request, and no builder ever starts on
a worktree that already exists.

## What the dispatcher does, and what it does not

`scripts/intake/dispatch.sh` is one straight-line script with no
functions in it, and reading it is faster than reading this paragraph.
(No line count here on purpose: three comments in this tree have quoted
one that the next edit falsified.) It checks the two switches, refuses to
run from a linked worktree, takes a lock directory so only one runs at a
time, fetches `origin/main`, asks `scripts/intake.mjs` for the first
buildable request, makes a worktree for it off `origin/main`, runs one
`claude -p` there with the Cloudflare credentials unset and the `gh`
login left alone, and logs what it decided to `.intake/intake.log`. The
lock is held for the whole build, because Gradle does not share a
laptop.

It does not triage, does not wait on CI, does not merge, does not verify
the deploy, does not move your request file, does not notify anybody, does
not send a fix-only builder after a red one, does not watch your request
file while a builder runs, and keeps no attempt counters, holds or time
ceilings. All of that existed and all of it is gone — 2,306 lines of it,
which is where every bug lived.

**A worktree is never deleted automatically.** If a builder dies, its tree
stays on disk under `.intake/wt/` with whatever it had, and
`npm run intake:status` shows the files, the commits and whether they were
pushed. That rule was learned by losing about thirty-two modified files to
a dispatcher that tidied up.

**An existing worktree stops the request.** The dispatcher refuses, says
which tree to go and look at, and leaves the request where it is. Removing
that tree by hand is what lets the request be retried.

## The rules the builder works under

They are not negotiable and they are in `.claude/agents/request-builder.md`,
not here, so an agent cannot talk itself out of them:

- it plans the request itself, because a request may be prose and nothing
  triaged it first
- TDD, and the red is recorded in the commit message
- **parity**: a change to one platform is a change to both, in the same
  pull request
- every suite green before the PR, read off the BUILD line and never off
  an exit code
- a suite that shrinks needs the floor lowered deliberately, in the same
  commit, with the reason
- it waits for CI with `gh pr checks <n> --watch --fail-fast`, which
  blocks, and merges on green unless the frontmatter says `merge: ask`
- it moves the request into `requests/done/` in a commit of its own, on
  its branch. What it must never do is commit the request file where it
  is: that put live request files on `main` permanently and seeded every
  later worktree with them
- it never calls ScheduleWakeup or Monitor, never backgrounds a build and
  never writes its own wait loop. One foreground command at a time,
  because a headless run gets no second turn

Merging deploys — `pages.yml` publishes the website and `release.yml` cuts
a signed APK — and **nothing checks the shipped artifact automatically**.
A green deploy workflow has shipped nothing before, so "done means
deployed" is still the standard, and right now it is a standard somebody
has to apply by hand.

## Taking one back

Delete the file before an agent has picked it up. Nothing looks at it
afterwards.

It does **not** stop a builder that is already running — the dispatcher
used to watch the live file on an interval and kill the builder, and that
went with the rest of the machinery. A build already under way finishes,
opens its pull request and may merge it. Close the pull request yourself if
you do not want it.

Moving the file to `requests/done/` yourself works too.

## Turning the whole thing off

```
bash scripts/intake/uninstall.sh
```

That touches `.intake/disabled`, boots the launchd job out and removes the
plist. Both `hook.sh` and `dispatch.sh` exit immediately while that file
exists, which matters because the hook is **committed**: `launchctl
unload` on its own left an edit to any `requests/*.md` from any Claude
session in this repo still able to start a builder.

Back on:

```
rm .intake/disabled && bash scripts/intake/install.sh
```

`install.sh` writes `.intake/enabled`, which is the opt-in. `.intake/` is
gitignored, so a fresh clone is not armed by the committed hook until
somebody installs — an absent `enabled` is as dead as a present
`disabled`.

## Seeing what is happening

```
npm run intake:status
```

One screen: both switches, the lock and whether a pid in it is still
alive, every request with the state `scripts/intake.mjs` gives for it,
every worktree
under `.intake/wt` with how many files and commits it holds and whether
they are pushed, and the open pull requests with their check counts. A
banner only when `node` or `gh` actually failed, never because the answer
was empty.
