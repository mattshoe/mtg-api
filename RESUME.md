# Where this stopped

**The parity work is done.** All thirty-nine audits in
`ANDROID-PARITY.md` are closed: sections 1 to 4, both web-side bugs,
and the design differences in section 5. Nothing is in flight and no
agent is running.

Green on every suite, all verified on the same tree:

| suite | count | how |
|---|---|---|
| `:core:jvmTest` | 2134 | `npm run test:core` |
| Android screens, JVM | 432 (28 skipped) | `npm run test:screens`, ~3m20s |
| Android screens, device | 424 | `npm run test:android`, ~22m |
| web | 393 | `npm run test:web` |
| worker | 558 | `npm test` |

The 432 and the 424 are the same source. The device runs every test
in `sharedTest`, including all 28 the JVM skips behind
`Parity.needsRealRendering()`; the eight it does not see live in
`src/test` and are JVM-only by design.

**There is still no PR** for `fix/back-and-mana-symbols`. `main` is
`c388bc1`.

## What is left, and it is not much

- **3.2, the card page** — no mana cost, type line, oracle text,
  power/toughness or flavour on *either* platform, because
  `CardDetail` has no fields for them. A missing feature rather than a
  parity gap, and Matt has not asked for it.
- **Section 6, the smaller cosmetics** — chip fills and weights, the
  "going out" tag's colour, the amber on "No source for X", one space
  against two after a ruling date, a placeholder present on one
  platform and not the other, the curve's gradient and its 5dp against
  6px bar gap, thumbnail radius, the two-line card name, the pager's
  left alignment, the Reset button's weight, the line-count
  pluralisation in the edit dialog. Collected but never queued.
- **`MainActivityFacetsTest.facetsLoadOnceAtStartupAndASecondCallDoesNotRefetch`
  is still flaky under load.** It has failed perhaps one run in four,
  always while something else was hammering the machine, always
  passing alone and on a re-run. It is no longer *mute*, though:
  `loadFacets` used to end in `catch (e: Exception) {}`, so three
  attempts at fixing it were working from "facets never loaded" and
  nothing else. It now rethrows `CancellationException` and keeps the
  throwable in `facetsError`, and the test rethrows that with the
  cause attached. The next occurrence will name itself.

## Two judgement calls Matt may want to reverse

Both are one line, both are stated as facts in a test rather than
left implied, and both came out of "match the web exactly".

- **The deck's own name is no longer anywhere on the deck page.** The
  web's open-deck `page-head` carries the back button, the share menu
  and the admin actions and no title, so the hero leads with the
  commander and the deck name is gone.
- **The hero's colours are real mana symbols, not the web's plain
  "WU" letters.** A deliberate departure: Matt asked for symbols
  everywhere colours appear, and the deck tile forty lines up already
  draws them.

## The branch

Everything lives on **`fix/back-and-mana-symbols`**, cut from `main`.
It has no PR yet. `main` is `c388bc1`.

**Do not branch from `parity/android`.** It was merged as PR #12 and
deleted. Fix agents told to use it came back with conflicts against
newer work; that mistake cost a merge resolution already.

