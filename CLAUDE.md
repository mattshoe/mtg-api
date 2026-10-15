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

## Every screen is tested on a tablet too

Matt: "you need to test every single feature from this moment forward
in tablet mode". The carousel was built and tested on a phone-shaped
screen, and on his folding phone opened out the card was taller than
the space it had, cut off top and bottom. Every screen test the JVM
ran was on Robolectric's 320 by 470 default, narrower than it is
tall, so nothing could have shown it.

So any test of a screen runs on a tablet as well as a phone, on both
platforms:

- **Android**: wrap the real `AppShell` in `Tablet.Sized(Tablet.UNFOLDED)`
  and `Tablet.Sized(Tablet.LANDSCAPE)`, from
  `apps/androidApp/src/sharedTest/.../Tablet.kt`, **and** put
  `@Config(qualifiers = Tablet.UNFOLDED_QUALIFIERS)` (or `LANDSCAPE_`)
  on the test. `Sized` is what the device gets; the `@Config` gives
  Robolectric a real tablet-sized window, because forcing a tablet
  into its 320 by 470 one shrinks the density and the system bars
  swell until the layout is nonsense. `Tablet.kt` says more.
- **Web**: `Tablet.open(Tablet.UNFOLDED)` in
  `apps/webApp/src/jsTest/.../Tablet.kt` gives an iframe of that size
  with the real stylesheet. Mount the page into its body.
  `position: fixed` and `vh` resolve against the frame, which a plain
  `div` of the same size does not do.

Assert the same measured geometry at each size. `CarouselOnATabletTest`
on both sides is the example.

## `blocked` is permanent, and almost nothing should ever write it

Matt: "A task should NEVER enter blocked unless the process is
COMPLETELY unrecoverable and the agent can no longer function. It is NOT
a temporary status, it is a PERMANENT, COMPLETELY UNRESOLVABLE status."

`blocked` is one of the two things that buzzes his phone — the other is
`deployed`. So it is not a progress report, it is an alarm, and it is
the last thing a task ever says.

**Write it when the work cannot continue at all and no agent will ever
make it continue.** A decision only Matt can make. A credential only he
can supply. A request that contradicts itself.

**Do not write it for anything a retry would fix.** An agent that died,
a rate limit, a red CI job, a worktree with work in it and nobody
working — all recoverable, all `paused`, which says exactly that:
nothing is running, the work is kept, the reason says why. `paused`
notifies nobody.

It flapped before this rule existed. The dispatcher wrote `blocked` for
a stalled worktree on every timer firing, reconcile cleared it, the next
firing wrote it again — once a minute, a push notification each time.
Two changes stop that: a stalled worktree is `paused`, and nothing takes
a task back out of `blocked` except the work actually shipping.

That last part is the cost, and it is deliberate: a task you wrongly
mark `blocked` stays wrong until a person moves it. Which is the right
price for the only word that is allowed to interrupt him.

## Nothing merges to main without a full green suite, run locally

Matt: "Just disable CI and make a HARD RULE that all tasks MUST have a
fully passing test suite locally before merging to main. THE ENTIRE test
suite must pass before merging to main."

CI no longer validates anything. `test.yml` and `apps.yml` are
`workflow_dispatch` only — runnable by hand, run by nothing. The merge
gate is a pull request and that is all it is.

So the suite is proved here, on this machine, before the merge:

```
npm run test:everything
```

That is worker, shell, core, screens, web and the device emulator, in
cheapest-first order, stopping at the first failure. It prints
EVERY SUITE PASSED or it tells you which one went red and that nothing
after it ran. Roughly 35 minutes with the emulator, most of it the
emulator.

**All of it. Not the suites your diff reaches — all of them.** That
rule used to read "only the ones your diff actually reaches", which was
right when CI ran the rest. CI does not run the rest any more. There is
nothing behind you.

