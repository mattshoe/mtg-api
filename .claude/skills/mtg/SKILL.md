---
name: mtg
description: Everything an agent needs to change Matt's MTG collection app without breaking it — the architecture, where each kind of code lives, the hard requirements like TDD and platform parity, how to run and read the suites, how to deploy, and the specific mistakes this repo has already shipped. Load this before touching any code in mtg-api.
---

# Working in mtg-api

One collection of Magic cards, three front ends over one API. If you
change behaviour you change it in one place and both platforms get it.

Read `CLAUDE.md` as well — it is the law and this is the map. Where they
disagree, `CLAUDE.md` wins.

## The shape of it

```
src/                  the Cloudflare Worker. JavaScript, ES modules, zero
                      runtime dependencies, no build step
schema.sql            what a database built from scratch gets
migrations/           what the live database gets
test/                 the Worker's vitest suite, real D1 inside workerd

apps/core/            :core — all the logic, all the state, no UI.
                      Kotlin Multiplatform, commonMain
apps/core-net/        :core-net — the HTTP client for the Worker
apps/webApp/          Compose HTML. Real DOM, runs in a real browser
apps/androidApp/      Compose Multiplatform. The phone
frontend/             the static page the Kotlin bundle is served from,
                      plus app.css, plus the old classic.html

requests/             the request intake folder (see requests/README.md)
scripts/              tooling. guard.mjs, the floor and count checkers
```

Live: API `https://mtg-api.mattshoe81.workers.dev`, site
`https://mtg.mattshoe.org` (GitHub Pages, hash-routed), Android as a
signed APK on GitHub Releases.

## Where a change goes

This is the question that decides whether a change is right or a mess.

**Logic, state, formatting, validation, any decision at all → `:core`,
in `commonMain`.** `AppState` in `App.kt` is the whole app's state and
`load` is the only thing that talks to the API. If you find yourself
writing the same `if` in `LibraryPage.kt` and `LibraryScreen.kt`, it
belonged in `:core` and the two copies will drift.

Examples already there: `Guild.kt` names a colour combination,
`Edhrec.kt` builds a rank string and a URL, `CardFacts.kt` turns a card
row into the list of rows both shells render, `ManaCost.kt` parses a
mana cost, `Shell.kt` says which views exist and which are gated.

**The shells render and nothing else.** `apps/webApp/.../web/*.kt` and
`apps/androidApp/.../android/*.kt` are the same screens twice — the file
names line up on purpose (`LibraryPage.kt` / `LibraryScreen.kt`,
`AdminPage.kt` / `AdminScreen.kt`, `DecksPage.kt` / `DecksScreen.kt`).
A new screen is two files and one piece of state.

**The API.** `src/index.js` routes; the comment block at the top of it is
the route index and is kept current. One module per area —
`accounts.js`, `decks.js`, `cards.js`, `query.js`, `prices.js`. Reading
is open; writing needs to pass `mayEdit`.

**Platform storage** goes through the `Store` interface —
`localStorage` on web, `SharedPreferences` on Android. The core never
learns which.

**Colours and sizes** live in `Design.kt`, which mirrors the `:root`
custom properties in `frontend/css/app.css`. `DesignTest` holds the two
to each other, so change both or the test goes red.

## The hard requirements

These are not style. Each one is here because breaking it shipped
something broken.

### 1. Test first, and watch it fail

Write the test. **Run it against the unfixed code and read the
failure.** Check the message names the actual cause. Then the smallest
change that makes it pass, then the whole suite.

Six tests in this repo were written, landed green, and proved nothing —
mounted somewhere the bug could not happen, or asserting on a fixture
the real app never produces, or gated so they had never executed at
all. Ten seconds of watching one fail would have caught every one.

**Record the red in the commit message**, with the real failure text:

```
Red: 5 of 6 in CardFaceParityTest — "no type line", "only the
creature face has a stat box expected:<1> but was:<0>"
```

If some of your new tests pass against the unfixed code, say so and say
why. A regression guard is worth having; calling it proof is not.

### 2. Parity is not optional

A change to the website is a change to the phone, **in the same pull
request**. Matt has said this more times than anything else, in capital
letters. "Almost parity" is a failure.

The way to get it for free is to put the behaviour in `:core` and have
both shells read it. The way to get caught is to ship a web screen and
file the Android one as follow-up work.

If something genuinely cannot exist on one platform, say so in the PR
body and say why.

### 3. Read the BUILD line, never the exit code

