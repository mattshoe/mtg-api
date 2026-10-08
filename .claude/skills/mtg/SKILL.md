---
name: mtg
description: Everything an agent needs to change Matt's MTG collection app without breaking it. Three core requirements, none negotiable: platform parity, so web and Android ship the same change in the same pull request, always; test-driven development with a red you actually watched fail; and green CI as the gate, so merge your own work, never ask permission for a green PR, then verify the deployed artifact. Also the architecture, where each kind of code lives, TDD with a watched red, how to run and read each suite, how to deploy, and the mistakes this repo has already shipped. Load this before touching any code in mtg-api.
---

# Working in mtg-api

One collection of Magic cards, three front ends over one API.

**Three things are core, and none of them is negotiable:**

1. **Parity.** The website and the phone ship the same change, in the
   same pull request. Not eventually, not in a follow-up, not "almost".
2. **Test-driven, with a red you watched fail.** Write the test, run it,
   read the failure, then fix it. Six tests here landed green and proved
   nothing.
3. **Green CI is the gate.** Merge your own work, do not ask permission
   for a green pull request, then check the deployed artifact.

Everything else in this document is detail. Those three are the job, and
each has its own section below.

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
`ManaCost.kt` parses a mana cost, `CardFilters.kt` builds the Library's
SQL, `Shell.kt` says which views exist and which are gated, `Roles.kt`
holds the two roles and the Admin Settings search.

Check a file exists before you rely on it. An earlier draft of this
document listed `Edhrec.kt` and `CardFacts.kt` here on the strength of
#35's commit message, and neither has ever existed — which is the trap in
the next section, committed by the person writing the warning about it.

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

### 1. Parity. This is the one that matters most

**A change to the website is a change to the phone, in the same pull
request.** There is no version of "done" that covers one platform.

Matt has said this more times and more loudly than anything else:

> "WHY THE FUCK IS THE ANDROID APP LINK IN THE FUCKING HEADER STILL!!!!
> I FUCKING SAID PARITY!!! NOT 'ALMOST PARITY'!!!!!"

> "You didn't fucking add admin settings to the Android app?!?!?! I TOLD
> YOU A THOUSAND FUCKING TIMES THAT YOU NEED TO KEEP FUCKING PARITY!!!!!"

Both of those were shipped by an agent that did the web half, tested it,
deployed it, and reported the work finished. Do not be the next one.

#### How to actually get it

**Put the behaviour in `:core` and have both shells read it.** That is
the whole technique. If the same `if` is written twice, once in
`LibraryPage.kt` and once in `LibraryScreen.kt`, they will drift, and the
drift will be invisible until Matt finds it.

The screen files are deliberately paired, and the pairing is the
checklist:

| `:core` | web | Android |
|---|---|---|
| `Library.kt`, `CardFilters.kt` | `LibraryPage.kt` | `LibraryScreen.kt` |
| `Decks.kt`, `DeckStats.kt` | `DecksPage.kt`, `DeckStatsPanel.kt` | `DecksScreen.kt` |
| `CardDetail.kt` | `CardPage.kt` | `CardSheet.kt` |
| `Roles.kt` | `AdminPage.kt` | `AdminScreen.kt` |
| `MassEntry.kt` | `MassEntryPage.kt` | `MassEntryScreen.kt` |
| `NewDeck.kt` | `NewDeckPage.kt` | `NewDeckScreen.kt` |
| `Stats.kt` | `StatsPage.kt` | `StatsScreen.kt` |
| `DeckTweak.kt` | `DeckTweakSheet.kt` | `DeckTweakSheet.kt` |
| `Facets.kt` | `FilterPanel.kt` | `FilterSheet.kt` |
| `Shell.kt` | `AppShell.kt`, `AppNav.kt` | `AppShell.kt` |
| `Overlay.kt` | `Overlays.kt` | `Overlays.kt` |
| `Design.kt` | `frontend/css/app.css` | `Theme.kt` |

`Shell.kt` is why there is one answer to "which screens exist and which
are gated" rather than two. Add a view there, not in a nav bar.

