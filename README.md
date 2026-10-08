# mtg-api

Matt's Magic: The Gathering collection behind a REST API, on Cloudflare Workers
and D1.

```
https://mtg-api.mattshoe81.workers.dev
```

Plus a web frontend at **https://mtg.mattshoe.org** — search, decks,
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
https://mtg-api.mattshoe81.workers.dev/query?fmt=tsv&sql=SELECT name, free FROM bulk_cards WHERE owner_id=(SELECT id FROM users WHERE key='e7de0cb1') ORDER BY free DESC LIMIT 20
```

A GET that could delete rows is one link preview or prefetch away from doing
it, so anything that writes is refused there with a 405. POST is unrestricted.

```bash
curl -X POST https://mtg-api.mattshoe81.workers.dev/query \
  -H 'content-type: application/json' \
  -d '{"sql":"SELECT name, free FROM bulk_cards WHERE owner_id=(SELECT id FROM users WHERE key=?) ORDER BY free DESC LIMIT 5","params":["e7de0cb1"]}'
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

### `POST /prices`

On-demand prices for a list of scryfall ids, straight from Scryfall. Mostly
superseded by the stored `prices` table — use that unless you need a figure
fresher than this morning.

```bash
curl -X POST https://mtg-api.mattshoe81.workers.dev/prices \
  -H 'content-type: application/json' -d '{"ids":["2d47121d-8b90-4d28-9ffa-0a640b9dd611"]}'
```

```json
{"prices":{"2d47121d-…":{"usd":1.97,"foil":null,"etched":null,"eur":1.3,"tix":0.04,"tcg":"https://…"}},
 "fetched":1,"cached":0,"missing":[]}
```

No admin token — it is a read. Up to 1500 ids per call, batched 75 at a time
into Scryfall at their documented rate limit, and cached in the Cloudflare
Cache API for 12 hours. Around 300ms for a page of cards cold, ~120ms warm;
the whole collection is about 15s cold and a second or two after that.


### `GET /logs` and `GET /logs/stats`

The request log. **Admin only** — unlike every other read here, because it
carries IP addresses and the SQL people ran.

```bash
curl 'https://mtg-api.mattshoe81.workers.dev/logs?min=warn&since=24&q=bolt' \
  -H "authorization: Bearer $TOKEN"
```

Filters: `min` (level and above), `level` (exact, comma-separated), `event`,
`method`, `status` (`error`, `4xx`, `5xx`, or an exact code), `since` (hours
or an ISO timestamp), `slow` (minimum ms), `q` (searches path, message,
detail, event and IP), `limit`, `offset`.

Because ordinary reads are open, the `logs` table is blocked in `/query` too
unless you present a token — gating the endpoint but not the table would
leave the data one `SELECT` away.

### `GET /maintenance` and `POST /maintenance`

What the daily job did, and a way to run it now. GET is open; POST needs
admin and returns `202` immediately, since the work runs in the background.
`{"wait": true}` blocks, `{"only": "orphans"}` narrows, `{"all": true}`
includes the price refresh.

### `POST /admin`

Password in, token out. The token is a signed expiry (`<unix>.<hmac>`), good
until the password is rotated, not stored anywhere on the server.

```bash
curl -X POST https://mtg-api.mattshoe81.workers.dev/admin \
  -H 'content-type: application/json' -d '{"password":"..."}'
```

```json
{"ok":true,"token":"0.vazqY21…","expires_at":null}
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
  -d '{"list":"4 Lightning Bolt (2X2) 117\n1 Sol Ring *F*","dry_run":true}'
```

```json
{"applied":false,"dry_run":true,"resolved":2,"failed":0,
 "changes":[["Lightning Bolt","2X2","117","nonfoil",0,4]],
 "errors":[]}
```

Each `changes` row is `[name, set, collector_number, finish, qty_before, qty_after]`.
The cards go to the signed-in account's own collection. `"collection": "<key>"`
names another one by its public key, which only an account allowed to edit it
(or the admin role) gets past; the operator's password has no collection of its
own and must name one. An `owner` field is refused. `dry_run` plans without
writing anything.

