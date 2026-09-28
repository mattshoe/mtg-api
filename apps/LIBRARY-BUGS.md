# The Library page: the catalogue, and what came of it

**All of it is fixed.** Kept as written so the reasoning survives —
each entry is why a test exists now.

Verified by: 528 distinct tests (1,488 executions across four
targets), all green; `scripts/filter_sweep.py` running 94 filter
shapes against the real collection; and live queries for the two data
bugs.

What the fixes were, in commit order:

- `932e669` the four broken ones
- `5fc9493` numbers that are true, searches that do not crash
- `00672ac` the rest of the catalogue

Each entry is marked with how much it is actually known:

- **CONFIRMED** — a failing test or a live query proves it. Cited.
- **REPORTED** — found by reading the code. Credible, not yet reproduced.
- **DISPROVED** — reported, then tested, and it does not happen.

The harness that produced most of the CONFIRMED entries:
`LibraryDriverTest` (drives the whole shell), `LibraryLayoutTest`
(measures boxes against the real stylesheet), `LibraryProbeTest`
(one test per doubtful claim), `scripts/filter_sweep.py` (every
filter against the real collection).

---

## Broken — the page does the wrong thing

### B1. You cannot type in the card name box  · CONFIRMED

`LibraryPage.kt:65-73`, `AppShell.kt:94-108`.
Test: `LibraryDriverTest.theNameBoxAcceptsTypedCharacters` — the input's
value is `""` after typing `bolt`.

One `input` event fires two writes against the same captured
`AppState`:

```kotlin
onState = { c ->
    onComplete(c)                                        // writes complete
    apply(state.where(state.filters.copy(q = c.term)))   // writes library
},
```

No recomposition runs between them, so the second `copy()` is built on
the pre-keystroke state and carries the old `complete`. The input is
controlled — its `value` is re-applied from `complete.term` on every
recomposition — so the character the browser just put in the box is
wiped. Meanwhile `filters.q` *does* get the letter, so the grid
re-filters and the URL grows `?q=b` for a letter that is not on screen.

Because `term` never reaches two characters, `Completion.worthAsking`
is never true: **autocomplete never fires at all**, and the whole
arrow-key/Enter path in `Autocomplete.kt` is dead at runtime.

### B2. "Colour identity" in the sort dropdown is a hard SQL error  · CONFIRMED

`CardFilters.kt:73`. Live:

```
ORDER BY (c.color_identity_count, c.color_identity) IS NULL, … DESC
→ {"error":"row value misused: SQLITE_ERROR"}
```

The enum's `column` holds two comma-separated expressions;
`buildQuery` wraps it in parentheses, which SQLite parses as a row
value and refuses. The option is in the dropdown because the dropdown
renders `Sort.entries` wholesale. Picking it empties the grid and
shows the error banner, and the only way out is to pick another sort.
The same string is in `frontend/js/filters.js:99`, so the old site had
it too.

### B3. "Copies owned" filters one printing, not the stack you are looking at  · CONFIRMED

`CardFilters.kt:298-299`. Live, for matt:

```
qtyMin = 4, as built   → 231 cards
qtyMin = 4, summed     → 277 cards
```

46 cards are hidden from their own filter. Dismal Backwater: 8 copies
across 3 printings, 3 in the biggest — the grid shows `qty 8` and
`qtyMin=4` does not return it. It is a `WHERE c.qty >= ?` against a
query that groups and displays `SUM(c.qty)`. It needs `HAVING`, in
both the page query and the count query or the total disagrees with
the rows.

### B4. The sort row is misaligned  · CONFIRMED

`app.css:204`, `LibraryPage.kt:124-127`.
Test: `LibraryLayoutTest.theControlRowLinesUp` —

```
button "Filters"  centre=192.375  height=30.75
button "Export"   centre=192.375  height=30.75
select .sort      centre=185.875  height=30.75   ← 6.5px high
button "↓"        centre=192.375  height=30.75
```

