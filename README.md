# mtg-api

Matt's Magic: The Gathering collection behind a REST API, on Cloudflare Workers
and D1.

```
https://mtg-api.mattshoe81.workers.dev
```

Plus a web frontend at **https://mattshoe.github.io/mtg-api/** — search, decks,
add and remove cards, stats, and a SQL console.

Reading is open. **Anything that writes needs admin mode** — see below.

Before this, the collection was five SQLite files in a Google Drive folder, and
every write went through a file queue — a write round trip averaged 90 seconds
and only worked while the Mac was awake. The shards existed solely because the
Drive connector capped mobile downloads at 10 MB. Here it is one database, and
an add takes about a second.

---

## Endpoints

### `GET /schema`

Every table and view with its columns, types and row counts, plus the join keys
an agent needs. Roughly 2k tokens, so it is affordable to fetch before asking a
real question.

```bash
curl https://mtg-api.mattshoe81.workers.dev/schema
```

### `GET /query` and `POST /query`

Arbitrary SQL, one statement per call. **POST** reads or writes. **GET** takes
the same thing as query parameters and is read-only, for callers that can only
fetch a URL — Claude desktop and mobile among them:

```
https://mtg-api.mattshoe81.workers.dev/query?fmt=tsv&sql=SELECT name, free FROM bulk_cards WHERE owner='matt' ORDER BY free DESC LIMIT 20
```

A GET that could delete rows is one link preview or prefetch away from doing
it, so anything that writes is refused there with a 405. POST is unrestricted.

```bash
curl -X POST https://mtg-api.mattshoe81.workers.dev/query \
  -H 'content-type: application/json' \
  -d '{"sql":"SELECT name, free FROM bulk_cards WHERE owner=? ORDER BY free DESC LIMIT 5","params":["matt"]}'
```

```json
{"cols":["name","free"],"rows":[["Forest",1053],["Island",1046]],"n":2}
```

| field | meaning |
|---|---|
| `sql` | one statement, required |
| `params` | values for `?` placeholders |
| `fmt` | `rows` (default), `objects`, or `tsv` |
| `limit` | row cap, default 5000 |

GET takes the same names in the query string; `params` goes in as a JSON array,
e.g. `?params=["matt"]`.

`rows` names the columns once and sends each row as an array — on the full
`cards` table that is 2.7 MB against 5.1 MB for `objects`. `tsv` is smaller
still and returns `text/tab-separated-values`.

A bare `SELECT` with no `LIMIT` of its own is capped, and the response says
`"truncated": <limit>` when it hit the cap. Errors come back as
`{"error":"..."}` with a 400 and the real SQLite message.

### `POST /admin`

Password in, token out. The token is a signed expiry (`<unix>.<hmac>`), good
for 12 hours, not stored anywhere on the server.

```bash
curl -X POST https://mtg-api.mattshoe81.workers.dev/admin \
  -H 'content-type: application/json' -d '{"password":"..."}'
```

```json
{"ok":true,"token":"1790487821.vazqY21…","expires_at":1790487821}
```

Send it back as `Authorization: Bearer <token>` on anything that writes. Without
it those endpoints answer `401 {"error":"admin mode required","admin_required":true}`.

**What needs it:** `/cards/add` and `/cards/remove` (dry runs included), and any
`POST /query` whose statement is not read-only. **What does not:** `/schema`,
`GET /query`, and a read-only `POST /query`.

The password is a Worker secret (`wrangler secret put ADMIN_PASSWORD`), never in
the repo. Rotating it invalidates every outstanding token, because the password
is the HMAC key.

This stops a stray curl, a bookmarked tab left open, and an agent that wandered
off its instructions. It is not protection from someone who has the password.

### `POST /cards/add` and `POST /cards/remove`

The only endpoints that need more than SQL, because adding a card means asking
Scryfall what the card is. Both need an admin token.

```bash
curl -X POST https://mtg-api.mattshoe81.workers.dev/cards/add \
  -H 'content-type: application/json' \
  -d '{"owner":"matt","list":"4 Lightning Bolt (2X2) 117\n1 Sol Ring *F*","dry_run":true}'
```

