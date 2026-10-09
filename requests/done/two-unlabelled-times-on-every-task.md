---
status: ready
---

# Every task shows two bare durations and neither says what it is

Matt: "Why does it say \"3m for 3m\" and \"17m for 7m\"?!?!?!"

A task row currently stacks two numbers with no labels:

    reconcile tells the truth        in progress
    17m
    for 7m · PR #76

    Tap a task to open a details page    in progress
    3m
    for 3m

The first is the task's total age since it was created. The second is how
long it has been in its current status. When a task starts the moment it is
created those are the same number, so it reads as a stutter or a bug — which
is how Matt read it.

Matt has decided it: "I don't give a fuck good long is been in the fucking
status just show the total fucking time!!!! And drop the pr number and only
show that on details!"

So a row shows ONE duration — the total time since the task was created —
and no pull request number. The time-in-current-status goes away entirely
from the list. The pull request number moves to the task details page, along
with everything else about the task.

Matt: "And are the completed ones going to show it too???" Yes, and it has
to mean the right thing in both cases:

- **Still going** — how long since it was created, counting up.
- **Finished** — how long it TOOK, created to finished, frozen. Not a
  timestamp, and not a number that keeps climbing after the work stopped.

Both are one duration in the same place on the row, so the list reads the
same whether a task is running or done.

This is the display half of the same thing `reconcile-tells-the-truth`
raises as its defect 4, which is about the data: the done rows lost their
duration in the move to D1 and whatever writes a merged row has to carry it.
If that one lands first, use what it stores rather than recomputing.