#### Before you open the pull request

Walk this, every time. It is four commands and it is the difference
between finished and the two quotes above.

1. `git diff --stat origin/main` — does the list contain **both** a
   `webApp/` file and an `androidApp/` file? If only one, justify it in
   the next step or go and do the other half.
2. Is there a test for the new behaviour on **both** sides?
   `apps/webApp/src/jsTest/` for the browser,
   `apps/androidApp/src/sharedTest/` for the phone. A `:core` test alone
   does not prove either shell renders it.
3. Did **both** suites run green — `npm run test:web` *and*
   `npm run test:screens` — read off the `BUILD SUCCESSFUL` line?
4. Does the PR body say, in words, what the change looks like on each
   platform?

#### The only acceptable exception

A thing that genuinely cannot exist on one platform — a browser
download, an Android share sheet. Then say so **in the PR body**, say
why, and say what the other platform does instead. Silence is not an
exception, and "I will do Android next" is not one either.

### 2. Green CI is the gate. Merge your own work

**Do not ask permission to merge a green pull request.** Matt, on being
asked:

> "WHAT THE FUCK ARE YOU ASKING MY PERMISSION FOR?!?! THAT'S WHAT
> FUCKING CI IS FOR!!!!"

Every check green means:

```
gh pr merge <n> --squash
```

Green means **all** of them — `shared`, `web`, `android` (the emulator,
about fifteen minutes), `tally`, and both worker `test` jobs. Never with
a check pending or skipped. Never `--admin`. Never force anything past a
failure: a red check is a thing to fix, not a thing to get around.
`--admin` and a push to `main` are both refused by the PreToolUse hook in
`.claude/settings.json`, so trying produces a blocked tool call.

Not `--delete-branch`, though. It makes `gh` check out the base branch in
the worktree you are standing in, and the stale tree that leaves behind
made a fully merged request read as unfinished. Leave the branch.

**If you are a request-builder, none of this is yours.** You stop at an
open pull request and `scripts/intake/dispatch.sh` does the waiting, the
check verification, the merge, and the post-merge artifact check below.
The `apps` job is 13-17 minutes and three of four builders ended their
turn rather than sit through it, which in a headless run means never
coming back. Read `.claude/agents/request-builder.md`.

Working by hand, all of it is yours, including the artifact check. The
dispatcher's version is `verify_shipped` in `scripts/intake/dispatch.sh`:
it resolves the squash commit from `gh pr view --json mergeCommit`, watches
only the `pages` and `release` runs for that sha with a deadline, and then
greps the deployed `mtg.js` and the released APK's dex for a marker string
the builder wrote. Without a marker it says it verified nothing rather
than reporting success, and the outcome goes into the filed request.

Merging deploys — `pages.yml` publishes the website and `release.yml`
cuts a signed APK. So merging is not the end either. Watch the deploy
runs, then **check the shipped artifact carries the change**, with the
curl and the dex grep under "Verifying a deploy for real". A green deploy
workflow is not proof.

The only thing that stops at a green PR is a request file whose
frontmatter says `merge: ask`, which triage sets only for something
genuinely risky — a schema change, or anything touching auth or who can
edit whose collection.

### 3. Test-driven, with no exceptions. This is core

Along with parity, this is what the repo is. There is no size below which
it stops applying, no deadline that suspends it, and no "it is only a
one-line change".

The cycle, in this order, every time:

1. **Write the test.** It describes the behaviour you are about to add,
   or the bug you are about to fix, in the words a person would use.
2. **Run YOUR test. Watch it fail.** Not "it should fail" — run it, read
   the failure, and check the message names the actual cause.
3. **Write the smallest production change that makes it pass.**
4. **Run YOUR test again and watch it pass.**
5. **Record the red in the commit message**, with the real failure text.

**The cycle is narrow. Keep it narrow.** One test, or at most the one file
it lives in:

```
npx vitest run test/decks.test.js -t 'the name a deck is renamed to'
./apps/gradlew -p apps :core:jvmTest --tests 'DeckStatsTest'
./apps/gradlew -p apps :androidApp:testDebugUnitTest --tests 'LibraryGridTest'
```

