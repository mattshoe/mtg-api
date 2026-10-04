# Where this stopped

Bringing Android to parity with the website, from the 39-feature audit
in `ANDROID-PARITY.md`. Read that first; this file is only the state of
play.

## The branch

Everything lives on **`fix/back-and-mana-symbols`**, cut from `main`.
It has no PR yet. `main` is `c388bc1`.

**Do not branch from `parity/android`.** It was merged as PR #12 and
deleted. Fix agents told to use it came back with conflicts against
newer work; that mistake cost a merge resolution already.

Green as of the last run: core all passing, Android screens 349, web
381, worker 558.

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

## In flight when this stopped

Three fix agents were running, each in its own worktree under
`.claude/worktrees/`. Their branches are on disk whether or not the
agent finished. Check each for a commit, then merge it, run
`npm run test:screens`, and only then move on.

| item | branch | what |
|---|---|---|
| 1.3 | `android-parity-1-3-toast` | auto-dismiss after 5s, tap to dismiss, cannot swallow a press, off the bottom controls |
| 1.4 | `parity/android-1-4-autocomplete-dismiss` | tap-outside / Back close the suggestion list |
| 2.1-2.3 | `worktree-agent-aa3e17db39725e327` | the web's dead CSS: `tr.bad`, `.chip.warn`, `.chip.off`, `.toast.*` |

If a branch has no commit, the agent did not finish — reread the item
in `ANDROID-PARITY.md` and relaunch it.

## The queue, in order

Matt's rulings: fix the web where the web is the broken one, and match
the web exactly on the six design differences in section 5.

**Functional, Android**
1. Price badge to bottom-centre (1.6) — he asked for this once already
2. New deck: `onCommanderTyped` and `onPickFile` never passed (1.7, 1.8)
3. `ExportTo.FILE` on Android — share's Download and the Library's
   missing Download are one gap (1.9, 1.10)

**Wrong on the web**
4. Stats money unformatted, `$5046` against `$5,046` (2.4)
5. Deck tile not keyboard reachable (2.5)
6. Stats scope switcher still on `.owner-opt` (2.6)

**Wrong on both**
7. Lock does not clear the stored token, so a restart re-unlocks (3.1)
8. `Double.toString()` gives "2" on the web and "2.0" on Android from
   one shared value (3.3). Grep for other Doubles reaching a screen.

**Presentation, Android** (4.1-4.9) — mono and tabular numbers,
uppercase labels, art crop anchors, hairlines and owner-group spacing,
dialog error styling, the tweak sheet as a real dialog, tap targets,
the digits-only keyboard that blocks `*`.

**Design, now in scope** (section 5) — hamburger nav with the title and
an Admin group and no Find button, hero leading with the commander,
anchored share menu. The drag-and-drop zone is the one item to skip: a
phone has no drag source, so it would be dead code. Say so rather than
building it.

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
- **A test that mounts a component alone can be blind.** Four separate
  bugs survived because of this — the share menu in an absolutely
  positioned frame, Library rows below a lazy grid's fold, the tweak
  sheet in a bare `Box`, the filter panel fed a `Facets` the app never
  produces. Every fix wants one test through the real shell.
- **`createComposeRule` and `Robolectric.buildActivity` fight** over
  the main dispatcher. Together in one test they pass alone and fail in
  the suite.
- `./gradlew` is at `apps/gradlew`. `--tests` works for `:core:jvmTest`
  and `:androidApp:testDebugUnitTest`, never for `:webApp:jsTest`.
- `npm run test:screens` is 349 Android tests on the JVM in ~85s;
  `npm run test:android` is the same source on a device in ~14 minutes
  and is where the 22 `needsRealRendering` tests actually run.