`npm run test:screens` has exited 0 over `BUILD FAILED`. A killed Gradle
test task leaves the **previous** run's XML on disk, so anything reading
that directory reports a run that never happened — this repo has
reported "356 tests, 0 failures" out of nothing, and caught three agents
with it.

- grep the output for `BUILD SUCCESSFUL` before believing any count
- `rm -rf` the results directory before a run you intend to trust
- a hung test writes no XML at all, so the XML can never name it;
  `apps/androidApp/build/test-order.log` has a START and an END per
  test and the one with no END is the one that hung

### 4. No suite shrinks

`test/suite-floors.json` holds the committed counts and
`scripts/check-suite-floor.mjs` enforces them in CI. A shrinking suite
is worse than a failing one, because it goes green.

When a suite grows, raise the floor in the same commit:

```
npm run check:floor -- --raise screens=apps/androidApp/build/test-results/testDebugUnitTest
```

It will not lower a floor whatever you pass it. If you genuinely removed
a test, edit the JSON by hand and say why in the message. That is the
point — it takes a deliberate, visible act.

### 5. A schema change needs both files

`schema.sql` is what a fresh database gets, including the one the suite
builds. `migrations/` is what the live one gets. Editing only one has
shipped broken to production both ways round, a day apart.

**An applied migration is never edited.** Wrangler records a migration
as done *by name*, so editing one that has already run is a no-op on the
live database and a double-add on a fresh one — every sign-in failed on
the INSERT. The fix is always another file.
`test/fixtures/migration-hashes.json` makes that checkable: a new name
is ordinary work, a changed hash on an existing name is the mistake.

### 6. Matt is colourblind

Separate things by lightness, never by hue alone. Measure it, do not
eyeball it.

### 7. Done means deployed, on both platforms

Not "PR open". Not "CI green". Not "merged". Deployed and verified live
on the website **and** in the shipped APK. Do not send a progress table
of work that is not deployed; Matt has been explicit and furious about
this twice.

If you are a builder agent under `requests/`, you stop at a green PR and
say exactly that — "PR #N green, waiting on you to merge" — which is not
a claim that anything is done.

## Running the suites

| what | how | time |
|---|---|---|
| worker | `npm test` | ~40s |
| shared core | `npm run test:core` | ~1m |
| web, real browser | `npm run test:web` | ~2m30s |
| Android screens, JVM | `npm run test:screens` | ~3m30s |
| Android screens, device | `npm run test:android` | ~23m |
| all but the device | `npm run test:all` | ~8m |

**`npm run test:core` is not what CI runs.** It is only
`:core:jvmTest`. CI's `shared` job runs four tasks plus coverage, and
the gap has shipped a red build:

```
cd apps && ./gradlew --no-daemon :core:jvmTest :core:jsNodeTest \
  :core-net:jvmTest :core-net:jsNodeTest
cd apps && ./gradlew --no-daemon :core:koverVerify
```

Everything goes through `scripts/guard.mjs`, which kills the whole
process group on timeout. Run long things in the background and read the
output file. Do not hand-roll `until ... sleep` wait loops; they become
orphans.

`forkEvery(1)` in `apps/androidApp/build.gradle.kts` is load-bearing and
costs about five minutes. Do not raise it to make the suite faster —
`ComposeRootRegistry` accumulates every Compose root ever created and
the suite grinds to a halt around test 200 without it. It was
`forkEvery(4)` once, which worked on one laptop and failed on CI.

### Where Android tests live

- `apps/androidApp/src/sharedTest/` — screens, visible to both the JVM
  runner and the device runner. Drive the real `AppShell`, never a
  composable mounted alone. Assert measured geometry and resolved
  styles, never a class name or a constant re-read from the source that
  set it.
- `apps/androidApp/src/androidTest/.../e2e/` — journeys. These launch
  the real `MainActivity` against `FakeWorker`, a `MockWebServer` in
  front of real bundled SQLite loaded with this repo's own `schema.sql`
  and `test/fixtures/seed.sql`. They have found things nothing else
  could, including a two-faced card's name printed twice everywhere.

A test behind `Parity.needsRealRendering()` is skipped on the JVM, which
means it has **never executed**. Prefer a test that runs on the JVM. If
it genuinely needs pixels, run `npm run test:android` before believing
it and say in the commit that it is otherwise unproven.

## CI and deploying

Pull requests run `.github/workflows/apps.yml` — `shared`, `web`,
`android` (emulator included, about fifteen minutes) and `tally` — plus
`test.yml` for the Worker. The pull request is the gate; `apps.yml`
deliberately does not re-run on merge.