The raw `./apps/gradlew` form, not an `npm run test:screen` — there is no such
script. A narrow-test wrapper that routes through `scripts/guard.mjs` is
planned and not written, and naming a command that does not exist is the same
defect as the `./gradlew` path this replaced.

Seconds, not minutes. Step 4 used to read "run the whole suite, not just
your test", and that line cost real hours: four suites is about seven
minutes, a cycle needs a red run and a green run, and a five-part change
then spends over an hour waiting before anybody thinks about anything.
Running thousands of other people's tests after every edit is not
thoroughness — it tells you nothing about the line you just changed, and
it is the single most expensive habit an agent can pick up here.

**Then once, when the work is finished and before the pull request, run
the full set.** That is where a whole-suite run belongs, and everything
below about reading the BUILD line and not letting a floor drop applies
to it. CI runs all of it on the pull request as well, which is what CI is
for.

#### Why step 2 is the whole discipline

Six tests in this repository were written, landed green, and were later
found to prove nothing. Four were mounted somewhere the bug could not
happen:

- a share-menu test mounted the menu in an absolutely positioned frame,
  so the off-screen bug it existed to catch could not occur
- a Library test asserted on rows below a lazy grid's fold, which are
  never composed
- a tweak-sheet test hosted the sheet in a bare `Box`, which has no
  screen to run off, nothing behind it to dim and no outside to tap
- a filter-panel test fed a hand-built `Facets` the real app never
  produces, while the real facet loader did not exist

The other two were gated behind `needsRealRendering()` and had therefore
never executed at all. Every one of the six would have been caught by ten
seconds of running it against the unfixed code.

#### What the test has to do

- **Drive the real object.** On Android, through `AppShell` — not a
  composable mounted alone, which is how four of the six happened. On the
  web, the real page composable in a real browser.
- **Assert a resolved fact**: the computed style, the measured geometry,
  the `TextStyle` read back through semantics, the state the app actually
  holds. Never a class name. Never a constant re-read from the source
  that set it.
- **Fail with a sentence that names the cause.** "the tenth hit already
  fits, so this proves nothing about scrolling" cost one run.
- **Live where both runners see it**, for Android screens:
  `apps/androidApp/src/sharedTest/`.
- **Exist on both platforms** when the change is on a screen — see
  requirement 1. A `:core` test alone does not prove either shell renders
  anything.

#### The red goes in the commit message

Nothing checks it and nothing can; a commit is a finished thing and the
order its parts were written in leaves no trace. CI once policed a `Red:`
trailer and Matt was right about what that was: you can satisfy a message
format perfectly while doing the opposite of TDD.

Write it anyway, with the actual failure:

```
Red: 5 of 6 in CardFaceParityTest — "no type line", "only the
creature face has a stat box expected:<1> but was:<0>"
```

**If some of your new tests pass against the unfixed code, say so and say
why.** A regression guard is worth having; counting it as proof is not,
and six shipped tests here were doing exactly that.

#### Gated tests are not coverage

A test behind `Parity.needsRealRendering()` is skipped on the JVM, which
means it has never run. Prefer one that runs on the JVM. If a check
genuinely needs pixels, run `npm run test:android` before believing it,
and say plainly in the commit that it is otherwise unproven.

### 4. Read the BUILD line, never the exit code

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

### 5. No suite shrinks

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

### 6. A schema change needs both files

`schema.sql` is what a fresh database gets, including the one the suite
builds. `migrations/` is what the live one gets. Editing only one has
shipped broken to production both ways round, a day apart.

**An applied migration is never edited.** Wrangler records a migration
as done *by name*, so editing one that has already run is a no-op on the
live database and a double-add on a fresh one — every sign-in failed on
the INSERT. The fix is always another file.
`test/fixtures/migration-hashes.json` makes that checkable: a new name
is ordinary work, a changed hash on an existing name is the mistake.

### 7. Matt is colourblind