The select carries `.field`, which has `margin-bottom: 13px`.
`.field:last-child` would zero it, but the arrow button follows the
select so it is not the last child. In a flex row with
`align-items: center` the *margin box* is centred, so the select rides
6.5px up and the row is 13px taller than anything visible in it.

On iOS the same row is worse: WebKit ignores an author `line-height`
on `<select>`, so the select is ~27px against the button's ~30.75px —
a height mismatch *and* an offset at once. That is the "weirdly small"
in the report. **REPORTED** (not measurable in headless Chrome).

---

## Wrong — the page shows something untrue

### W1. `price` and `value` in the same row are computed from different rows  · REPORTED

`CardFilters.kt:423-424`. `price` is a bare non-aggregate expression,
so SQLite hands it one arbitrary printing; `value` is
`SUM(qty * price)` over all of them. A Sol Ring owned in three
printings renders as `qty 3 · price $2.00 · value $45.00`. Three
copies at two dollars is forty-five dollars.

And **Price is the default sort**, so it orders the whole collection
by whichever printing SQLite picked.

### W2. `free` is whole-collection while `qty` is filtered  · REPORTED

`CardFilters.kt:90` + `:423`. `u.free` comes from `card_usage`, which
sums over every printing regardless of the WHERE. Filter `finish=foil`
on a card you own as 1 foil + 3 nonfoil and the tile reads
`1× matt · 4 free`.

### W3. Any per-printing filter silently re-scopes the numbers on the tile  · REPORTED

`CardFilters.kt:285, 325-343, 364-365`. Filtering by price, rarity,
set, layout, frame, border, finish, year or any flag changes which
printings enter the group, so `qty`, `printings` and `value` all
change to describe the surviving subset. `priceMin=30` on a card with
one expensive printing shows `qty 1, printings 1, free 3`.

### W4. `value` is a partial sum when any printing is unpriced  · REPORTED

`CardFilters.kt:424`. `qty * NULL` is NULL and `SUM` skips NULLs, so
4 copies with 2 priced at $5 renders `qty 4 · value $10`. If every
printing is unpriced the value is NULL and looks identical to a
genuinely priceless card.

### W5. Released / Set / Artist / Rarity all sort on an arbitrary printing  · REPORTED

`CardFilters.kt:68-70, 74`. Bare ungrouped columns get the `MIN(c.id)`
row — the first printing ever imported. A card you own from 1993 to
2026 sorts as 1993. Sol Ring owned in LEA/C21/M3C sorts as *uncommon*
and lands next to the bulk commons. `instr(...)` for rarity is an
expression over a bare column, so it is not even covered by SQLite's
min/max guarantee — its row is unspecified.

### W6. Three multi-selects AND where six OR  · REPORTED

`CardFilters.kt:303, 309-317, 332`. `rarities`, `sets`, `setTypes`,
`layouts`, `frames`, `borders` are `IN` lists — any of these. `types`,
`supertypes`, `subtypes` and `games` are one `EXISTS` each, ANDed —
all of these. Tick Elf and Goblin in the subtype picker and you get
nothing. Tick paper and arena and every paper-only card vanishes. The
same-looking control means two different things.

### W7. Raw FTS5 syntax reaches `MATCH` from the oracle-text box  · REPORTED

`CardFilters.kt:276-277`. Typing `Landfall:` gives
`fts5: no such column: Landfall` — a 500, surfaced as the error
banner. Unbalanced quotes, a leading `-`, a bare `*` and `NEAR` do the
same. The "Exact text" box beside it accepts anything.

### W8. A cancelled search writes "Search failed: Job was cancelled"  · REPORTED

`Main.kt:294-306`. `CancellationException` is an `Exception`, the
catch is `Exception`, and the assignment afterwards is not a
cancellation point. Change a filter while a query is in flight and the
grid is replaced by an error banner for ~250ms.