Counts are in the table at the top of this file. Screens takes about 3 minutes now, not 85 seconds —
`forkEvery(4)`, and worth every second of it (see "Things that will
bite").

**The device suite is green too: 406 tests, 0 failures, ~21 minutes**
(`npm run test:android`, against the `parity2` AVD). That number
matters more than it looks. 406 against the JVM's 414 is not a
shortfall — it is every test in `sharedTest`, including all 27 the JVM
skips behind `Parity.needsRealRendering()`; the eight the device does
not see live in `src/test` and are JVM-only by design. Run it after any
presentation change: it caught two tests that were green on the JVM and
simply wrong (see below), and it is the only thing that checks layout
against real font metrics, because Robolectric renders every glyph
about 1dp wide.

## Done and merged onto this branch

- **Back and mana symbols** (`3317593`) — back comes off a card in one
  press and returns to the deck it came from; the order now lives in
  `AppState.back()` with `BackTest` pinning it. Colour pips are real
  Scryfall symbols on the filter toggles, the deck stats rows and the
  deck tiles. The mana curve is to scale again.
- **Facets load on Android** (`cde6534`) — audit item 1.1, the biggest
  one. Seven lists across five filter sections were permanently empty.
  Tested through the real `MainActivity.onCreate` against a mock
  engine, not a hand-built fixture.
- **Scroll position survives a card** (`1d8eeab`) — item 1.2. The grid
  and deck scroll state are hoisted above `AppShell`'s `when`.
- **Unsaved mass entry warns before exit** (`ddf3ff2`) — item 1.5. The
  decision is `AppState.wouldExitWithUnsavedEntry` in core; the dialog
  is Android's. "Keep editing" is what back and tap-outside do.
- **The facets test made order-independent** (`6bda3fd`) — it passed
  alone and failed in the suite, which is worse than failing.
- **The web's dead CSS** (`7174408`) — items 2.1-2.3. A failed log row
  now actually paints (tint, bold, and a rule down the leading edge,
  so it is not hue alone); `.chip.warn` and `.chip.off` exist, with
  "not legal" marked by a dashed border rather than another colour;
  and `AppState.toastFailed` gives the toast something to key on, so
  `.toast.bad` is finally reachable. The tests read
  `getComputedStyle`, not class names — and the proof that matters is
  that the old class-name-only test stayed green while the seven new
  ones went red.

## In flight when this stopped

Three fix agents were running, each in its own worktree under
`.claude/worktrees/`. Their branches are on disk whether or not the
agent finished. Check each for a commit, then merge it, run
`npm run test:screens`, and only then move on.

Nothing. Every branch is merged and every worktree that mattered is
gone.

**1.3 (the toast) is done and the branch is deleted.** Not merged —
dropped. Its content had already arrived through another agent's
branch, and the branch itself had fallen 3,681 lines behind: merging
it would have reverted a dozen features. HEAD's `ToastTray` in
`AppShell.kt` has all three of 1.3's requirements — auto-dismiss on
`AppState.TOAST_MS`, tap-to-dismiss via `clickable` with a "Dismiss"
role, and a `fillMaxSize` tray where only the chip paints, docked
top-center under 600dp and bottom-end above it. Checked against the
code, not assumed.

If a branch has no commit, the agent did not finish — reread the item
in `ANDROID-PARITY.md` and relaunch it.

### 1.4 is done — this section is history, kept for the lesson

`parity/android-1-4-autocomplete-dismiss` is deleted. The rework
landed in `6379e44`: the suggestion list is a `Popup` with
`onDismissRequest` and
`PopupProperties(focusable = false, dismissOnClickOutside = true,
dismissOnBackPress = false)`, which gives the window
`FLAG_NOT_FOCUSABLE | FLAG_WATCH_OUTSIDE_TOUCH` — the platform's own
non-consuming outside-touch mechanism — so a control behind the list
still fires. No shell-wide wrapper, and none of the 13 collateral
failures.

Two gaps it is honest about: Robolectric does not route touches
between windows, so `ACTION_OUTSIDE` actually being *delivered* is
device-only (the test asserts the window flags instead, which is what
breaks if this ever becomes a focusable popup); and the new-deck
wizard's commander list closes on tap-outside but not on Back,
because `back()` knows `AppState.complete` and not `NewDeck.hint`.

The lesson, which is why the rest of this section stays:

### what the first attempt got wrong

The autocomplete-dismiss branch is good work and its own suite passed,
but merging it here broke **13 Android screen tests** that have nothing
to do with autocomplete — share menu, rename, add-a-card, scroll
position, the Find button, facets. The failures are all
`Failed to inject touch input`, `performScrollTo() failed` and
`Failed to perform text input`, which is the signature of something
swallowing pointer events app-wide rather than thirteen separate bugs.

The likely cause is `AutocompleteDismissScope` — a watcher the branch
installs at the screen root on `PointerEventPass.Initial` to notice
taps outside the field. Core was unaffected (2124 green), so it is the
Compose wrapper, not the shared `back()` change.

The branch's own tests were green. The whole suite is what caught it.
A root-level `pointerInput` running
`awaitPointerEventScope { while (true) { awaitPointerEvent(Initial) } }`
plus an extra full-screen `Box` at the root of the shell breaks touch
injection for every test in the app, and no amount of care inside the
feature's own tests would have shown that. Reach for the platform
primitive — `Popup`, `DropdownMenu`, `AlertDialog` — before reaching
for a watcher at the root of the tree.

## The queue, in order

Matt's rulings: fix the web where the web is the broken one, and match
the web exactly on the six design differences in section 5.

**Functional, Android**
1. Price badge to bottom-centre (1.6) — he asked for this once already
2. New deck: `onCommanderTyped` and `onPickFile` never passed (1.7, 1.8)
3. `ExportTo.FILE` on Android — share's Download and the Library's
   missing Download are one gap (1.9, 1.10)

**Wrong on the web** — all three done (2.4 earlier; 2.5 and 2.6 in
`faea45d`). The deck tile carries `role`/`tabindex`/`onKeyDown` like
its sibling card row plus an inset focus ring, and the Stats scope
switcher is a `.seg` instead of the wizard's `owner-opt`.

**Wrong on both**
7. Lock does not clear the stored token, so a restart re-unlocks (3.1)
8. `Double.toString()` gives "2" on the web and "2.0" on Android from
   one shared value (3.3). Grep for other Doubles reaching a screen.

**Presentation, Android — all of section 4 is done.** 4.1-4.5
(`b03eea6`): mono and tabular numbers, uppercase labels, art crop
anchors, hairlines, owner-group spacing. 4.6 (`135d899`): the deck
dialogs' errors are a real tinted, ringed, monospace box — the old
plain `Text` resolved to `Ink2`, the exact colour of the prose line
above it. 4.7 (`60574e4`): the tweak sheet is a `BasicAlertDialog`
with a scrim, tap-outside and an 82vh cap; it measured 470dp on a
470dp screen before, Preview past the bottom. 4.8 (`c212f2a`) and 4.9
(`3a4f102`): every `NavPill`, `Ghost` and `Seg` segment has a 48dp
touch target with the painted pill centred inside at its old size,
and the power/toughness box raises `KeyboardType.Phone` so `*` is
reachable.

Two things from that batch worth carrying forward. 4.8 raised the
minimum on *every* `Ghost`/`NavPill`/`Seg` segment, not only the nav
ones the audit named — rows that pack several across a narrow screen
may be tighter than before, and the Library's Copy/Download row had to
become a `FlowRow` because it already overflowed at 320dp. And the
`*`-typing test passes with or without the fix: `performTextInput`
goes in under the IME, so the keyboard was never what blocked it. That
test guards the binding, it does not demonstrate the defect.

**Design** (section 5) — the hero leads with the commander
(`3de211f`), and the nav is with the agent above. The anchored share
menu is the remaining one. The drag-and-drop zone is the item to skip:
a phone has no drag source, so it would be dead code. Say so rather
than building it.

Two judgement calls in the hero worth knowing, both flagged to Matt:
the deck's own name is now absent from the deck page entirely, because
the web's open-deck `page-head` carries no title (one line to put back,
and `DecksParityTest` states it as a fact rather than leaving it
implied); and the colours there are real mana symbols rather than the
web's plain "WU" letters, because Matt asked for symbols everywhere
colours appear and the deck tile forty lines up already draws them.

