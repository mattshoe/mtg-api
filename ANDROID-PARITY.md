# Android against the website

**Everything in this report is done.** Sections 1, 2, 3 and 4 are
closed, both web-side bugs are fixed, and the design differences in
section 5 are settled — the three Matt ruled on are built, and the
drag-and-drop zone is deliberately skipped because a phone has no drag
source. The one thing left open is 3.2, which was never a parity gap:
the card page shows no mana cost, type line, oracle text,
power/toughness or flavour on *either* platform, because `CardDetail`
has no fields for them. That is a missing feature, and Matt has not
asked for it.

The findings are kept below as written, in the present tense they were
written in. They are a record of what was wrong, not a to-do list.

Thirty-nine single-feature audits, one agent each, web as the
reference. Every finding below was either measured on a running build
or read out of both sources with a file and line; "looks different"
was not accepted.

Grouped by cause rather than by screen, because several screens turn
out to share one defect — the facet loader alone accounts for seven
broken lists across five filter sections.

Verdicts: 7 matched outright, 26 partial, 5 differ, and a sweep for
dead controls came back clean.

---

## 1. Wrong on Android

**1.1 Facets are never loaded. Seven lists are permanently empty.**
The web calls `FacetQueries.everything` and `FacetQueries.decks` at
startup and fills `app.facets`. The whole of `androidApp/src/main`
mentions facets exactly once — `AppShell.kt:168`, passing the empty
default down to `FilterSheet`. There is no loader. So on the phone the
deck picker, card types, set types, layouts, frames, borders and the
format dropdown are all empty or stuck on "loading…", and five of the
ten filter sections are unusable.
Two things hid this: `AppShell.kt:168` carries a comment claiming to
fix exactly this problem, and `LibraryParityTest` feeds a hand-built
`Facets` fixture straight into the composable, bypassing
`MainActivity`.

**1.2 Your place in the list is lost on every navigation.**
The web has `Scroll.kt`, which remembers `window.scrollY` per route and
gives up if you take over. Android has nothing. `AppShell`'s
`when (state.view)` composes one branch at a time, so the grid state is
disposed when you open a card and recreated empty when you come back.
Proven: scrolled to row 39, round-tripped, came back at the top. This
is the bug Matt reported on the web.

**1.3 The toast has none of its fixes.**
No auto-dismiss (the web clears after 5s), no tap-to-dismiss, and no
`pointer-events: none` tray pinned out of the way. Android renders it
in-flow at the end of the screen's column. This is "the stupid toast
covers the buttons", still live.

**1.4 The autocomplete list cannot be dismissed.**
No tap-outside, no Escape, no Back — zero `onDismiss` references in the
Android app. It also has no keyboard navigation, which makes its own
highlight styling dead code. Same bug class the web had and fixed.

**1.5 Leaving mass entry discards your list in silence.**
The web blocks the tab close on `beforeunload` when `entry.unsaved`.
Android's back runs `finish()` with no check and no dialog.

**1.6 The Library price badge is bottom-left.**
Matt asked for bottom-middle; the web is `left:50%; translateX(-50%)`,
Android is `Alignment.BottomStart`. Measured: badge centre x=26 against
art centre x=83.5.

**1.7 The new deck commander box never suggests anything.**
`AppShell` has no `onCommanderTyped` parameter at all, so the dialog
takes its no-op default and `MainActivity` has no debounce for it.

**1.8 "Upload a file" in the new deck wizard is a dead button.**
Same call site omits `onPickFile`; the only one the shell forwards goes
to mass entry.

**1.9 Share's "Copy" and "Download" do the same thing.**
`MainActivity.shareDeck` never reads its `where: ExportTo`. Clipboard
for both is a defensible platform choice; offering two menu rows that
behave identically is not.

**1.10 The Library offers no Download, only Copy.**
`ExportTo.FILE` is unimplemented on Android generally — 1.9 and 1.10
are one gap with two symptoms.

## 2. Wrong on the web

The reference is not always right. These are Android-is-correct cases.

**2.1 A failed log row is marked with nothing.** `ConsolePage.kt:103`
sets `classes("bad")` on the `<tr>`, and no `tr.bad` or bare `.bad`
rule exists — only `.chip.bad`, `.tag.bad`, `.toast.bad`. A failed
request looks exactly like a successful one. Android marks it three
ways. This is an accessibility bug on the reference.

**2.2 `.chip.warn` and `.chip.off` are undefined**, so "restricted" and
"not legal" both fall back to the same grey. Android gives them
distinct treatments.

**2.3 `.toast.ok` and `.toast.bad` are dead** — the class is never set,
so an error toast and a success toast are identical on the web.

**2.4 Stats money is unformatted on the web.** `$5046` against
Android's `$5,046`; the web skips the shared `Prices.money`.

**2.5 A deck tile is not keyboard reachable on the web** — no role, no
tabindex, no key handling, unlike its sibling card row. Android's is.

**2.6 The web's Stats scope switcher still uses `.owner-opt`**, the
widget the web's own test says wraps badly on a phone and which was
replaced everywhere else.

## 3. Wrong on both

**3.1 Locking does not lock.** Neither platform clears the persisted
token — only the in-memory one. Reload or restart and you are admin
again.

