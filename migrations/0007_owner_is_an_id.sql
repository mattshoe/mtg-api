-- Ownership is an account id, a deck's address is a key, and nothing
-- is decided by a slug any more.
--
-- Matt: "THERE SHOULD NEVER FUCKING BE A COLLISON IN CARDS BY OWNER
-- DUDE ... ARE YOU NOT USING A FUCKING USER ID?!?!?!"
--
-- `cards.owner` and `decks.owner` held a name, and an account owned
-- them only while its `users.slug` happened to spell the same name.
-- Kayla signed in as `kayla-m` and saw an empty collection. Now a card
-- belongs to `users.id`, which nothing a person types can change.
--
-- The old `owner` columns, `decks.slug` and `users.slug` stay where
-- they are, read by nothing, and go in a later migration once no
-- deployed code could still want them. Dropping and adding in one
-- step is how a deploy that half-lands breaks every write.
--
-- Backfilled by joining the slug, not by hardcoding 1 and 3: those are
-- the ids on the live database and nowhere else.

-- The views name the columns being replaced, so they go first and
-- come back at the end.
DROP VIEW IF EXISTS deck_conflicts;
DROP VIEW IF EXISTS bulk_cards;
DROP VIEW IF EXISTS card_usage;
DROP VIEW IF EXISTS decks_not_built;
DROP VIEW IF EXISTS deck_gaps;
DROP VIEW IF EXISTS card_prices;
DROP VIEW IF EXISTS totals;

ALTER TABLE cards ADD COLUMN owner_id INTEGER REFERENCES users(id);
ALTER TABLE decks ADD COLUMN owner_id INTEGER REFERENCES users(id);
ALTER TABLE decks ADD COLUMN key TEXT;

UPDATE cards SET owner_id = (SELECT u.id FROM users u WHERE u.slug = cards.owner)
 WHERE owner_id IS NULL;
UPDATE decks SET owner_id = (SELECT u.id FROM users u WHERE u.slug = decks.owner)
 WHERE owner_id IS NULL;

-- Eight characters of 0-9a-f, inside the alphabet the Worker's own
-- generator draws from, the same way 0003 backfilled `users.key`.
UPDATE decks SET key = lower(hex(randomblob(4))) WHERE key IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_decks_key ON decks(key);
CREATE INDEX IF NOT EXISTS idx_decks_owner_id ON decks(owner_id);
CREATE INDEX IF NOT EXISTS idx_cards_owner_id ON cards(owner_id, name_norm);
-- One physical stack per (account, printing, finish), the rule
-- `idx_cards_unique` kept for the names.
CREATE UNIQUE INDEX IF NOT EXISTS idx_cards_owner_unique ON cards(owner_id, scryfall_id, finish);

-- Scryfall Tagger's vocabulary. The values are theirs and are fine;
-- the word `slug` said they were an address of ours, which they are not.
ALTER TABLE card_tags RENAME COLUMN tag_slug TO tag;
ALTER TABLE tags RENAME COLUMN slug TO tag;
DROP INDEX IF EXISTS idx_tags_slug;
CREATE INDEX IF NOT EXISTS idx_card_tags_tag ON card_tags(tag);

-- Was a maintained table. The aggregate is cheap and a view cannot drift.
CREATE VIEW totals AS
SELECT owner_id, name, name_norm, face1, face2,
       SUM(qty)                                        AS total_qty,
       COUNT(*)                                        AS num_printings,
       MAX(CASE WHEN finish != 'nonfoil' THEN 1 ELSE 0 END) AS has_foil,
       GROUP_CONCAT(DISTINCT setcode)                  AS sets,
       MIN(cmc)                                        AS cmc,
       MIN(type_line)                                  AS type_line,
       MIN(rarity)                                     AS rarity,
       MIN(color_identity)                             AS color_identity
FROM cards
GROUP BY owner_id, name_norm;

-- The price that actually applies to a stack, given its finish. A foil
-- row must not quote the nonfoil price.
CREATE VIEW card_prices AS
SELECT c.id AS card_id, c.owner_id, c.name_norm, c.qty, c.finish,
       CASE c.finish
         WHEN 'foil'   THEN COALESCE(p.usd_foil, p.usd)
         WHEN 'etched' THEN COALESCE(p.usd_etched, p.usd_foil, p.usd)
         ELSE p.usd
       END AS price,
       p.tcg_url, p.updated_at
FROM cards c
LEFT JOIN prices p ON p.scryfall_id = c.scryfall_id;

CREATE VIEW deck_gaps AS
SELECT d.key, d.name AS deck, d.owner_id, dc.name, dc.qty, dc.role
FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
WHERE dc.in_collection = 0;

CREATE VIEW decks_not_built AS
SELECT key, name, owner_id, is_proxy, status, card_count, owned_count
FROM decks
-- "Not built" means proxy or PROPOSED, for either owner. Being Kayla's is
-- not a reason to call a deck unbuilt; her precons are as real as Matt's.
WHERE NOT (is_proxy = 0
           AND (status IS NULL OR status NOT LIKE 'PROPOSED%'));

CREATE VIEW card_usage AS
SELECT t.owner_id,
       t.name,
       t.name_norm,
       t.total_qty                           AS owned,
       COALESCE(u.in_decks, 0)               AS in_decks,
       t.total_qty - COALESCE(u.in_decks, 0) AS free,
       COALESCE(u.deck_count, 0)             AS deck_count
FROM totals t
LEFT JOIN (
    SELECT d.owner_id AS owner_id,
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
    GROUP BY d.owner_id, dc.name_norm
) u ON u.name_norm = t.name_norm AND u.owner_id = t.owner_id;

CREATE VIEW bulk_cards AS
SELECT * FROM card_usage WHERE free > 0;

CREATE VIEW deck_conflicts AS
SELECT cu.owner_id, cu.name, cu.owned, cu.in_decks, cu.deck_count,
       (SELECT GROUP_CONCAT(d2.name, ', ')
          FROM deck_cards dc2 JOIN decks d2 ON d2.id = dc2.deck_id
         WHERE dc2.name_norm = cu.name_norm
               AND d2.owner_id = cu.owner_id AND d2.is_proxy = 0
               AND (d2.status IS NULL OR d2.status NOT LIKE 'PROPOSED%')) AS decks
FROM card_usage cu
WHERE cu.in_decks > cu.owned;


