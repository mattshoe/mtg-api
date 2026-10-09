# How work is done in this repo

## Test-driven development, with no exceptions

**Every** change to production code starts with a failing test. Every
feature, every bug fix, every one-line tweak. There is no size below
which this stops applying and no deadline that suspends it.

The cycle, in this order:

1. **Write the test.** It describes the behaviour you are about to
   add, or the bug you are about to fix, in the terms a person would
   use.
2. **Run it and watch it fail.** Not "it should fail" — run it, read
   the failure, and check the message says the right thing. A test
   that passes before the fix is testing nothing, and this repo has
   shipped four of those.
3. **Write the smallest production change that makes it pass.**
4. **Run your test again and watch it pass.** Your test, narrowly —
   `npx vitest run <file> -t '<name>'`, or `--tests 'OneTest'` for
   Gradle. Seconds, not minutes. This step used to say "run the whole
   suite, not just your test", and that was wrong: a whole-suite run
   inside the cycle tells you nothing about the line you just changed
   and costs about seven minutes a time. The full set runs ONCE, when
   the work is done and before the pull request.
5. **Record the red in the commit message.** Nothing checks it and
   nothing can; write it so the next reader knows the test could
   fail.

### Why the red step is not optional

Six tests in this project were written, landed green, and were later
found to prove nothing. Four were mounted somewhere the bug could not
happen:

- a share-menu test mounted the menu in an absolutely positioned
  frame, so the off-screen bug it existed to catch could not happen
- a Library test asserted on rows below a lazy grid's fold, which are
  never composed
- a tweak-sheet test hosted the sheet in a bare `Box`, which has no
  screen to run off, nothing behind it to dim and no outside to tap
- a filter-panel test fed a hand-built `Facets` that the real app
  never produced, while the real app's facet loader did not exist

Every one of those would have been caught by running it against the
unfixed code for ten seconds. That is the whole discipline.

The other two were gated behind `needsRealRendering()`, so they had
never executed at all: one called `onRoot()` with a dialog open, where
there are two roots and `onRoot()` throws; one sized its fixture to
Robolectric's 470dp screen, which a real 808dp phone does not
overflow, so the sheet needed no scrolling and the test proved
nothing. Both were green on the JVM for days. See below.

### What the test has to do

- **Drive the real object.** On Android that means through
  `AppShell`, not a composable mounted alone — see the four above. On
  the web it means the real page composable in a real browser.
- **Assert a resolved fact**: the computed style, the measured
  geometry, the `TextStyle` read back through semantics, the state
  the app actually holds. Never a class name, never a constant
  re-read from the source that set it.
- **Fail with a sentence that names the cause.** "the tenth hit
  already fits, so this proves nothing about scrolling" cost one run.
  "facets never loaded" cost four attempts, because it was reported
  for a load that had completed and written its result.
- **Live where both runners see it**, for Android screens:
  `apps/androidApp/src/sharedTest/`.

### Gated tests are not coverage

A test behind `Parity.needsRealRendering()` is skipped on the JVM,
which means it has **never executed**. Two such tests landed green and
were simply wrong, and the device run is the only thing that found
them. Prefer a test that runs on the JVM. If a check
genuinely needs pixels, run `npm run test:android` before believing
it, and say plainly in the commit that it is otherwise unproven.

### End to end, on a phone

`apps/androidApp/src/androidTest/.../e2e/` is a different kind of
test from everything in `sharedTest`, and the difference is the
point. Those mount `AppShell` with a hand-built `AppState`; these
launch the real `MainActivity` and press its buttons, and the rows
come back over a real socket from SQL that actually ran.

`FakeWorker` is a `MockWebServer` in front of a real SQLite — the
bundled one from `androidx.sqlite:sqlite-bundled`, because Android's
own build has no `fts5` module and `:core` searches through
`card_search MATCH ?`. It loads the repository's own `schema.sql`
and `test/fixtures/seed.sql`, the same bytes the worker's vitest
suite uses, copied into the test APK by the `e2eAssets` task.

So the harness never has to know what the app is going to ask. A
recorded fixture keyed by SQL would answer yesterday's query
perfectly and go stale the moment anybody edits one, which is the
opposite of what a journey is for.

Things it has already found that nothing else could:

- `CardRow.fullName` printed the back of a two-faced card twice —
  "Brazen Borrower // Petty Theft // Petty Theft" — everywhere a
  card is named. The unit test beside it had been green for weeks
  because it built a row with one face in the name and a `face2`
  beside it, which the query cannot return.
- Every failure toast on Android was styled as a success, because
  `work` called `say(message)` without `failed = true`. The website's
  own `work` has always passed it.
