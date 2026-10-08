# requests/

One file per thing you want. Drop it in and walk away.

A watcher notices the file, triage reads it, and a builder agent picks it
up, implements it and opens a pull request. The dispatcher then waits on
CI, merges on green, and checks that the change actually shipped. You do
not have to be here, and neither do I.

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
  → triage                 reads it, merges duplicates, splits anything
                           that is secretly three jobs, sizes it
  → requests/thing.md      rewritten in place with a plan, committed
  → builder                branch, TDD, both platforms, PR — then it stops
  → dispatcher             blocks on CI, checks every check
  → merged                 `merge: auto`, which deploys the site and cuts
                           an APK. `merge: ask` stops at a green PR and
                           you get a notification instead
  → verified               the pages and release runs watched for the
                           squash commit, then the deployed mtg.js and the
                           released APK's dex grepped for a marker string
                           the builder wrote. No marker means it says it
                           verified nothing, rather than passing
  → requests/done/thing.md moved and committed here, with the outcome in it
```

A red pull request gets a **fix-only** builder: the dispatcher pulls the
failing job's log with `gh run view --log-failed`, hands it and the request
over, and tells it to make CI green without re-implementing anything. Twice,
and then the request is held and you are told. While that is happening the
request is NOT buildable, so there is no second full builder — there used to
be one per dispatch, which is how a branch grew five commits of repeated
work.

The builder stops at an open pull request on purpose. The `apps` job takes
thirteen to seventeen minutes and three of four builders ended their turn
rather than sit through it — one scheduled a wakeup that could never
arrive, because a headless run has no next turn. So the dispatcher owns
the waiting and the merging, and green CI is still the gate.

Triage can also **combine**: two requests that touch the same screen
become one file with both asks in it, and the originals are folded in
rather than built twice over the same code. It says so in the file it
leaves behind.

## The rules the builder works under

They are not negotiable and they are in the agent definition, not
here, so an agent cannot talk itself out of them:

- TDD, and the red is recorded in the commit message
- **parity**: a change to one platform is a change to both
- every suite green before the PR, read off the BUILD line and never
  off an exit code
- a suite that shrinks needs the floor lowered deliberately, in the
  same commit, with the reason
- the PR is opened and the builder stops. The dispatcher waits on CI and
  merges on green — green CI is the gate, not you. A request file that
  says `merge: ask` stops at a green PR and you get a notification
  instead, which triage sets only for a schema change or something
  touching auth or who can edit whose collection
- the builder never moves the request file and never merges. Both are the
  dispatcher's, in the real repo, so a finished request actually leaves
  this folder — two requests were built hours apart and stayed buildable
  because the builder moved its own copy inside its worktree
- a request leaves this folder on **every** terminal outcome, not only a
  merge: merged, green-and-waiting-for-you, red after two fixes, or given
  up after three failed builds. It stopped being true for a while and the
  consequence was a second builder force-pushing over the first one's
  commit. A request that is mid-flight — waiting on CI or on a fix — stays
  here, which is not the same thing
- after a merge the `pages` and `release` runs are watched and both
  artifacts are grepped for the builder's marker. "Done means deployed" is
  the rule, a green workflow has shipped nothing before, and when there is
  no marker or no APK to download the dispatcher says what it could not
  check instead of claiming it passed

## Taking one back

Delete the file. The dispatcher watches it while a builder is running and
stops that builder when it disappears, and nothing picks it up afterwards.

This used to be false in both directions and it is worth saying how: the
builder was handed a private copy of the file, so deleting the original
changed nothing it could see, and nothing was watching. A withdrawn
request got built anyway. The check runs on an interval now, so a builder
may get a minute or so further before it stops.

Moving it to `requests/done/` yourself works too.

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

One screen: the dispatcher's lock and claims, every request with its
state, how many files and commits a builder's slot holds and whether they
are pushed, the last event from each builder and its age, the request
branches, and the open pull requests with their check counts.