`--no-device` exists and skips the emulator, which is twenty of those
thirty-five minutes. It prints a warning saying the run was not a full
pass, and it is not one. Use it while you are working; never as the
thing you merge on.

### It resumes, so a fix does not cost you the suites in front of it

Matt: "Why the fuck are these simple tasks running for 3 hours?!" One
agent had run the whole gate eight times and another four, and that was
the three hours. It started at suite one every time, so a one-line fix
to a Kotlin screen re-ran the Worker suite, the core suite and the web
suite — none of which that line can reach — before getting back to the
thing that broke.

A suite that passes is now remembered in `.gate/passed.json`, against a
hash of the files it can actually see. On the next run it is skipped
while that hash holds, and the output says `already passed on this tree`
rather than pretending it ran. Suites proved before a failure are kept
too, so fixing the red one re-runs the red one.

What makes that safe is in `COVERS` at the top of
`scripts/test-everything.mjs`: a path map per suite, deliberately
generous. **A suite listed against too much re-runs when it need not,
which costs minutes. A suite listed against too little is skipped when
it should have run, which is a suite going green without executing — and
that is worse than one that fails, because it is believed.** If you add
a directory the build reads, add it to every suite that could see it.
Anything no suite claims makes everything run, which is the catch for
forgetting.

`--fresh` ignores all of it and runs the lot. Resuming fails closed: no
git, no skipping.

### The phone runs what needs a phone

Matt: "26 minutes is way too fucking long for validations. That is like
enterprise application length and we are FAR from that." And: "Can't we
move the majority of that into compose tests in the jvm? Only run
CRITICAL flows on an emulator."

They were already JVM Compose tests. Everything in `sharedTest/` ran
**twice**: once on Robolectric as `screens`, 703 tests in 2.4 minutes,
and again on the emulator as `device`, 659 tests in 19 minutes. Same
source, same assertions, eight times slower, because the device run was
defined by directory rather than by need.

Two kinds of test need hardware and `scripts/device-classes.mjs` works
them out from the source:

- **Anything calling `Parity.needsRealRendering()`** — those SKIP on the
  JVM, so the phone is their only run. Taken per METHOD, not per class:
  by class it was 279 tests and ten and a half minutes, because one
  gated test drags along every other test in its file.
- **Everything in `androidTest/`** — the journeys that launch the real
  activity over a real socket.

**19 minutes to 1m47s**, 35 tests. `npm run test:android:all` still runs
the lot for before a release.

The list derives itself, so a new gated test is on the phone the moment
it is written. `test/device-classes.test.js` holds the dangerous part:
a gated test left off that list runs **nowhere** — skipped on the JVM,
never asked for on the phone — and would not go red, it would simply
stop existing.

### What this costs, said plainly

The device suite is 580 tests that only ever run on an emulator. Before,
CI ran them on every pull request and nobody had to think about it. Now
they run when somebody runs them. If an agent skips that step the tests
do not fail — they simply never happen, and the first person to find out
is Matt, with the app in his hand.

That is the trade. It was made deliberately: CI had become slower and
less reliable than the laptop, and a gate that is red for reasons of its
own is not a gate.

### Deployment still happens on merge

`worker.yml`, `pages.yml` and `release.yml` still run when main moves,
and they no longer re-run the suite first. Matt: "MAKE SURE DEPLOYMENT
IS NOT STILL RUNNING THE TEST SUITE BEFORE DEPLOYING!!" Whether main is
fit to deploy is decided before the merge, not after it.

## Happy-path tests are not enough, ever

Matt: "HAPPY PATH ONLY TESTING IS NOT EVER EVER EVER EVER EVER
SUFFICIENT!!!!!"

The share-to-collection flow — the feature the Android app exists for —
broke three times the same way and shipped green every time. Both shells'
`work` helper caught a failed call, raised a toast and stopped. A toast
does not clear a spinner, so the wizard sat on "Working / Checking…"
forever with no way out but killing the app. `MassEntry.failed()` existed,
was unit-tested, and nothing ever called it.