- A journey left the admin token in `SharedPreferences` and the next
  journey started signed in.

Write a journey for anything a person does in a sequence. Keep
asserting on measured geometry in `sharedTest`; that is still where
layout belongs.

## Say what was red, in the commit message

Not because anything checks it — nothing can. A commit is a finished
thing and the order its parts were written in leaves no trace. I tried
making CI police a `Red:` trailer and Matt was right to call it what
it was: you can satisfy a message format perfectly while doing the
exact opposite of TDD.

Write it anyway, because the next person reading the commit wants to
know the test could fail:

```
Red: 5 of 6 in CardFaceParityTest — "no type line", "only the
creature face has a stat box expected:<1> but was:<0>"
```

And if some of your new tests pass against the unfixed code, **say so
and say why**. A guard against regression is worth having; counting it
as proof is not. Six tests this repo shipped were doing the second
thing.

## What CI does enforce

Not the discipline — the result of it. Three things, and all three
have caught something real:

- **Every test runs on every pull request.** `shared`, `web` and
  `android` in `.github/workflows/apps.yml`, including the emulator.
  Worth knowing how this was learned: `apps.yml` triggers on
  `pull_request` and pushes to `main`, so a hundred commits once sat
  on a branch with no pull request and no CI at all, verified by
  nothing but a laptop.
- **Every test that exists actually ran** —
  `scripts/check-test-count.mjs` counts `@Test` in the source and
  compares. Kotlin's incremental compiler once dropped three classes,
  71 tests, out of a device APK and reported BUILD SUCCESSFUL.
- **No suite shrinks** — `scripts/check-suite-floor.mjs` against the
  committed numbers in `test/suite-floors.json`. A suite that shrinks
  is worse than one that fails, because it goes green. Skips are
  excluded from the count, so a gated test cannot pad the total.

When a suite grows, raise the floor in the same commit:

```
npm run check:floor -- --raise screens=apps/androidApp/build/test-results/testDebugUnitTest
```

It will not lower a floor, whatever you pass it. If you genuinely
removed a test, edit `test/suite-floors.json` by hand and say why in
the message — which is the point: it takes a deliberate, visible,
reviewable act.

## A schema change needs both files

`schema.sql` is what a database built from scratch gets — including
the one this suite builds. `migrations/` is what the live one gets.
Only ever one of them gets edited, and both ways round have now
shipped broken to production a day apart:

- three tables added to `schema.sql` and no migration written, so the
  deploy sent code querying `users` to a database that had never
  heard of it
- a column added to `migrations/0002`, which had already been
  applied. Wrangler records a migration as done **by name**, so
  editing one that has run is a no-op on the live database and a
  double-add on a fresh one. Every sign-in failed on the INSERT.

Neither could be caught by a test that builds from `schema.sql`,
because both are about the file it does not read. `test/migrations.test.js`
reads both and holds them to each other, and the second rule is the
one worth saying out loud: **an applied migration is never edited.
The fix is always another file.** The hashes in
`test/fixtures/migration-hashes.json` are what makes that checkable;
a new name is ordinary work, a changed hash on an existing name is
the mistake.

`test/fixtures/schema-baseline.json` is the tables that predate
`migrations/`. They were applied by hand and have no migration;
everything after them needs one.

## Never trust a test count without a successful build

A killed Gradle test task leaves the **previous** run's XML on disk.
Anything reading that directory then reports the old run as if it were
new. This repo has reported "356 tests, 0 failures" out of a run that
never happened, and caught three agents with it.

- Check the exit code and `BUILD SUCCESSFUL` before reading a count.
- `rm -rf` the results directory before a run you intend to trust.
- A hung test produces no XML at all, so the XML can never name it.
  `apps/androidApp/build/test-order.log` has a START and an END per
  test; a START with no END is the one that hung.

## Running things

| what | how | time |
|---|---|---|
| worker | `npm test` | ~40s |
| shared core | `npm run test:core` | ~1m |
| web, real browser | `npm run test:web` | ~2m30s |
| one Compose screen test | `./apps/gradlew -p apps :androidApp:testDebugUnitTest --tests 'YourTest'` | seconds |

The whole Android screens suite is not on that list, on the JVM or on a
device. Both are in the never-run-locally table below, and the reason is
underneath it.

Everything goes through `scripts/guard.mjs`, which kills the whole
process group on timeout. Run things in the FOREGROUND and let them
finish. Do not background a run and poll its output, and do not
hand-roll `until ... sleep` wait loops — both become orphans, and one
cost an agent two hours.

