---
status: hold
size: large
platforms: web, android
merge: ask
---

# The card page shows everything, links to EDHREC, the carousel shows the rank, and double-faced cards flip

Absorbs `requests/flip-double-faced-cards.md` (deleted). Both requests
redraw the card image on `CardPage.kt` / `CardSheet.kt` and on both
`CardCarousel.kt` files, so building them apart is two agents editing the
same four files.

From `card-page-shows-everything.md`:

"The cards details does not contain the edhrec rank. It looks like the
card details is missing a BUNCH of stuff from the database. The full card
details needs to show EVERYTHING"

"I want the carousel to show edhrec rank on it"

"And i want the full details page to include additionally a link to
edhrec for that card"

Everything means everything that is in the database for that card, the
child tables included, not a chosen nine columns.

From `flip-double-faced-cards.md`:

"No double faced cards should have a toggle layover on the image to flip
it"

## Plan

**None of this exists yet.** #35's message describes all of it
(`CardFacts`, `Edhrec`, `FlipTest`) and its diff contains none of it.
Checked in the tree: no `Edhrec.kt`, no `CardFacts.kt`, no flip code, and
the only `edhrec` in production code is the filter and the sort. The
`mtg` skill's map lists `Edhrec.kt` and `CardFacts.kt` as already there,
which is wrong for the same reason.

"No double faced cards" is read as "Now/All double faced cards". There
is no flip toggle in the tree to take away, so the literal reading has
nothing to act on.

Five parts. Build them in this order, one commit each, so a PR that
stops halfway is visibly half. That is exactly how #35 failed.

### 1. Everything on the card page (`:core` first)

Today `AppState`/`Load.card` in `apps/core/.../core/App.kt` runs five
queries from `CardQueries` in `CardDetail.kt`: `face`, `printings`,
`usedIn`, `legalities`, `rulings`. `printings` selects 12 of the ~50
columns of `cards` and none of the child tables.

- New `CardFacts.kt` in `apps/core/src/commonMain/.../core/`: a query
  for the printing the page shows (the same one the image uses,
  `printings.first()`) selecting every column of `cards`, plus one
  `group_concat` per child table: `card_colors` (split by `kind`, whose
  values are `color`/`identity`/`produced`), `card_types`,
  `card_keywords`, `card_finishes`, `card_games`, `card_promo_types`,
  `card_frame_effects`, and `card_tags` joined to `tags` for the label.
  Use correlated subqueries or pre-grouped joins so the child tables
  cannot multiply each other's rows.
- A decoder, and a `rows: List<Fact>` (label, value) that is the single
  definition of "everything" both shells render. Group it: the card
  (oracle-level: types, colours, identity, produced mana, keywords,
  layout, reserved, game changer, EDHREC rank, oracle id, tags) and this
  printing (set and set type, collector number, rarity, released,
  artist, frame, border, watermark, security stamp, finishes, games,
  promo types, frame effects, the flags `full_art` `textless` `promo`
  `reprint` `variation` `oversized` `story_spotlight` `booster`,
  scryfall id).
- Columns that are already on the page (`qty`, `owner`, `finish`,
  `name`, the face text in `card_faces`) or are pure keys (`id`,
  `name_norm`, `foil_flag`, `face1`/`face2`) go on an explicit
  not-shown list in `CardFacts.kt`, each with a one-line reason. That
  list plus `rows` must cover every column of `cards`.
- Add it to `Load.card` and to `CardDetail` as a new field. The
  decode is duplicated today in `apps/webApp/.../web/Main.kt`
  (`loadCard`) and `apps/androidApp/.../android/MainActivity.kt`
  (around line 841). Move that decode into `:core` while adding the
  sixth query, so the two cannot drift.
- Render `rows` in `CardPage.kt` (web) and `CardSheet.kt` (Android) as
  a "Details" section after the face panels. No logic in either shell.

### 2. EDHREC rank and link on the card page

- New `Edhrec.kt` in `:core`: `rankText(rank: Long?)` (for example
  `EDHREC #1,234`, null for unranked) and `url(name)` for
  `https://edhrec.com/cards/<slug>`, built from the front face.
- Get the slug rules from real edhrec.com pages before pinning them in
  a test: one card with an apostrophe, one with an accent, one
  double-faced card, one split card. Do not copy the rules out of #35's
  message, which was never checked against anything.
- Both shells: the rank in the Details rows (from part 1) and a named
  "EDHREC ↗" link near the top of the page, opening in a new tab on web
  and via an intent on Android.

### 3. EDHREC rank on the carousel