**Flagged, not queued** — the card page shows no mana cost, type line,
oracle text, power/toughness or flavour on *either* platform, because
`CardDetail` has no fields for them (3.2). A missing feature, not a
parity gap. Matt has not asked for it.

## Things that will bite

- **Fix agents need their own worktree.** Read-only audits can share a
  tree; agents writing production code cannot — concurrent writes
  caused transient compile breaks that made one audit fall back to
  static analysis.
- **`scripts/guard.mjs` keys its pidfile by command**, so two agents
  running the same suite at once will kill each other. Fine for one
  person, wrong for a fan-out. Worktrees dodge it; fix it properly if
  agents ever share a tree again.
- **A pixel test gated behind `needsRealRendering()` has never run.**
  Two landed green on the JVM and were simply wrong. One called
  `onRoot()` with a dialog open, where there are two roots and
  `onRoot()` throws on the ambiguity — unreachable on the JVM because
  the test skips there. The other sized its fixture at ten rows
  because ten rows overflow Robolectric's 470dp screen; they do not
  overflow a real 808dp phone, so the sheet needed no scrolling and
  the test proved nothing. Size a fixture off `screenHeightDp`, never
  off a number that happened to work, and run the device suite before
  believing a gated test.
- **Make a test assert its own preconditions.** The scroll test above
  failed with "the tenth hit already fits, so this proves nothing
  about scrolling", which is why it cost one run instead of five.