Merging to `main` deploys:

- `pages.yml` → the website, on changes under `frontend/` or the apps
- `release.yml` → a signed APK cut as a GitHub release, every push
- `worker.yml` → the API, on changes under `src/`, `migrations/` or
  `schema.sql`

**Merging is Matt's call.** Open the PR, drive it to green, stop.

### Verifying a deploy for real

Not "the workflow was green" — look at the artifact:

```
curl -s --compressed https://mtg.mattshoe.org/kmp/mtg.js | grep -c 'YourNewString'
gh release download <tag> --repo mattshoe/mtg-api --dir /tmp/v
cd /tmp/v && unzip -q mtg-collection.apk 'classes*.dex' && grep -c -a 'YourNewString' classes*.dex
curl -s https://mtg-api.mattshoe81.workers.dev/schema | head
```

## Accounts, roles and collections

Two roles, `user` and `admin`. Every new account is a `user` and can
edit only its own cards. `admin` can do anything including handing out
`admin`, and only accounts Matt decides get it. There is no shared admin
password login any more — you sign into your own account.

The distinction that matters: **`Route.collection` is the collection
*key*, which is what an address carries and what somebody pastes into a
chat. `AppState.resolvedCollection` is the owner slug, which is what
`cards.owner` holds.** They are joined by `GET /c/:key`. Keeping them
apart is what stops an address from being mistaken for permission. Four
places had this wrong once and two tests were pinning the wrong value,
which is why nothing caught it.

`View.operator = true` means a view needs a role, not merely an account.
`Admin.settled` means `/auth/me` has answered — an unanswered question
is not a "no", and treating it as one showed Kayla's decks in Matt's
session.

## Two traps in the tools themselves

**`grep` in this shell is not grep.** It is a shell function that wraps
ugrep with `--ignore-files`, and it silently returns nothing — exit 1, no
output — for paths an ignore file covers. It returned nothing for every
search under `src/` while the string was plainly on line 33. Nothing says
it was filtered; it looks exactly like an honest no-match.

Use `/usr/bin/grep` for anything you intend to draw a conclusion from,
and when a search comes back empty on a file you believe contains the
string, confirm with `sed -n` before believing it. An empty result from
a filtered search is the same false confidence as a test count from a
build that failed.

**A commit message is not evidence.** `git log` is not a record of what
is in the tree. This repository has one commit whose message describes
seven changes in detail, with a `Red:` section naming three test classes,
and whose diff contains four of the seven and none of those classes —
written from the plan instead of from the diff, and merged and deployed
on the strength of it.

So before you write a commit message, run `git diff --cached --stat` and
write only what is in it. Before you believe a commit did something, run
`git show --stat`. And before you tell Matt a thing is deployed, check
the deployed artifact for the string — the curl and the dex grep under
"Verifying a deploy for real" — not the workflow's green tick and not
the commit that claims it.

## Mistakes this repo has already made

Do not re-make these.

- **An unknown scope is never every scope.** An empty owner meant no
  `WHERE` clause and the query returned everybody's cards. It is
  `WHERE 1=0` now. Pooled reads must ask by name.
- **A test that was green while describing something broken** is worse
  than no test. Six of those shipped. See requirement 1.
- **Floors derived by arithmetic instead of counted** — 470 against a
  real 469. Read the number out of the results, never reason about it.
- **A role system whose only bootstrap was a curl.** Nobody in the
  database had `role = 'admin'`, so Admin Settings was invisible on both
  platforms and it looked like the feature was missing. A migration
  (`0004_first_admin.sql`) is the bootstrap.
- **Deriving a deck's colours before its cards were written.**
  `paintDeck` runs *after* `planAndWrite` for that reason.
- **Guessing a column's vocabulary.** `card_colors.kind` is
  `color`/`identity`/`produced`, read out of production — not what it
  looked like it should be.
- **A pull request that shipped half of itself.** #35's message
  described the EDHREC batch — a rank on the carousel, every column on
  the card page, a link out, a flip toggle — and the diff had none of
  it. Four of Matt's asks were reported as deployed and did not exist.
  Nothing caught it because the floors and the suites were consistent
  with the half that did land.
- **An intake audit that only checked emptiness.** `test/intake.test.js`
  now asserts the *format* of every column written at intake, per
  column, and proves it bites.

## Tone

Matt swears a lot and it is not aimed at you. What he wants is the thing
actually finished, on both platforms, verified. Short answers. No
progress tables. No "almost".