Same shape in `Scryfall.complete` (`Scryfall.kt:50-54`) — a cancelled
lookup returns `emptyList()` and the stale job closes the live
suggestion list.

### W9. `busy` is never set, so there is no loading state and a false "Nothing matches that"  · REPORTED

`Library.loading()` is called nowhere in the web app. `Main.search`
goes straight from the old state to `.loaded(...)`. Two effects: the
"Searching…" branch is unreachable and stale rows sit under a stale
count for the debounce plus two sequential round trips; and
`isEmpty` is true on first mount, so the page flashes **"Nothing
matches that."** before the first results arrive.

### W10. A shared link cannot open past page 1  · REPORTED

`Main.kt:98, 221` → `Library.where()` → `page = 1`. `FilterUrl` writes
and parses `page` correctly and the caller throws it away. Send
someone `#/search?q=goblin&page=4` and they land on page 1 with
`page=4` in their address bar.

### W11. Leaving the Library and coming back strips the filters out of the URL  · REPORTED

`AppState.navigate` builds a `Route` with `query = ""`, so the tab
click writes `#/search`. The in-memory filters survive, so the grid
still looks filtered — the URL just lies until the next filter edit.

### W12. `adv` is invisible and unclearable  · CONFIRMED (unreachable from the UI)

Nothing in `webApp/jsMain` writes `filters.adv` — grep finds nothing.
It still round-trips through the URL and still ANDs real clauses onto
the search, and no facet counts it, so no group badge shows it and no
accordion opens for it. Follow a link with `adv=-is:reprint` and every
reprint is missing with nothing on screen to say why and no way to
turn it off.

### W13. The pager is unstyled  · REPORTED

`app.css:485-486` defines `.pager` and `.pager .info`;
`LibraryPage.kt:181, 187` emit `.flex-wrap` and `.muted.small`. So no
centring (left-aligned under a full-width grid), no `18px` padding
(flush against the last row of cards), and no tabular numerals, so the
Next button jogs sideways as the page number widens.

### W14. Zero gap in several places  · CONFIRMED (one of them)

Test: `LibraryProbeTest.theColourPipsAndTheirShortcutsAreNotTouching`
— **0px** between the colour pips and the clear/all-five/colourless
chips. `.frow` is emitted by the panel and has no rule of its own,
only `.frow > label`.

Same shape, **REPORTED**: `.panel` → `.fgrid` (the filter grid is
flush against the control panel), `.fgrid` → "Reset everything", the
result count → `.grid`, and `.grid` → the pager.

### W15. The last nav item sits off the right edge at 390px  · CONFIRMED

Test: `LibraryLayoutTest.nothingOverflowsThePageAtPhoneWidth` —
`"Find"` has `right=438.67` in a 390px page. The row scrolls
sideways so it is reachable, but there is no affordance saying so.

### W16. The toast has no positioning container  · REPORTED

`AppShell.kt:145` emits `.toast` with no `.toasts` parent, and
`.toasts` is the rule that does `position: fixed`. The toast renders
in flow after the whole page, off-screen below the card grid.

### W17. `label.check` and `.check` are two owners of one component  · REPORTED

`app.css:547` and `app.css:695`, 150 lines apart, both matching
`<label class="check">`. `label.check` wins on specificity, so
`.check`'s `gap: 7px` is dead code and `flex-wrap: wrap` applies to a
block written assuming no wrap.

---

## Cosmetic

- **C1** `.chip` is the only button component that never sets
  `font: inherit`, so the colour-mode chips and token chips render in
  the UA button face beside everything else. `app.css:226`. REPORTED
- **C2** `.pip` never sets `padding: 0`, so the UA's `1px 6px` shrinks
  the 30×30 circle's content box to 15×25. `app.css:239`. REPORTED
- **C3** The autocomplete list appearing makes `.ac` grow 13px
  (`:last-child` stops matching), shoving the sort row down and the
  list itself with it. REPORTED