Separate things by lightness, never by hue alone. Measure it, do not
eyeball it.

### 8. Done means deployed, on both platforms

Requirements 1 and 2 again, at the far end of the pipeline.

Not "PR open". Not "CI green". Not "merged". Deployed and verified live
on the website **and** in the shipped APK. Do not send a progress table
of work that is not deployed; Matt has been explicit and furious about
this twice.

A builder under `requests/` does NOT merge its own work and does not
verify the deploy — `scripts/intake/dispatch.sh` does both, and §2 above
says so. This paragraph said the opposite for a whole round, in the skill
the builder is ordered to load first.

What does not change is what "done" means. Reporting "PR green" is not
reporting done; reporting "merged" is not either. Done is the string in
the deployed `mtg.js` and in the shipped APK's dex — which is why the
dispatcher asks the builder for a greppable marker and then looks for it
in both.

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
means it has **never executed** — see requirement 3.

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

**Merge on green.** Matt: "WHAT THE FUCK ARE YOU ASKING MY PERMISSION
FOR?!?! THAT'S WHAT FUCKING CI IS FOR!!!!" Every check green means
`gh pr merge <n> --squash`, not a question. Then watch the deploy and
check the artifact below — a green deploy workflow is not proof the change
is live. (A request-builder does not do this at all: it stops at an open
pull request and the dispatcher merges. See above.)

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

### Three identifiers, and the lines between them

Matt: "THE FUCKING USER ID NEEDS TO BE PRIVATE AND DIFFERENT FROM USER
KEY!!!!! USER KEY IS NOT SUFFICIENT TO MUTATE DATA!!! ONLY USER ID!!"

**`users.key`** — `bprh3d2s`, 8 random base32 characters. **Public.** It
is what `/c/<key>` carries and what a shared link is made of. It names a
collection to *look at*. It is **not a credential** and must never
authorise a write.

**`users.id`** — the integer primary key. **Private.** Never in a URL,
never in a response body, never in a page, never logged. The only thing a
mutation may be decided by, reached only by resolving the session cookie
through `sessions` to a row in `users`. The shells never hold one.

**Slugs** — every one of them is being deleted, and you must not add
another. Matt: "FUCK THE SLUG!!! WHAT THE FUCK DO YOU NEED A SLUG FOR?!"
and "WE'RE GOING TO HAVE FUCKING COLLISIONS IN URLS ALL OVER THE FUCKING
PLACE".

An identifier derived from text a person typed collides as soon as there
is a second person, and both slugs in this schema already do:

- `users.slug` is what `cards.owner` holds, so ownership is two strings
  happening to match — which is what emptied Kayla's collection
- `decks.slug` is `UNIQUE` **globally** (`schema.sql:54`) and every lookup
  is `WHERE slug = ?` with no owner, so two accounts cannot own a deck
  with the same name

**The rule: an address is a random opaque key, an identity is an id, and
there is nothing in between.** A name is free text that anybody may reuse.
Do not add a column called `slug`, and do not derive an identifier from
anything a person typed.

The two remaining ones, `tags.slug` and `card_tags.tag_slug`, hold
Scryfall Tagger's vocabulary rather than an address of ours, and are being
renamed to `tag` — the values are fine, the word is not.

See `requests/cards-owner-should-be-a-user-id.md`.

In the app, `Route.collection` is the **key** — what an address carries —
and `AppState.resolvedCollection` is the owner, joined by `GET /c/:key`.
Keeping them apart is what stops an address being mistaken for
permission. Four places had this wrong once, and two tests were *pinning*
the wrong value, which is why nothing caught it.

**So: a key lets you read. Only a session that resolves to an id lets you
write.** If you find a key or a slug reaching a permission decision, that
is a security bug, not a style question.

**No account is ever deleted unless Matt asks for that account by
name.** An empty collection is not a reason. Account id 2
(`matthew.shoemaker.277@gmail.com`, slug `matthew-shoemaker`, key
`t4pee71g`, role `user`) is Matt's test account and owns no cards and
no decks by design. Leave it alone.

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