Every e2e test launches the real app against a fake Worker with real
SQLite, and every one of them lets the server answer normally. The catch
block had never run in any test, on either platform. That is how it
reached a real person three times.

**So: anything that calls the network gets the failing test as well as the
working one.** Make the server answer 500, or 409, or drop the connection,
and assert what the person is left looking at:

- the spinner is gone
- there is an error that says what happened
- they can try again without restarting the app

A green suite over an untested catch block is worse than no suite, because
it is believed.

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

## What the gate enforces beyond the tests passing

**CI enforces nothing. It does not run.** This section used to open
"Every test runs on every pull request" and that has been false since
the day `test.yml` and `apps.yml` became `workflow_dispatch` only. A
pull request here has no checks at all. `npm run test:everything` is the
only thing standing between a change and main, and it carries the two
checks that used to live in CI:

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

Those go through `scripts/guard.mjs`, which adds `--daemon` and
`--configuration-cache` for you. Both matter more than they sound:
`~/.gradle/gradle.properties` has carried `org.gradle.daemon=false`
since July 2024, a machine-wide setting the repo cannot override from a
project file, so every Gradle command here was starting a JVM and
throwing it away. Gradle said so on every run — "a single-use Daemon
process will be forked" — and nobody read it. Re-running an unchanged
`:core` test went from 11.9s to 0.8s, and a real edit on `:androidApp`
from 46.5s to 24.9s. If you invoke `gradlew` directly rather than
through an npm script, pass both flags yourself. `MTG_GRADLE_PLAIN=1`
turns them off when you need to know whether the cache is what is lying
to you.

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

## Ask for several things at once

Measured across every agent log in this repo: **9,220 tool calls over
9,220 turns, and not one turn ever made two calls.** A turn costs about
25 seconds whatever is in it, which is 110 of the 190 hours these tasks
have taken — more than every command they ran put together.

Independent calls go in one message. Four file reads, three greps and a
`git status` is one turn, not eight. The test is dependency: if you need
the first answer to know what to ask second, they are two turns. If you
are working through a list, they are one.

Suites are the exception and still run one at a time in the foreground,
because they contend for the same machine.

This rule lived here alone for a while and did nothing: five agents then
ran 184, 153, 103, 86 and 54 tool calls over exactly that many turns,
0% batched, every one of them. It is in the dispatcher's launch prompt
now as well, which is the one text an agent cannot skip. If it is still
0% next time it is measured, the rule is not the fix and something else
has to be.

## One agent at a time, and a queue you can see

Matt: "I thought you optimized things. I have several tasks in flight,
all exceeding 2 hours already."

Five were running. Each had already spent 20 to 46 minutes inside `npm
run test:everything`, and the gate needs the machine to itself — it
holds `~/.mtg-gradle.lock` across its Gradle suites and the device one
alone is twenty minutes. Five exclusive 25-minute slots is two hours of
queueing before anybody does any work. Arithmetic, not inefficiency,
and making individual commands faster does not touch it.

Matt: "Update it so only ONE task runs at a time. A very very SIMPLE
queue system. NOTHING FANCY. JUST ONE AT A TIME."

So `dispatch.sh` starts nothing while one is already working.
`MTG_AGENT_CAP` raises it without an edit, for when the machine is not
the constraint. On a 16GB laptop it also costs memory: a gate run was
killed by the system while an agent's device suite held the emulator.

The queue is the request folder, oldest file first — "First come first
serve" — and `queued` is the status a ready request waits in, so the
app shows what has not been picked up. `pending` is a different thing
and stays: that is a task sent from the phone which the laptop has not
collected yet. Both shells already print `status.word`, so the word
turning up in the list needed no UI code; `Tasks.active` sorts queued
rows by when they were sent rather than by title, because with one
running the order is the only thing worth showing.