- **A test that mounts a component alone can be blind.** Four separate
  bugs survived because of this — the share menu in an absolutely
  positioned frame, Library rows below a lazy grid's fold, the tweak
  sheet in a bare `Box`, the filter panel fed a `Facets` the app never
  produces. Every fix wants one test through the real shell.
- **`createComposeRule` and `Robolectric.buildActivity` fight** over
  the main dispatcher. Together in one test they pass alone and fail in
  the suite.
- **Never read a test count without checking the build succeeded.** A
  killed Gradle test task leaves the *previous* run's XML on disk, so
  the wrapper happily reports yesterday's green. That is exactly how
  "356 tests, 0 failures" got reported out of a run that never
  happened.
- **`ComposeRootRegistry` never shrinks on its own.** It holds every
  Compose root ever created in a weakly-referenced set, and *every*
  `isIdleNow` call copies the whole set. Each dialog and each dropdown
  is its own root, so the set reaches the thousands over a full run —
  and the entries are cleared only by a *full* GC, which never comes
  when the ceiling is 2g and the suite uses 300MB. Around test 200 one
  `waitForIdle()` would spin for twenty minutes. `forkEvery(4)` in
  `apps/androidApp/build.gradle.kts` is the fix and must stay: a fresh
  JVM every four classes is a guarantee rather than a hope about
  collector behaviour. The diagnosis came from `jstack` on the stuck
  worker (RUNNABLE in `getCreatedComposeRoots`, 280s of CPU, parked
  nowhere) plus `jcmd <pid> GC.run`, which let it advance a test
  immediately.
- **A hung test leaves no XML, so the log is the only witness.** Every
  test writes START and END to `apps/androidApp/build/test-order.log`;
  a START with no matching END names the test that hung. The task also
  self-kills at 12 minutes instead of waiting for `guard.mjs`. Both in
  `e1522ba`.
- **A Robolectric test that builds a real `MainActivity` must destroy
  it and flush the global snapshot.** `ComposeIdlingResource` also
  pumps frames while `Snapshot.current.hasPendingChanges()` is true,
  and that is process-global, not per-test — see the `@After` in
  `DownloadDecisionTest`.
- **Merging a far-behind branch duplicates code silently.** The 1.3
  merge auto-merged a whole `ToastTray` function and 13 imports twice
  without raising a conflict, and the same file once had an entire
  `when` block duplicated by a "keep both sides" resolution. Check for
  conflicting overloads after any merge, and resolve hunk by hunk.
- `./gradlew` is at `apps/gradlew`. `--tests` works for `:core:jvmTest`
  and `:androidApp:testDebugUnitTest`, never for `:webApp:jsTest`.
- `npm run test:screens` is 402 Android tests on the JVM in ~3m;
  `npm run test:android` is the same source on a device in ~21 minutes
  and is where the 27 `needsRealRendering` tests actually run.