**3.2 The card page shows no card.** No mana cost, type line, oracle
text, power/toughness or flavour on either platform, because
`CardDetail` has no fields for them. `app.css` still carries dead
`.oracle` and `.flavor` rules. Not a parity gap; a missing feature.

**3.3 `Double.toString()` is not the same on JS and JVM.** A deck
averaging exactly 2.0 mana reads "2" on the web and "2.0" on Android,
from one shared value. Any shared `Double` that reaches a screen
through `toString()` has this.

## 4. Cross-cutting Android presentation

Each is one fix with many symptoms.

**4.1 Numbers are not fixed-width.** The web uses `var(--mono)` for
curve counts, axis labels, card-row quantities and library prices, and
`tabular-nums` for the pager so Next does not jog. Android applies none
of it, though `Theme.kt` already has the convention.

**4.2 Uppercase labels are missing.** The web `text-transform`s panel
headings, figure labels and group headings; Compose has no equivalent
and nothing uppercases the strings.

**4.3 Art crops are centred, not top-biased.** The web anchors every
crop high — tile 38%, hero 34%, card row and token 32% — because that
is where faces are. Android passes no `alignment` anywhere.

**4.4 Hairlines and borders are missing**: no rule under an owner
heading, no divider between card rows, no thumbnail border, no seam
between figure cells, no shaded band behind a group heading.

**4.5 Owner groups are not set apart.** The web gives 28px between
shelves against 11px inside one; Android uses a uniform 8dp
everywhere, so the grouping reads as one list.

**4.6 Error text in the three deck dialogs has no visual treatment** —
the web has a tinted bordered `.err` box, Android plain text.

**4.7 The tweak sheet is not a dialog.** A bare `Column`: no scrim, no
tap-outside-to-close, no height cap. Every other Android overlay uses
`AlertDialog`. The parity suite hosts it in a bare `Box`, so it could
not see this.

**4.8 Tap targets.** Nav Lock/Unlock/Find are ~24dp against their own
tab pills at ~30dp, both under the 48dp guideline; the stat operator
segments divide unweighted space five ways.

**4.9 The power/toughness box raises a digits-only keyboard**, so `*`
cannot be typed — and `*` is a legitimate value the core comments on.

## 5. Design differences, for Matt to rule on

Not bugs. Matching the web here might make Android worse.

- **Nav collapse.** Web is a hamburger at every width; Android is a
  scrolling pill row that never collapses.
- **The Find button.** Android has one; the web deliberately has none
  and a test asserts its absence. A phone has no Cmd+K.
- **Share goes to the clipboard**, with wording that never claims a
  download happened.
- **The share menu is inline**, which is structurally why Android
  cannot reproduce the off-screen bug the web had to fix.
- **No drag-and-drop file zone** on a phone.
- **Hero hierarchy.** Web leads with the commander name and folds the
  count and colours into the small line; Android leads with the deck
  title and drops the colours.

## 6. Smaller cosmetics

Collected in the per-feature reports: chip fills and weights, the
"going out" tag's colour, the "No source for X" caption's amber, a
one-space against two-space gap after a ruling date, placeholder text
present on one platform and not the other, the curve's gradient and its
5dp against 6px bar gap, thumbnail radius, the two-line card name, the
pager's left alignment, the Reset button's weight, and the line-count
pluralisation in the edit dialog.

## What the work said about the tests

The audit's own lesson was that four separate bugs survived because a
test mounted a component alone. Everything below that line was written
before the fixes. Doing them added four more lessons, all of which
cost real time:

- **A killed Gradle task leaves the previous run's XML on disk.** The
  wrapper then reports yesterday's green. "356 tests, 0 failures" was
  read out of a run that never happened, and three separate agents
  were caught by it. Never read a count without confirming the build
  succeeded.
- **A hung test leaves no XML at all**, so the XML can never name it.
  `build/test-order.log` writes a START and an END per test; a START
  with no END is the culprit. That found a twenty-minute hang in one
  run after two blind ones.
- **`ComposeRootRegistry` never shrinks on its own** and every
  `isIdleNow` copies the whole set, so a long suite grinds to a halt
  around test 200. `forkEvery(4)` is the fix and must stay.
- **A `needsRealRendering` test has never run.** It skips on the JVM,
  so it is unproven code, not coverage. Two landed green and were
  simply wrong — one called `onRoot()` with a dialog open, where
  there are two roots; one sized its fixture to Robolectric's 470dp
  screen, which a real 808dp phone does not overflow. Run
  `npm run test:android` before believing a gated test.

And one that generalises the original lesson rather than repeating it:
the four share-menu tests in `DecksParityTest` all pass against the
inline menu, because every one of them asks what the menu *says* and
none asked where it was. A test can mount the right thing and still
be pointed at the wrong question.

## What the audit says about the tests

Four separate times a test was blind to a real bug because of how it
mounted the thing it tested: the share menu inside an absolutely
positioned frame, Library rows below a lazy grid's fold, the tweak
sheet in a bare `Box`, and the filter panel fed a hand-built `Facets`
that the real app never produces. Every fix below needs at least one
test that goes through the real shell, not the composable alone.