```json
{"applied":false,"dry_run":true,"resolved":2,"failed":0,
 "changes":[["Lightning Bolt","2X2","117","nonfoil",0,4]],
 "errors":[]}
```

Each `changes` row is `[name, set, collector_number, finish, qty_before, qty_after]`.
`owner` defaults to `matt`. `dry_run` plans without writing anything.

Accepted list lines — the same shapes the old tooling took:

```
Sol Ring
4 Sol Ring
4x Sol Ring
1 Sol Ring (M3C) 409
1 Sol Ring (M3C) 409 *F*
1 Sol Ring (M3C) 409 foil
# comments and blank lines are skipped
```

Set plus collector number pins an exact printing and wins over the name. A bad
line does not sink the request: the good ones apply and the rest come back in
`errors`. The whole mutation is one D1 batch, so a Scryfall failure leaves the
database untouched.

---

## The data

18 tables. One row in `cards` per (owner, printing, finish), with `qty` as how
many of that stack are owned.

**Join keys.** `cards.name_norm` is `lower(trim(name))` and matches
`deck_cards.name_norm` and `totals.name_norm`. `cards.id` is what every
`card_*` child table's `card_id` points at, and what `card_search.rowid` is.
`cards.oracle_id` joins `legalities` and `rulings`.

**Owners.** `matt` and `kayla`. The two collections are never merged — filter
on `owner`.

**Views.**

| view | what it is |
|---|---|
| `totals` | one row per unique card per owner, quantities summed across printings |
| `card_usage` | `owned`, `in_decks`, `free` per card |
| `bulk_cards` | `card_usage` where `free > 0` — the unassigned pool |
| `deck_gaps` | deck slots not backed by a card in the collection |
| `deck_conflicts` | cards slotted into more decks than there are copies |
| `decks_not_built` | proxy or `PROPOSED` decks |

`totals` used to be a maintained table and is now a view, so it cannot drift.
Only decks that physically exist consume cards: not proxies, not `PROPOSED`.

**Full text.** `card_search` is FTS5 over name, type line, oracle text, flavor
text, keywords and tags, porter-stemmed.

```sql
SELECT c.name FROM card_search s JOIN cards c ON c.id = s.rowid
 WHERE card_search MATCH 'proliferate' LIMIT 10
```

**Two things to know.** `legalities` stores only statuses other than
`not_legal`; an absent row means not legal. And tags come from Scryfall's
Tagger bulk file, which has no per-card endpoint, so a card added through the
API has no tags until the nightly backfill.

---

## Frontend

`frontend/` is a static site on GitHub Pages, deployed by
`.github/workflows/pages.yml` on every push that touches it. Same stack as the
Worker: plain ES modules, no build step, no framework, no dependencies at all.
Open `frontend/index.html` through any static server and it talks to the live
API.

| view | what it does |
|---|---|
| Search | every column in the database, as facets or as a query language — see below |
| Card | full detail in a drawer — every printing owned, decks it is in, tags, legalities, rulings, and ±1 buttons |
| Decks | all 32 decks, each with its list, curve, notes and gaps; plus a gaps-and-conflicts overview |
| Add / Remove | paste a list, preview the real dry run, then apply |
| Stats | curve, colours, types, rarity, biggest sets, most unassigned copies |
| Console | arbitrary SQL with a schema browser, snippets, history and CSV export |

Admin mode is the padlock in the top bar, or `l`. It lives in a JavaScript
variable and nowhere else — not `localStorage`, not `sessionStorage`, not the
URL — so closing or reloading the tab ends it. While it is off, Add and Remove
show a lock screen, the drawer's ±1 buttons are replaced by an unlock button,
and the console runs reads but prompts before a write.

**Colour matching** has four explicit modes, on either colour identity or the
printed colour: **Exactly**, **At most** (nothing outside these — the one that
answers "what can I put in this commander"), **At least**, and **Any of**.
Colourless is handled per mode rather than pretended to be a sixth colour.

The filter panel is full width and collapsed by default. Open it and you get
eleven group headers; each one opens on its own, so you only unfold what you
are actually using. 76 checkboxes, ranges with operators, segmented toggles,
colour pips. Nothing scrolls inside anything else — the page has one
scrollbar. Open/closed state is remembered, and a link or saved search that
arrives with filters set opens the groups responsible. Active filters show as
removable chips above the panel, and each group header carries a count.

