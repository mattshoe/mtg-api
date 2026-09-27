-- Matt's MTG collection: one database, no shards.
--
-- This is the five old collection-*.db shards collapsed into a single schema.
-- The shards only ever existed because the Google Drive connector capped
-- mobile downloads at 10 MB; nothing about the data wanted to be split.
--
-- Column definitions are carried over verbatim from build_collection_db.py so
-- that an export from here and an export from the old core shard line up row
-- for row. What changed: `totals` is now a view instead of a maintained table,
-- `db_meta` is gone with the staleness protocol it served, and `cards.id`
-- autoincrements because rows are now deleted individually and an id must
-- never be reused while child rows might still reference it.

-- ============================================================ cards
CREATE TABLE cards (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner TEXT,                 -- 'matt' | 'kayla'; collections are separate
    qty INTEGER, finish TEXT, foil_flag TEXT,
    scryfall_id TEXT, oracle_id TEXT,
    name TEXT, name_norm TEXT, face1 TEXT, face2 TEXT,
    mana_cost TEXT, cmc REAL,
    oracle_text TEXT, flavor_text TEXT,
    power TEXT, toughness TEXT, loyalty TEXT, defense TEXT,
    type_line TEXT, supertypes TEXT, types TEXT, subtypes TEXT,
    colors TEXT, color_identity TEXT, color_identity_count INTEGER,
    produced_mana TEXT,
    rarity TEXT, setcode TEXT, set_name TEXT, set_type TEXT,
    released_at TEXT, collector_number TEXT, artist TEXT,
    layout TEXT, frame TEXT, border_color TEXT,
    watermark TEXT, security_stamp TEXT,
    reserved INT, game_changer INT, full_art INT, textless INT,
    promo INT, reprint INT, variation INT, oversized INT,
    story_spotlight INT, booster INT, edhrec_rank INTEGER
);

CREATE TABLE card_faces (
    card_id INT, face_index INT, name TEXT, mana_cost TEXT, type_line TEXT,
    oracle_text TEXT, flavor_text TEXT, power TEXT, toughness TEXT,
    loyalty TEXT, defense TEXT, artist TEXT, colors TEXT
);

CREATE TABLE aliases (alias_norm TEXT, canonical_name TEXT);
CREATE TABLE card_colors  (card_id INT, color TEXT, kind TEXT);
CREATE TABLE card_types   (card_id INT, type TEXT, kind TEXT);
CREATE TABLE card_keywords (card_id INT, keyword TEXT);
CREATE TABLE card_finishes(card_id INT, finish TEXT);
CREATE TABLE card_games   (card_id INT, game TEXT);
CREATE TABLE card_promo_types  (card_id INT, promo_type TEXT);
CREATE TABLE card_frame_effects(card_id INT, frame_effect TEXT);

-- ============================================================ decks
CREATE TABLE decks (
    id INTEGER PRIMARY KEY,
    slug TEXT UNIQUE,           -- one row per deck, no -vN suffixes
    name TEXT,
    owner TEXT,                 -- 'matt' | 'kayla'; never mix the two
    format TEXT,                -- 'commander' | 'standard' | 'modern' | ...
    source_file TEXT,
    recorded_date TEXT,
    status TEXT,
    colors TEXT,
    commander TEXT,
    theme TEXT,
    bracket TEXT,
    is_proxy INTEGER,
    card_count INTEGER,         -- sum of qty in the list
    owned_count INTEGER,        -- of those, how many are in the collection
    source_md TEXT              -- the original markdown, so the file is disposable
);

CREATE TABLE deck_cards (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    deck_id INTEGER REFERENCES decks(id),
    qty INTEGER,
    name TEXT,                  -- canonical name (full DFC name where relevant)
    name_norm TEXT,             -- join key: cards.name_norm / totals.name_norm
    raw_name TEXT,              -- exactly as written in the deck file
    oracle_id TEXT,
    role TEXT,                  -- 'commander' | 'land' | 'spell'
    section TEXT,               -- original markdown heading
    in_collection INTEGER       -- 0 = proxy, Kayla's, or simply not owned
);

CREATE TABLE deck_notes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    deck_id INTEGER REFERENCES decks(id),
    section TEXT,               -- 'Gameplan', 'Correction log', ...
    body TEXT
);

