# requests/

One file per thing you want. Drop it in and walk away.

A watcher notices the file, triage reads it and writes a plan into it, and
a builder agent picks it up, implements it, opens a pull request, waits for
CI and merges it on green. You do not have to be here, and neither do I.

## Writing one

A request is a file. That is the whole format:

```
requests/edhrec-rank-on-tiles.md
```

```markdown
# EDHREC rank on the grid tiles

The carousel shows it now. I want it on the Library tiles too,
small, under the price.
```

A title and a sentence is plenty. Half a sentence is fine —
`requests/decks-load-slow.md` containing "decks page takes forever on
my phone" is a legitimate request and triage will come back with
questions in the file itself rather than blocking on you.

Name the file whatever you like; the name becomes the branch, so
`fix-the-thing.md` is easier to live with than `asdf.md`.

## What happens to it

```
requests/thing.md          you wrote it
  → triage                 reads it and sizes it, IN PLACE. It does not
                           split one request into several or fold two into
                           one — the copy-back out of its worktree could
                           only ever return the files it was handed, so
                           anything it created was silently thrown away
  → requests/thing.md      rewritten with a plan, tests and a done-when
  → builder                one worktree under `.intake/wt/thing`, TDD, both
                           platforms, a pull request, then CI, then merge
  → merged                 by the builder itself, on all-green. That
                           deploys the site and cuts an APK
  → requests/done/thing.md moved here by the dispatcher
```

The builder owns the whole of that, CI and the merge included. It waits
with `gh pr checks <n> --watch --fail-fast`, which blocks in the
foreground, then checks that every check says SUCCESS rather than merely
that none failed, then squash-merges. Green CI is the gate — not you, and
not the dispatcher.

That division was the other way round for a while and it was the wrong fix
for a real problem. Builders were told to "watch CI", the `apps` job takes
thirteen to seventeen minutes, and three of four ended their turn waiting
for a notification that a headless run can never receive. Moving the wait
into the dispatcher fixed the symptom and cost 2,000 lines of bash that
three audit rounds found seven criticals in, four of them in that
machinery. Naming a command that blocks fixed the cause.

`merge: ask` is the one exception: the builder stops at a green pull
request and says so, and you get a notification. Triage sets it for three
things and nothing else — a schema change, auth or roles, or card
ownership.

If CI comes back red, the same builder fixes it and watches again. Nothing
re-dispatches a second builder at a request, and no builder ever starts on
a worktree that already exists.

## What the dispatcher does, and what it does not

`scripts/intake/dispatch.sh` is 276 lines and does four things: it triages
anything with no plan, starts **one** builder in a fresh worktree with a
time ceiling on it (three hours; `INTAKE_MAX_MINUTES`), moves a merged
request into `requests/done/`, and notifies you about anything that needs
you.

It does not wait on CI, does not merge, does not verify the deploy, does
not send a fix-only builder after a red one, does not watch your request
file while a builder runs, and does not hold or retry a request that keeps
failing. All of that existed and all of it is gone.

**A worktree is never deleted automatically.** If a builder dies, its tree
stays on disk under `.intake/wt/` with whatever it had, and
`npm run intake:status` shows the files, the commits and whether they were
pushed. That rule was learned by losing about thirty-two modified files to
a dispatcher that tidied up.

**A request leaves this folder only when its pull request merged.** Every
other outcome leaves the file exactly where it is, which is why a dead
builder's request is still listed as ready — and why an existing worktree
stops the next dispatch instead of starting a second builder over it.

## The rules the builder works under

They are not negotiable and they are in the agent definition, not
here, so an agent cannot talk itself out of them:

- TDD, and the red is recorded in the commit message
- **parity**: a change to one platform is a change to both
- every suite green before the PR, read off the BUILD line and never
  off an exit code
- a suite that shrinks needs the floor lowered deliberately, in the
  same commit, with the reason
- the builder never moves the request file and never commits it. Its copy
  is handed to it at a path outside `requests/` for that reason: a builder
  that committed the live file put finished requests on `main` permanently
  and seeded every later worktree with them
- it never calls ScheduleWakeup or Monitor, never backgrounds a build and
  never writes its own wait loop. One foreground command at a time,
  because a headless run gets no second turn

Merging deploys — `pages.yml` publishes the website and `release.yml` cuts
a signed APK — and **nothing checks the shipped artifact automatically**.
A green deploy workflow has shipped nothing before, so "done means
deployed" is still the standard, and right now it is a standard somebody
has to apply by hand.

## Taking one back

Delete the file. Nothing picks it up afterwards, and if triage is mid-run
its plan is thrown away rather than written back over the deletion.

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

## Seeing what is happening

```
npm run intake:status
```

One screen: the dispatcher's lock and whether its process is alive, every
request with the state `scripts/intake.mjs` gives for it, every worktree
under `.intake/wt` with how many files and commits it holds and whether
they are pushed, and the open pull requests with their check counts.