`list` takes whatever an exporter actually produces, text or CSV — the
format is detected rather than declared.

```
Sol Ring
4 Sol Ring
4x Sol Ring
1 Sol Ring (M3C) 409
1 Sol Ring (M3C) 409 *F*
1x Sol Ring (m3c) 409 [Ramp]     Archidekt category, stripped
SB: 2 Negate                     MTGO board prefix, stripped
Deck / Sideboard / Commander     section headers, skipped
# and // are comments
```

CSV from ManaBox, Moxfield or Deckbox works as-is — paste the file whole.
Columns are matched by name rather than position, since every exporter
orders and spells them differently: quantity/count/qty, name/card name,
set code/edition/set, collector number/card number, foil/finish. Quoted
fields with commas in them (`"Alela, Cunning Conqueror"`) parse correctly,
and a `foil` column reads words or true/false.

Set plus collector number pins an exact printing and wins over the name. A bad
line does not sink the request: the good ones apply and the rest come back in
`errors`. The whole mutation is one D1 batch, so a Scryfall failure leaves the
database untouched.

### `POST /decks/disassemble`

```json
{"key": "q8ytka9m", "dry_run": false}
```

Deletes the deck, its list and its notes, and hands every card it was
holding back to bulk. Admin only, dry runs included.

Nothing about a card moves. `cards` already records what is owned and
`deck_cards` is the only thing claiming any of it; bulk is not a place but
`card_usage.free`, owned minus the copies some deck has spoken for. Delete
the deck and those copies come free on their own — which is why the reply
counts `freed` from rows that are actually `in_collection`, and why a deck
made mostly of gaps frees almost nothing.

```json
{"deck":{"key":"…","name":"…"},
 "freed":100,"cards":[{"name":"Bitterblossom","qty":1}],
 "rows":{"deck_cards":78,"deck_notes":1},"applied":true,"dry_run":false}
```

The three deletes are one batch. Half of them would leave `deck_cards`
rows pointing at a deck that no longer exists, and those would count
against `free` forever.

### `POST /cards/validate`

```json
{"list": "1 Sol Ring\n1 Bitterblosom"}     // or {"names": [...]}
```

Do these names exist? The collection is asked first — most of what anyone
types is already owned, that lookup is free, and it keeps a familiar list
validating with no network at all — then Scryfall for the rest, and a
fuzzy lookup for a suggestion on anything still missing.

```json
{"ok": false, "checked": 2, "unknown": 1,
 "cards": [{"name":"Sol Ring","ok":true,"source":"collection","suggestion":null},
           {"name":"Bitterblosom","ok":false,"source":null,"suggestion":"Bitterblossom"}]}
```

Open, since it writes nothing. Scryfall being unreachable is a 502, not a
verdict that the names are bad.

### `POST /decks/create`

```json
{"name": "Fairy Deck", "format": "commander",
 "commander": "Alela, Cunning Conqueror", "bracket": "3", "list": "1 Bitterblossom\n…"}
```

A new deck, list and all, behind the wizard at `#/decks/_new`. Admin only.
`GET /decks/formats` is the dropdown's source and says which formats take
a commander. The deck goes in your own collection (or `collection: <key>`)
and gets a random `key`, which is its address and is in the reply. Any name
is fine, including one another account already uses.

Everything the wizard asks is validated here too, because the browser is
not the only caller. Creating goes through the same path as editing a
list, so a new deck draws its cards out of bulk and acquires whatever
bulk cannot cover — a dry run shows that shopping list before anything is
written, and if any of it cannot be resolved nothing is created at all.

### `POST /decks/list`

```json
{"key": "q8ytka9m", "list": "1 Alela, Cunning Conqueror\n3 Bitterblossom", "dry_run": false}
```

