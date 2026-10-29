---
status: ready
merge: auto
---

# Nothing is watching the intake log

`.intake/intake.log` recorded the same error once a minute for twelve
hours while the queue was dead:

    task-status: ENOENT: no such file or directory, scandir
    /Users/matthewshoemaker/repos/mtg-api/.intake/pinned/requests

Seven hundred identical lines. Nothing read them. Matt found out by
looking at the app and asking why his task had not moved.

Every other failure in this system got the same treatment at some
point: the dispatcher knew, wrote it down, and nobody was listening.

## What to build

The dispatcher notices when it is failing the same way repeatedly, and
says so where Matt will see it.

He has been exact about notifications and the rules stand: push is for
`deployed` and for a task that cannot be recovered, nothing else. So
this is NOT a push per error. The shape that fits what exists:

- count consecutive ticks that ended in the same error
- past some threshold, that is not a bad minute, it is a broken
  machine, and the right word for it is `blocked` on whatever task is
  affected — permanent, unrecoverable, needs a person
- if no task is affected, because the failure is the dispatcher
  itself, there is already a path for this: `say "NEEDS YOU: ..."` plus
  the `osascript` notification used when `intake.mjs` cannot read the
  queue. Reuse it rather than inventing a second one.

Do not make it chatty. A dispatcher that cries wolf gets ignored, and
the flapping `blocked` notifications were a real problem once already
— see CLAUDE.md on why `blocked` is permanent.

## Tests

`test/intake-shell.test.js`. Drive the real script with something that
fails every tick and assert it escalates once, not every minute.