- **C4** Mana cost renders as literal `{2}{W}{W}` — the `.mana`/`.ms`
  pip system exists in the stylesheet and is never emitted.
  `LibraryPage.kt:171`. REPORTED
- **C5** Card names have no clamp, so one long name adds ~32px of
  whitespace to every tile in its grid row. REPORTED
- **C6** `.card:hover` sticks on touch — the last-tapped card stays
  lifted 3px with a shadow. REPORTED
- **C7** The nav's tabs are 36.25px pills and its Lock/Find buttons are
  30.75px 7px-radius rectangles, in the same row. REPORTED
- **C8** Empty results render **"1–0 of 0"** (`IntRange.EMPTY` is
  `1..0`, and both ends are printed unconditionally). REPORTED
- **C9** A page past the end renders a backwards range —
  `"9801–250 of 250"` over an empty grid. Only reachable by direct
  construction today. REPORTED
- **C10** `Prices.money(-5.0)` → `"$-5.00"`, and negatives never reach
  the whole-pounds branch so they format differently from positives.
  REPORTED
- **C11** `twoPlaces(2.675)` → `"$2.67"` — binary float rounds the
  wrong way at the boundary, in a function whose sibling is called
  `exact`. REPORTED
- **C12** `%` and `_` typed into any text box act as LIKE wildcards.
  Bound, so not injectable, just silently useless. REPORTED
- **C13** `Filters.size` is in no URL codec, so `fromHash(toHash(f))`
  is not the identity for a non-default page size. REPORTED
- **C14** `dir=anything-but-desc` means ascending; an unknown `sort=`
  falls back to NAME while an absent one falls back to PRICE. REPORTED
- **C15** Two columns are fetched per row and never decoded —
  `pr.tcg_url` and `pr.updated_at AS priced_at`. REPORTED
- **C16** Number fields clear themselves on intermediate input: typing
  `-` or `1.` yields a null `value`, which writes `""` and the
  controlled input erases the partial entry. REPORTED
- **C17** `Library.sortBy` (flips on a repeat pick) is used by Android
  and `sortedBy` (never flips) by the web. Two sort semantics. REPORTED
- **C18** Half-typed text in a `Tokens` field is discarded when its
  group folds. REPORTED
- **C19** Flipping the sort direction resets to page 1. REPORTED
- **C20** `pages` divides by `size` with no guard; `size = 0` throws
  `ArithmeticException` while `buildQuery` would have used 100.
  Unreachable today. REPORTED

---

## Disproved — reported, tested, does not happen

- **D1** "A dropdown keeps showing the old pick after Reset, because
  `Option(selected)` is a content attribute and the browser's dirty
  flag ignores it." Compose HTML rebuilds the option list.
  `LibraryProbeTest.aDropdownFollowsTheStateWhenSomethingElseChangesIt`
  passes.
- **D2** "The min and max boxes of a range row differ by 13px because
  `.field:last-child` strips a margin from one of them." Measured in
  Chrome they are the same height.
  `LibraryProbeTest.aRangeRowsTwoBoxesAreTheSameSize` passes.

---

## Not bugs, checked

- Every column referenced in `CardFilters.kt` exists in `schema.sql`
  with a compatible type. All of them.
- `card_search` rowids line up with `cards.id` — `seed.py` inserts
  them explicitly.
- `pool`, `freeMin` and the `deck` clauses correctly belong in `WHERE`:
  `card_usage` is already aggregated 1:1 with the group.
- `(qty)` and `(free)` in `ORDER BY` do resolve to the SELECT aliases
  despite the parentheses — verified against a case where the two
  answers differ.
- Numeric filters bind numbers, not strings. `FilterCoverageTest`
  pins it; the SQLite affinity trap that makes string binds match
  nothing against a `COALESCE`/`CASE` is real and already guarded.
- `FilterUrl`'s percent codec handles multibyte UTF-8 and a trailing
  `%XX` correctly.
- All 94 filter shapes run against the real collection without error
  (`scripts/filter_sweep.py`).
