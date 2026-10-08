---
status: hold
size: small
platforms: web, android
merge: ask
---

# The EDHREC sort runs backwards

"The edhrec sorting looks backwards."

Every other column reads the arrow the same way, down is the good end:
the dearest card, the newest printing, the biggest creature. A rank is
not like that. Rank 1 is the card everybody plays and rank 22,000 is one
nobody sleeves, so sorting it like a price puts the cards nobody plays at
the top.

## Plan

Checked against the tree, not the commit log. #35's message says this
was fixed with `Sort.ascendingIsBetter`, and nothing by that name exists.
The bug is live:

- `Sort.EDHREC` in `apps/core/src/commonMain/kotlin/org/mattshoe/mtg/core/CardFilters.kt`
  sorts on `MIN(c.edhrec_rank)`.
- `buildQuery` in the same file turns `descending = true` into `DESC`
  for every column.
- `Library.sortBy` in `Library.kt` sets `descending = true` whenever a
  new column is picked, so choosing EDHREC puts rank 22,000 first.
- `Sort.directionLabel` says "Least played first" for `descending`,
  which is the backwards behaviour described accurately.

The fix is all in `CardFilters.kt`, so both shells get it with no shell
edit:

1. Give `Sort` a property saying a low value is the good end
   (true only for `EDHREC`).
2. In `buildQuery`, flip `ASC`/`DESC` for that column, so `descending`
   keeps meaning "best first" everywhere and nothing above `buildQuery`
   has to know. Keep the `(key) IS NULL` term first so unranked cards
   still go last in both directions.
3. `directionLabel` for `EDHREC`: `descending` reads "Most played
   first", ascending reads "Least played first".

Side effect worth a line in the PR: a saved link with `sort=edhrec` and
a direction in it (`FilterUrl.kt`) will now sort the other way. That is
the fix, not a regression.

## Tests

`apps/core/src/commonTest/`, run with the CI `shared` task set (`:core:jvmTest :core:jsNodeTest`), not just `npm run test:core`.

- New test: EDHREC with `descending = true` produces `MIN(c.edhrec_rank)`
  ordered `ASC`, and with `descending = false` produces `DESC`. Must go red
  on the current `buildQuery`. Also assert a price sort is unchanged, so
  the flip cannot leak to other columns.
- `CardFiltersExhaustiveTest.kt` lines 2338-2339 currently **pin the
  backwards labels**. Change them to the new meaning, and say in the
  commit that they were pinning the bug.
- Resolved fact, real SQL: add a step to the Android journey suite
  (`apps/androidApp/src/androidTest/.../e2e/JourneyTest.kt`, against
  `FakeWorker` and `test/fixtures/seed.sql`) that picks the EDHREC sort
  and asserts the first tile is the lowest-ranked card in the seed. Run
  it against the unfixed code first. Needs `npm run test:android`.

## Done when

On the live site and in the shipped APK, picking "EDHREC rank" in the
Library with the arrow pointing down shows the most-played cards first
(rank 1 end), and the arrow's label says "Most played first".