- `PeekCard` in `Decks.kt` gets `edhrecRank: Long?`.
- `AppState.peekRun` in `App.kt`: Library rows already carry
  `CardRow.edhrecRank`. Deck rows do not: add `edhrec_rank` to both
  sub-selects and the `COALESCE` list in `DeckQueries.cards` in
  `Decks.kt`, and a field on `DeckCard`.
- Add it as a `PeekTag` worded by `Edhrec.rankText`, so both
  `CardCarousel.kt` files render it with no change beyond what they
  already do with `tags`. Unranked says nothing.

### 4. Flip toggle on double-faced cards

- `:core`: one list of two-image layouts (`transform`, `modal_dfc`,
  `reversible_card`, `double_faced_token`) shared by the toggle and by
  `IS_SHAPES["dfc"]` in `QueryBox.kt`, so `is:dfc` and the toggle cannot
  disagree. Split, adventure and Kamigawa `flip` cards have one image
  and get no toggle.
- `CardQueries.art` gets a back-face option: Scryfall serves the back at
  the same path with `/front/` changed to `/back/`.
- Which side is showing lives in `AppState`, keyed by `nameNorm`, so it
  survives a rotation (`MtgViewModel`) and both shells read the same
  thing.
- Layout has to reach each image: `CardRow.layout` exists, `DeckCard`
  and `PeekCard` need it (add `layout` to `DeckQueries.cards`), and the
  card page takes it from part 1's printing.
- A toggle drawn over the image on every single-card image of a
  double-faced card: `CardPage.kt` / `CardSheet.kt`, both
  `CardCarousel.kt`, and the Library tiles in `LibraryPage.kt` /
  `LibraryScreen.kt`. On a tile, tapping the toggle flips the picture and
  must not open the carousel. Deck banners are cropped art, not the
  card, and are left alone.
- Matt is colourblind: the toggle is separated from the art by
  lightness (a solid backed button with an icon), measured, not by hue.

### 5. Floors

Raise `test/suite-floors.json` for every suite that grew, counted from
results, in the commit that grew it.

## Tests

Each red before its part's production code, failure text in the commit.

- `apps/core/src/commonTest/`: `CardFactsTest` (query selects every
  `cards` column and each child table, decoder, `rows`), `EdhrecTest`
  (rank text, slugs from the real pages you checked), `PeekRunTest` or
  an addition to `DeckPeekTest`/`LibraryPeekTest` (rank tag from both
  runs, deck query carries `edhrec_rank` and `layout`), `FlipTest`
  (layouts, back URL, state toggles and survives an unrelated state
  change).
- **Coverage test that bites**: reads `schema.sql`'s `CREATE TABLE cards`
  column list and fails, naming the column, for any column that is
  neither in `rows` nor on the not-shown list. Prove it by deleting one
  row and watching it name that column. JVM is fine for the file read.
- Web, real browser, `apps/webApp/src/jsTest/`: extend
  `CardPageLayoutTest` (Details section present, artist and keywords
  rows read back from the DOM, EDHREC link `href`), the carousel tag,
  and the flip (image `src` contains `/back/` after a click, and a
  single-faced card has no toggle). Fixtures must be shaped like the
  real query's output, not hand-built rows the query cannot return.
- Android, `apps/androidApp/src/sharedTest/`, driven through `AppShell`:
  extend `CardSheetParityTest`, `DeckCardCarouselParityTest` and
  `LibraryParityTest` with the same facts as the web tests. Prefer JVM
  over `needsRealRendering()`.
- One journey in `apps/androidApp/src/androidTest/.../e2e/JourneyTest.kt`
  against `FakeWorker`: open a double-faced card from the seed, read a
  Details row that only the new query returns, see the rank on the
  carousel, flip it. This is the only check that the new SQL actually
  runs against the real schema.

## Done when

On the live site and in the shipped APK:

- Opening any card shows a Details section listing its artist, layout,
  frame, border, keywords, finishes, games and printing flags, and the
  coverage test proves no `cards` column is silently missing.
- The card page shows the EDHREC rank and an "EDHREC ↗" link that opens
  that card's real edhrec.com page, including for a double-faced card.
- The carousel sheet, over a deck and over the Library, shows the EDHREC
  rank for a ranked card.
- A double-faced card (for example a transform card in the seed) shows a
  flip toggle on its image on the card page, in the carousel and on the
  Library tile, and tapping it shows the back. A single-faced card shows
  none.

Risky part: breadth, and the history. This is five asks on three
surfaces on two platforms, and the last attempt was reported as shipped
with none of it in the diff. Check `git diff --cached --stat` against
this list before every commit message, and the deployed `mtg.js` and APK
dex for a new string from each part before calling any of it done. The
SQL risk is the child-table joins fanning out rows; part 1's tests must
include a card with several keywords and several finishes.
