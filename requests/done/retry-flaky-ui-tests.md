---
status: ready
---

# UI tests should retry 3 times before failing

20 fucking minutes of waiting just for a flaky test. Your UI tests should
have 3 retries for failures.

The one that did it: `JourneyTest > openingTheAppShowsCardsTheDatabaseActuallyHas`
failed once on the emulator in CI run 37779576307, passed on the commit
before it with no app code changed in between.