-- ============================================================ oracle-level
CREATE TABLE card_tags (card_id INT, tag_slug TEXT, kind TEXT,
                        UNIQUE(card_id, tag_slug, kind));
CREATE TABLE tags       (slug TEXT, kind TEXT, label TEXT, description TEXT);
CREATE TABLE legalities (oracle_id TEXT, format TEXT, status TEXT);
CREATE TABLE rulings    (oracle_id TEXT, published_at TEXT, source TEXT,
                         comment TEXT);

-- ============================================================ prices
-- Their own table keyed by printing, not columns on `cards`: prices are
-- reference data with a shelf life of about a day, and the daily job
-- rebuilds this without ever touching the collection itself.
CREATE TABLE prices (
    scryfall_id TEXT PRIMARY KEY,
    usd REAL, usd_foil REAL, usd_etched REAL,
    eur REAL, tix REAL,
    tcg_url TEXT,
    updated_at TEXT
);

-- What the daily job did, so a task that quietly stops working is visible.
CREATE TABLE maintenance_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ran_at TEXT,
    task TEXT,
    ok INTEGER,
    detail TEXT,
    ms INTEGER
);

-- ============================================================ logs
-- Request and event log. Reads are gated on admin, unlike every other
-- table here, because this one carries IP addresses and the SQL people
-- ran. Rows live a week; the daily job prunes them.
CREATE TABLE logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts      TEXT NOT NULL,
    level   TEXT NOT NULL,      -- debug | info | warn | error
    event   TEXT,               -- route slug: query, cards.add, admin, ...
    method  TEXT,
    path    TEXT,
    status  INTEGER,
    ms      INTEGER,
    message TEXT,
    detail  TEXT,               -- JSON, never credentials
    ip      TEXT,
    country TEXT,
    ray     TEXT,               -- cf-ray, to line up with Cloudflare's logs
    admin   INTEGER
);

-- ============================================================ full text
CREATE VIRTUAL TABLE card_search USING fts5(
    name, type_line, oracle_text, flavor_text, keywords, tags,
    tokenize='porter unicode61'
);

-- ============================================================ indexes
CREATE INDEX idx_deck_cards_deck ON deck_cards(deck_id);
CREATE INDEX idx_deck_cards_norm ON deck_cards(name_norm);
CREATE INDEX idx_deck_cards_role ON deck_cards(role);
CREATE INDEX idx_decks_owner     ON decks(owner);
CREATE INDEX idx_deck_notes_deck ON deck_notes(deck_id);
CREATE INDEX idx_cards_norm      ON cards(name_norm);
CREATE INDEX idx_cards_owner     ON cards(owner, name_norm);
CREATE INDEX idx_cards_oracle    ON cards(oracle_id);
CREATE INDEX idx_cards_set       ON cards(setcode);
CREATE INDEX idx_cards_cmc       ON cards(cmc);
CREATE INDEX idx_cards_rarity    ON cards(rarity);
CREATE INDEX idx_cards_ci        ON cards(color_identity);
CREATE INDEX idx_cards_power     ON cards(power);
CREATE INDEX idx_kw_card         ON card_keywords(card_id);
CREATE INDEX idx_kw_keyword      ON card_keywords(keyword);
CREATE INDEX idx_types_card      ON card_types(card_id);
CREATE INDEX idx_types_type      ON card_types(type);
CREATE INDEX idx_colors_card     ON card_colors(card_id);
CREATE INDEX idx_faces_card      ON card_faces(card_id);
CREATE INDEX idx_aliases_norm    ON aliases(alias_norm);
CREATE INDEX idx_tags_card       ON card_tags(card_id);
CREATE INDEX idx_tags_slug       ON card_tags(tag_slug);
CREATE INDEX idx_legal_oracle    ON legalities(oracle_id);
CREATE INDEX idx_rulings_oracle  ON rulings(oracle_id);
CREATE INDEX idx_prices_usd      ON prices(usd);
CREATE INDEX idx_maint_ran       ON maintenance_log(ran_at DESC);
CREATE INDEX idx_logs_ts         ON logs(ts DESC);
CREATE INDEX idx_logs_level      ON logs(level, ts DESC);
CREATE INDEX idx_logs_event      ON logs(event, ts DESC);
CREATE INDEX idx_logs_status     ON logs(status, ts DESC);

