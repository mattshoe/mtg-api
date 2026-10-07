# requests/

One file per thing you want. Drop it in and walk away.

A watcher notices the file, triage reads it, and a builder agent picks
it up, implements it, opens a PR and drives that PR to green. You do
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
  → requests/thing.md      rewritten in place with a plan and a size
  → builder                branch, TDD, both platforms, PR
  → PR green               CI including the emulator
  → requests/done/thing.md moved, with the PR number in it
```

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
- the PR is opened and driven to green; **merging is yours** unless
  the request file says `merge: auto`

## Taking one back

Delete the file, or move it to `requests/done/` yourself. A builder
that finds its request gone stops.