Replaces a deck's list wholesale from a decklist, in the same format
`/cards/add` takes. Admin only. The reply is a diff — `added`, `removed`,
`changed`, plus `rows`, `card_count` and `owned_count`.

`commander` is its own field so the list stays the 99 and nothing else,
with no heading or marker singling one line out. Two names in it are
partners. Send it and it defines the commander rows outright; leave it
off and the deck's existing commander is matched by name. The
`decks.commander` column is only rewritten when the name actually
changes, because it carries hand-written prose the field cannot show and
should not silently delete.

A replace, not a merge: what you send is what the deck becomes. Which is
why one unparseable line refuses the whole request rather than applying
the rest — dropping a line here would silently delete a card from the
deck, and a typo should not do that.

**You choose where each card comes from.** The dry run returns a
`sourcing` array — per card, `need`, `own_free`, `other_free` and which
owner the other collection belongs to. Send `sources` back as
`{name_norm: "bulk" | "transfer" | "buy"}` to decide:

- `bulk` (the default) takes what this collection has spare and buys the
  rest
- `transfer` takes the spare, then **moves** copies out of the other
  collection, then buys whatever is still short
- `buy` leaves bulk alone and gets the whole quantity new

A transfer is a real inventory move: the other owner loses the copies,
printing for printing, and the receiving rows are copied from theirs
rather than re-fetched, so it works offline. It goes in the same batch as
the deck — a deck claiming transferred cards while the transfer failed
would be claiming someone else's.

**Editing a list moves real cards.** A card put into a deck came from
somewhere, so the collection is made to say so:

- copies the deck drops go **back to bulk** — nothing is written for that,
  bulk is `card_usage.free` and the deck simply stops claiming them
- copies the deck gains come **out of bulk** when a spare exists
- when no spare exists the shortfall is **acquired**: it goes through
  `/cards/add`, Scryfall and all, so the collection gains a real row
  rather than the deck claiming a card that does not exist

The reply lists `returned` and `acquired`, and a dry run plans the
purchase without making it. Buying happens before the deck is written, so
a name Scryfall cannot resolve leaves the deck exactly as it was.

A deck's own current claim does not count against it — the whole list
returns to bulk as part of the edit. Another deck already being short does
not count either: this edit makes its own deck whole, not someone else's.
Proxy and PROPOSED decks acquire nothing, because `card_usage` does not
count them as consuming anything, and basic lands are never bought.

`in_collection` is recomputed on every save, since it is what `deck_gaps`
reports and what `card_usage` counts against `free`. On a real deck it is
therefore always 1. `role` comes from the type line, with the commander
marked as such, and section headings are kept for rows that survive.

---

## The data

18 tables. One row in `cards` per (owner_id, printing, finish), with `qty` as how
many of that stack are owned.

**Join keys.** `cards.name_norm` is `lower(trim(name))` and matches
`deck_cards.name_norm` and `totals.name_norm`. `cards.id` is what every
`card_*` child table's `card_id` points at, and what `card_search.rowid` is.
`cards.oracle_id` joins `legalities` and `rulings`.

**Owners.** `owner_id` on `cards` and `decks` is the owning account's
`users.id`. A collection is named publicly by `users.key`, so filter with
`owner_id = (SELECT id FROM users WHERE key = ?)`. Collections are never
merged. The old `owner` and `slug` columns are retired and read by nothing.

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

`frontend/` is a static site on GitHub Pages at **mtg.mattshoe.org**,
deployed by `.github/workflows/pages.yml` on every push that touches it. The
custom domain is a plain CNAME from GoDaddy to `mattshoe.github.io`, the same
way `boardgames.mattshoe.org` works — `frontend/CNAME` holds the name and
GitHub issues the certificate.

The API stays on `workers.dev`. A Workers custom domain needs the zone itself
hosted on Cloudflare, and mattshoe.org is on GoDaddy nameservers; a CNAME from
there to `*.workers.dev` is refused (error 1014). Moving the zone to
Cloudflare would allow `mtg-api.mattshoe.org`, and nothing else would have to
change. Same stack as the
Worker: plain ES modules, no build step, no framework, no dependencies at all.
Open `frontend/index.html` through any static server and it talks to the live
API.