## An action is an icon, not the word for it

Matt: "The agents keep doing stupid shit like using text instead of
icons. Instead of an X icon or trash icon, just using the text
'remove', same with share and edit etc etc. This looks terrible and
dated."

A row of cards with `[Remove] [Edit] [Rename]` spelled out reads like a
2004 admin panel. Every app a person actually uses puts a bin, a pencil
and a share arrow there. So **delete, remove, trash, share, edit,
rename, copy, clear and discard are drawn, not written.**

Both platforms already have somewhere for them to live:

- **Android** — `apps/androidApp/.../NavIcons.kt`. Canvas paths with
  `at`, `line`, `box`, `circle` and `path` helpers. The nav icons are
  the examples to copy.
- **Web** — `apps/webApp/.../Icons.kt`. A `<span>` masked to a shape by
  `currentColor`, the shape itself one CSS rule. Not an inline `<svg>`:
  Compose HTML builds elements in the HTML namespace and an `<svg>`
  there renders as nothing at all, which is what the first attempt did.
  Not an `<img>` either — it cannot take the colour of the text around
  it, so it would not dim with its button.

Draw it on **both** platforms in the same change, the same shape, like
every other piece of parity in this repo.

**The accessibility label stays a word.** `contentDescription =
"Remove"` on Android, `aria-label="Remove"` on the web. An icon with no
label is worse than the word, because a screen reader then reads
nothing at all.

Confirmation dialogs are the exception: when the whole dialog asks
"Delete this deck?", the button underneath says `Delete` in words,
because at that point the word *is* the content.

`test/icon-actions.test.js` holds this. It is a ratchet, not a
cleanup: the labels in `test/fixtures/text-label-actions.json` are what
the codebase had when the rule was written, so a NEW one fails
immediately, and a fixed one comes off the list in the same commit. The
list can only ever shrink.

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

## Commands you do not run by hand

`npm run test:everything` runs all of these, in order, behind
`scripts/guard.mjs` so none of them is subject to your ten-minute tool
limit. Running one by hand in your TDD cycle costs the minutes beside it
and tells you nothing about the line you just changed.

This section used to say "CI runs these" and "never run locally". CI
runs nothing now; the gate runs them, on this machine, and you are the
one who starts it. The list is what it costs, not what is forbidden.

| never run locally | why | minutes |
|---|---|---|
| `./gradlew ... :androidApp:connectedDebugAndroidTest` | the whole emulator suite | ~17-23 |
| `npm run test:android:all` | the same thing | ~17-23 |
| `./gradlew ... :webApp:jsBrowserTest` | starts a real browser | ~4 |
| `./gradlew ... --no-daemon ...` | a cold JVM every invocation | +20s each |
| `:core:jvmTest :core:jsNodeTest :core-net:jvmTest :core-net:jsNodeTest :core:koverVerify` together | the whole shared job | ~4 |
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
telling an agent to run it is telling it to die.

So: change Compose screens, run **your own test** by name in the cycle —
`--tests 'YourTest'` is a second or two now the daemon is on, and is not
banned — then let `npm run test:everything` run the whole suite once at
the end. It puts every suite behind `guard.mjs`, which has no
ten-minute ceiling, and it resumes, so a second run after a fix pays for
the suite that broke rather than all six.

**What you run in the cycle**, and nothing else:

```
./apps/gradlew -p apps :core:jvmTest --tests 'YourTest'
./apps/gradlew -p apps :androidApp:testDebugUnitTest --tests 'YourTest'
npx vitest run test/thing.test.js -t 'the one case'
```

**What you run once, at the end, before you merge:** `npm run
test:everything`, all of it, and it must print EVERY SUITE PASSED.
Nothing runs behind you.

**Never background a command and poll its output.** Not a suite, not a build,
not anything. Run it in the foreground and let it finish. One agent spent 55%
of its run in polling loops and another twelve minutes reading its own
background task files.