**The query box** is optional, sits at the bottom of the panel, and takes
Scryfall-style syntax ANDed with whatever the controls have set. The cheatsheet
button lists every key.

```
id<=wub t:creature mv<=3        fits an Esper commander, cheap creatures
tag:mana-rock is:free -t:land   spare rocks not committed to a deck
pow>=6 -is:reprint r:mythic     big first-printing mythics
o:"draw a card" mv<2 is:free    cheap unassigned draw
edhrec<=250 -is:indeck          staples sitting in the bulk box
a:"seb mckinnon" is:foil        by artist and finish
```

Keys: `name o t c id mv pow tou loy qty free edhrec year r s st layout cn
a wm ft m kw tag f banned restricted deck owner game produces is not`, with
`: = >= <= > <` where they make sense and `-` to negate anything. 43 `is:`
values cover layout, type buckets, finish, flags and collection state.

Everything else lives in the panel: supertypes, subtypes, exclude-a-type, mana
cost, power/toughness/loyalty with operators, produced mana, number of
colours, set type, frame, border, release year, collector number, availability
(paper/Arena/MTGO), flavour text, artist, watermark, ten printing flags as
any/yes/no, format legality, has-rulings, copies owned, free copies, EDHREC
rank, and which deck a card is in (or no deck at all).

Keyboard: `s` `d` `a` `r` `g` `c` jump between views, `/` or `⌘K` finds a card,
`l` locks or unlocks, `t` toggles the theme, `esc` closes. Searches are shareable — the filters live
in the URL — and can be saved by name.

## Development

```bash
npm install
npm test          # 179 tests against a real local D1 in workerd
npm run dev       # local server
npm run deploy
```

Plain JavaScript, no TypeScript, no framework, no runtime dependencies. The
tests run the real Worker against real SQLite rather than mocks, with Scryfall
stubbed from recorded responses in `test/fixtures/scryfall/`.

```
src/index.js      router
src/query.js      /query
src/schema.js     /schema
src/cards.js      /cards/add, /cards/remove
src/card.js       Scryfall card -> database rows
src/parse.js      decklist parsing
src/scryfall.js   Scryfall client, rate-limited
schema.sql        the whole schema
```

`CLOUDFLARE_ACCOUNT_ID`, `CLOUDFLARE_API_TOKEN` and `MTG_ADMIN_PASSWORD` come
from `~/.mtg-api.env`. The Worker's own copy of the password is a secret:

```bash
printf 'newpassword' | npx wrangler secret put ADMIN_PASSWORD
```

## Scripts

| script | what it does |
|---|---|
| `scripts/backup.py` | dump the live database to a local `.sql.gz`, and prove it restores |
| `scripts/nightly.sh` | backup + tag backfill, run by launchd at 03:00 |
| `scripts/backfill.py` | tags from the local Scryfall index (writes, so it unlocks first) |
| `scripts/verify.py` | compare the API against the old shards, table by table |
| `scripts/seed.py` | one-time: the five old shards -> `data.sql` |
| `scripts/make_fixture.py` | regenerate the test fixture |

## Backups

`scripts/nightly.sh` runs at 03:00 via `com.matt.mtg.apibackup.plist`: dump the
database, restore the dump into a scratch SQLite file to prove it works, check
the row counts against the live API, keep 30 days in `~/mtg-backups/`, then
backfill tags. About 18 seconds end to end, 2.1 MB gzipped.

Note what it does *not* use. `wrangler d1 export` is the obvious tool and the
wrong one: it takes the database offline for the duration, and here that ran
past ten minutes. `backup.py` pages every table out through `/query` instead,
so nothing is ever locked and the API stays up.

```bash
gunzip -c ~/mtg-backups/mtg-20260926.sql.gz | sqlite3 restored.db
```

D1 also has Time Travel — 30 days of point-in-time restore, granular to the
second:

```bash
npx wrangler d1 time-travel info mtg
npx wrangler d1 time-travel restore mtg --timestamp=2026-09-26T12:00:00Z
```

That matters here, because with no auth and arbitrary SQL one bad statement
reaches the real database. Worst case is a restore.
