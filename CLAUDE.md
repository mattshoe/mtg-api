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
4. **Run the whole suite**, not just your test.
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
| Android screens, JVM | `npm run test:screens` | ~3m30s |
| Android screens, device | `npm run test:android` | ~23m |
| all but the device | `npm run test:all` | ~8m |

Everything goes through `scripts/guard.mjs`, which kills the whole
process group on timeout. Run long things in the background and read
the output file; do not hand-roll `until ... sleep` wait loops, which
become orphans of their own.

`forkEvery(4)` in `apps/androidApp/build.gradle.kts` is load-bearing.
`ComposeRootRegistry` keeps every Compose root ever created in a
weakly-referenced set and every `isIdleNow` copies the whole set, so
without forking the suite grinds to a halt around test 200. Do not
remove it.

## Android state

`MainActivity` must survive a configuration change. State lives in a
`ViewModel`, not in an activity field — a rotation used to empty the
whole app.