-- One physical stack per (person, printing, finish). The old builder rebuilt
-- the whole table from a card list every time, so it could not collide;
-- incremental adds can, and a silent duplicate row would double a count
-- everywhere downstream.
CREATE UNIQUE INDEX idx_cards_unique ON cards(owner, scryfall_id, finish);

-- ============================================================ views
-- Was a maintained table. The aggregate is cheap and a view cannot drift.
CREATE VIEW totals AS
SELECT owner, name, name_norm, face1, face2,
       SUM(qty)                                        AS total_qty,
       COUNT(*)                                        AS num_printings,
       MAX(CASE WHEN finish != 'nonfoil' THEN 1 ELSE 0 END) AS has_foil,
       GROUP_CONCAT(DISTINCT setcode)                  AS sets,
       MIN(cmc)                                        AS cmc,
       MIN(type_line)                                  AS type_line,
       MIN(rarity)                                     AS rarity,
       MIN(color_identity)                             AS color_identity
FROM cards
GROUP BY owner, name_norm;

-- The price that actually applies to a stack, given its finish. A foil
-- row must not quote the nonfoil price.
CREATE VIEW card_prices AS
SELECT c.id AS card_id, c.owner, c.name_norm, c.qty, c.finish,
       CASE c.finish
         WHEN 'foil'   THEN COALESCE(p.usd_foil, p.usd)
         WHEN 'etched' THEN COALESCE(p.usd_etched, p.usd_foil, p.usd)
         ELSE p.usd
       END AS price,
       p.tcg_url, p.updated_at
FROM cards c
LEFT JOIN prices p ON p.scryfall_id = c.scryfall_id;

CREATE VIEW deck_gaps AS
SELECT d.slug, d.name AS deck, d.owner, dc.name, dc.qty, dc.role
FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
WHERE dc.in_collection = 0;

CREATE VIEW decks_not_built AS
SELECT slug, name, owner, is_proxy, status, card_count, owned_count
FROM decks
-- "Not built" means proxy or PROPOSED, for either owner. Being Kayla's is
-- not a reason to call a deck unbuilt; her precons are as real as Matt's.
WHERE NOT (is_proxy = 0
           AND (status IS NULL OR status NOT LIKE 'PROPOSED%'));

CREATE VIEW card_usage AS
SELECT t.owner,
       t.name,
       t.name_norm,
       t.total_qty                           AS owned,
       COALESCE(u.in_decks, 0)               AS in_decks,
       t.total_qty - COALESCE(u.in_decks, 0) AS free,
       COALESCE(u.deck_count, 0)             AS deck_count
FROM totals t
LEFT JOIN (
    SELECT d.owner AS owner,
           dc.name_norm,
           SUM(dc.qty)                AS in_decks,
           COUNT(DISTINCT dc.deck_id) AS deck_count
    FROM deck_cards dc
    JOIN decks d ON d.id = dc.deck_id
    -- Only decks that physically exist consume cards: not a proxy build, not
    -- merely PROPOSED, and the slot backed by a card that owner actually
    -- holds. Excluded slots show up in deck_gaps instead.
    WHERE d.is_proxy = 0
      AND (d.status IS NULL OR d.status NOT LIKE 'PROPOSED%')
      AND dc.in_collection = 1
    GROUP BY d.owner, dc.name_norm
) u ON u.name_norm = t.name_norm AND u.owner = t.owner;

CREATE VIEW bulk_cards AS
SELECT * FROM card_usage WHERE free > 0;

CREATE VIEW deck_conflicts AS
SELECT cu.owner, cu.name, cu.owned, cu.in_decks, cu.deck_count,
       (SELECT GROUP_CONCAT(d2.slug, ', ')
          FROM deck_cards dc2 JOIN decks d2 ON d2.id = dc2.deck_id
         WHERE dc2.name_norm = cu.name_norm
               AND d2.owner = cu.owner AND d2.is_proxy = 0
               AND (d2.status IS NULL OR d2.status NOT LIKE 'PROPOSED%')) AS decks
FROM card_usage cu
WHERE cu.in_decks > cu.owned;