`forkEvery(1)` in `apps/androidApp/build.gradle.kts` is load-bearing
and costs about five minutes. `ComposeRootRegistry` keeps every
Compose root ever created in a weakly-referenced set, cleared only by
a full GC that never comes on a 2g ceiling, and every `isIdleNow`
copies the whole set — so without forking the suite grinds to a halt
around test 200.

It was `forkEvery(4)` first, which was a number that worked on one
laptop. CI is slower and the same accumulation crossed Espresso's
60-second idle ceiling there: seven tests failed with "Compose did
not get idle after 9,000,000 attempts", in classes unrelated to the
change. One JVM per class is a guarantee instead of a number tuned
against one machine's speed. Do not raise it to make the suite
faster; the failure it prevents is a suite that goes green locally
and red on hardware nobody has.

## Android state

`MainActivity` must survive a configuration change. State lives in a
`ViewModel`, not in an activity field — a rotation used to empty the
whole app.

**Waiting on a build is the same mistake however you spell it.** These are all
the same banned thing, and one agent burned an hour on the third:

```
cmd &                 then reading its output file
caffeinate -w <pid>            # waiting on a pid is polling
wait <pid>  /  while kill -0   # so is this
until grep ... done            # and this
ScheduleWakeup / Monitor       # you get no second turn; nothing wakes you
```

Run the command in the foreground and let it finish. If it is too slow to sit
through, it is a functional suite and it belongs in the once-at-the-end pass
or in CI — not in your cycle.

## Commands you may not run locally. Ever.

CI runs these. Running one yourself costs the minutes beside it and tells you
nothing CI will not tell you for free.

| never run locally | why | minutes |
|---|---|---|
| `./gradlew ... :androidApp:connectedDebugAndroidTest` | the emulator | ~17-23 |
| `npm run test:android` | the same thing | ~17-23 |
| `./gradlew ... :webApp:jsBrowserTest` | starts a real browser | ~4 |
| `./gradlew ... --no-daemon ...` | a cold JVM every invocation | +20s each |
| `:core:jvmTest :core:jsNodeTest :core-net:jvmTest :core-net:jsNodeTest :core:koverVerify` together | this is CI's `shared` job | ~4 |
| `npm run test:screens` | the whole worktree compiles cold; this is what killed seven agents | ~12-20 |
| `:androidApp:testDebugUnitTest` with no `--tests` | the same thing by its real name | ~12-20 |

One agent ran the emulator suite, `jsBrowserTest`, the whole `shared` job four
times with `--no-daemon`, and the Worker suite — inside its TDD cycle, for a
two-file change. 26 minutes before it opened a pull request.

### Why the whole screens suite is on that list

Seven agent runs on one request, `remove-task-title`, died in four hours.
Every one of them ended the same way, and the last line of each log says it:

```
The Android red run is a cold compile of the whole worktree.
The Android red run is still in Gradle's configuration phase after 12 minutes.
Waiting on the Android red run to finish, then I'll run it green.
```

An agent's Bash tool stops a command at **10 minutes** and that ceiling
cannot be raised. `npm run test:screens` is `guard.mjs 1200` — twenty
minutes of allowance — and on a worktree that has never been built it uses
most of it, because every module compiles from nothing. So the suite
cannot be run in the foreground, and the only other way to run it is to
background it, which is banned for the reason above: there is no second
turn, so the agent dies there and the request is resumed by the next one,
from cold, forever.

It is not a discipline problem. The command does not fit in the tool, and
telling an agent to run it is telling it to die. CI runs the same suite on
every pull request in its own job, `./gradlew :androidApp:testDebugUnitTest`,
on a machine with no ten-minute ceiling.

So: change Compose screens, run **your own test** by name in the cycle —
`--tests 'YourTest'` is seconds and is not banned — then push and read the
`android` job. If it is red, fix it and push again. One CI cycle is 7m39s
and it is the only way that suite can be made to run at all.

**What you run in the cycle**, and nothing else:

```
./apps/gradlew -p apps :core:jvmTest --tests 'YourTest'
./apps/gradlew -p apps :androidApp:testDebugUnitTest --tests 'YourTest'
npx vitest run test/thing.test.js -t 'the one case'
```

**What you run once, at the end, before the pull request:** `npm test` and
`npm run test:web` — only the ones your diff actually reaches. Not the
Android screens suite: CI runs that one, and the section above says why
you cannot. Then push and let CI do the rest. CI is 7m39s; it is not worth
reproducing on a laptop.

**Never background a command and poll its output.** Not a suite, not a build,
not anything. Run it in the foreground and let it finish. One agent spent 55%
of its run in polling loops and another twelve minutes reading its own
background task files.