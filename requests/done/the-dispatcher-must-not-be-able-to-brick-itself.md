---
status: ready
merge: auto
---

# The dispatcher must not be able to brick itself

`dispatch.sh` re-execs from a pinned copy of `scripts/` taken from
`origin/main`, so that a branch checked out in the clone cannot change
what production does. That part is right and must stay.

What it has no answer for: when the broken code IS the pinned code.

That happened. `task-status.mjs` and `inbox.mjs` worked out which repo
they belonged to from their own file path, so under
`.intake/pinned/scripts/...` they looked for `requests/` inside
`.intake/pinned`, which holds nothing but `scripts`. Every tick, for
twelve hours:

    task-status: ENOENT: no such file or directory, scandir
    /Users/matthewshoemaker/repos/mtg-api/.intake/pinned/requests

The inbox collected nothing. A task Matt sent sat at `pending` with no
name until somebody noticed. The only way out was to merge a fix
through the full gate, which takes as long as it takes, and the queue
is dead for all of it.

## What to build

A pinned run that is visibly not working falls back to the working
tree, loudly.

The judgement call is what "visibly not working" means, and it has to
be something that cannot be confused with an ordinary bad day:

- the pinned copy is missing a script the dispatcher is about to run
- the pinned dispatcher exits non-zero before it reaches the queue
- the same error line repeats for N consecutive ticks

Pick the narrowest signal that would have caught this one. A fallback
that fires too eagerly puts us back to running whatever is checked
out, which is the thing the pinning exists to prevent — so when it
fires it must say, in the log, that it is running unmerged code and
why.

## Tests

`test/intake-shell.test.js` already drives the real script in a
throwaway repo; the "which copy of the scripts actually runs" block is
where this belongs.

Note the trap that let the original bug through: a test box has no
credential, so `task-status.mjs` and `inbox.mjs` return before they
ever read `requests/`. A test that drives `dispatch.sh` and asserts
"no error" will pass against the broken code. Assert on the fallback
being taken, not on the absence of a symptom.