| view | what it does |
|---|---|
| Library | every column in the database, as facets or as a query language — see below; a grid of cards, 100 a page, both collections, sorted by price descending, one row per card rather than per printing. The name box autocompletes against every card in Magic, not only the ones owned |
| Card | full detail in a drawer — every printing owned, decks it is in, tags, legalities, rulings, and ±1 buttons |
| Decks | every deck with its list, curve, notes and gaps, plus a gaps-and-conflicts overview. Admins can edit a list or disassemble the deck |
| Add / Remove | three steps: list, whose collection, then a dry run you have to approve |
| Stats | both collections side by side, then curve, colours, types, rarity, biggest sets and most unassigned copies for whichever is in scope — `#/stats`, `#/stats/matt`, `#/stats/kayla` |
| Console | arbitrary SQL with a schema browser, snippets, history and CSV export |
| Logs | every request, searchable and filterable, with errors in their own grouped view |

Admin mode is the padlock in the top bar, or `l`. It lives in a JavaScript
variable and nowhere else — not `localStorage`, not `sessionStorage`, not the
URL — so closing or reloading the tab ends it.

While it is off, everything that needs it is simply not there. The Add,
Remove and Logs tabs are absent, their keyboard shortcuts do nothing, and a
bookmark pointing at one of those routes lands on Search with the password
prompt open. The drawer has no ±1 buttons and no unlock button either. The
console still runs reads, because reads need no token, and refuses a write
with a message rather than a second way in. Locking while one of the gated
views is open moves you off it. The padlock is the only entrance.

**Colour matching** has four explicit modes, on either colour identity or the
printed colour: **Exactly**, **At most** (nothing outside these — the one that
answers "what can I put in this commander"), **At least**, and **Any of**.
Colourless is handled per mode rather than pretended to be a sixth colour.

The filter panel is full width and always on screen as eleven group
headers, each collapsed until you open it, so you only unfold what you are
actually using. 76 checkboxes, ranges with operators, segmented toggles,
colour pips. Nothing scrolls inside anything else — the page has one
scrollbar. Open/closed state is remembered, and a link that arrives with
filters set opens the groups responsible. Active filters — whose collection
included — show as removable chips above the panel, and each group header
carries a count.

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

**Prices** appear on every card tile and per printing in the card drawer,
alongside the value of that whole stack and a TCGplayer link. Stats carries
the collection total.

**Export** takes the whole filtered set, not the page you can see, as a
decklist — saved as a `.txt` or straight to the clipboard, capped at 5,000
cards. Lines are `3 Sol Ring`, with no set code: a row is a card summed
over every printing of it you own, so pinning it to one printing would be
a lie.

They live in their own `prices` table keyed by printing, not as columns on
`cards`, so the daily job can rebuild them without touching the collection.
The `card_prices` view picks the figure matching each printing's finish — a
foil row never quotes the nonfoil price. Where a price is genuinely absent
the UI says why rather than showing a bare dash: a printing that has not
come out yet shows its release date, a token shows "not sold singly", and
Stats reports how many cards are unpriced under the total. Because it is
all SQL, sorting by price or by stack value, and filtering on a price
range, are ordinary queries: around 150ms.

Keyboard: `s` `d` `a` `r` `g` `c` jump between views, `/` or `⌘K` finds a card,
`v` opens the logs, `l` locks or unlocks, `t` toggles the theme, `esc`
closes. Searches are shareable: every filter lives in the URL.

## Development

```bash
npm install
npm test          # 266 tests against a real local D1 in workerd
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
| `scripts/refresh_prices.py` | prices from the local Scryfall bulk file, no API calls |
| `scripts/nightly.sh` | backup + tag backfill, run by launchd at 03:00 |
| `scripts/tags.mjs` | tags, the tag tree `otag:` searches, and untagged cards, from the oracle-tags bulk file (writes, so it unlocks first) |
| `scripts/verify.py` | spent: the migration-day parity check against the old shards |
| `scripts/seed.py` | spent: one-time, the five old shards -> `data.sql` |
| `scripts/make_fixture.py` | regenerate the test fixture, from the live API |

## Logging

Every request writes one row to `logs`, after the response has gone out via
`waitUntil`, with every failure swallowed — a log that breaks the thing it
logs is worse than no log. Levels fall out of the outcome: 5xx is `error`,
4xx is `warn`, a write is `info`, a read is `debug`, so the default view is
signal and the reads are still there when you want them.

Nothing secret is ever written. The `/admin` body is never recorded, and any
credential-shaped key that reaches a detail object is redacted on the way in.
A *failed* unlock is recorded, because that is the one thing in here that
looks like someone trying doors.

The Logs page has two modes. **All** is a table you can filter by level,
event, method, status, duration and free text, with each row expanding to
the full detail, IP and CF-Ray. **Errors** is not that with a filter on: it
groups identical failures, leads with the message, and counts repeats —
fifty copies of one broken query is one problem, and reading it as fifty
rows is how you miss the second problem underneath.

Rows live **7 days**; the daily job prunes them.

## Daily maintenance

Two halves, split by what each side can actually do.

**Cloudflare Cron Trigger, 08:10 UTC** (`[triggers]` in `wrangler.toml`).
Nothing needs to be awake. Prunes prices for printings nobody owns, deletes
log rows past the 7-day window, sweeps child rows orphaned from a deleted
card, rebuilds any missing full-text rows, and records a health snapshot. All D1-only, so nothing external can
rate-limit it.

**The Mac, 03:00** (`scripts/nightly.sh`). Refreshes every price, backfills
Scryfall tags, and writes the local backup.

Both log to `maintenance_log`; `GET /maintenance` shows the last 25 entries.

### Why prices run on the Mac

Not preference. Scryfall rate-limits Cloudflare's shared egress IPs hard: a
Worker gets 20 batches of 75 and then a 429 that backing off does not clear,
reproducibly at the same batch, while the identical 55 batches from a home IP
finish in 37 seconds. Their own 429 says to use the bulk data offering for
volume — and that file is 78 MB gzipped, far past what a Worker can hold.

`prefetch_scryfall.py` already downloads that bulk file three times a day for
the deck tooling, so `scripts/refresh_prices.py` reads prices straight off
disk: 4,096 printings in about 12 seconds with no API call at all.

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

## Shipping the Android app

Every merge to `main` builds, signs and publishes the APK to a GitHub
Release, which is where the site's Download button points
(`releases/latest/download/mtg-collection.apk`). It runs only after
the shared, web and Android test jobs have passed.

It needs the **same signing key** the app was first published with —
Android refuses to update an app signed with anything else, so a
differently-signed APK would break every install rather than updating
it. The key lives in `~/.mtg-android.env` locally and has to be given
to the build machine as three repository secrets:

```sh
# from the machine that holds the key
set -a; . ~/.mtg-android.env; set +a
base64 -i "$MTG_ANDROID_KEYSTORE" | gh secret set MTG_ANDROID_KEYSTORE_BASE64
printf %s "$MTG_ANDROID_KEYSTORE_PASSWORD" | gh secret set MTG_ANDROID_KEYSTORE_PASSWORD
printf %s "$MTG_ANDROID_KEY_ALIAS" | gh secret set MTG_ANDROID_KEY_ALIAS
```

Until those exist the release job publishes nothing and says so — a
warning on the run and a line in its summary, rather than a red build
about a missing secret. It does not fall back to a debug key: an APK
nobody can install over the top is worse than no APK.

`versionCode` is the commit count on `main` plus 100, so it only ever
goes up. `versionName` is still edited by hand in
`apps/androidApp/build.gradle.kts`.
