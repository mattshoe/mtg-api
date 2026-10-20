-- Matt: "cracker barrel deck still says 2 cards not owned even after you
-- said it was fixed".
--
-- The collection names a two-faced card by its full name, "Aang, Swift
-- Savior // Aang and La, Ocean's Fury". A deck list that named it by its
-- front face kept the front face in deck_cards, so the row joined no card:
-- the deck page counted it not owned and card_usage counted the copy free.
-- #188 fixed that for every save from then on. Cracker Barrel's two rows
-- were written before it, and nothing rewrites a row until the deck is
-- saved again. This does, once.
--
-- A row is stale when its name is no card at all and aliases names it as a
-- face of a "X // Y" card. The same rule as fullNameIndex in src/decks.js,
-- so a name that is itself a card, like Breya beside an art-series
-- "Breya // Breya", is left alone.

-- A deck that already has the full name as well: add the stale count to it.
UPDATE deck_cards
   SET qty = qty + (
     SELECT SUM(s.qty) FROM deck_cards s
      WHERE s.deck_id = deck_cards.deck_id
        AND s.name_norm NOT LIKE '%//%'
        AND NOT EXISTS (SELECT 1 FROM cards c WHERE c.name_norm = s.name_norm)
        AND lower((SELECT MIN(a.canonical_name) FROM aliases a
                    WHERE a.alias_norm = s.name_norm AND a.canonical_name LIKE '% // %'))
            = deck_cards.name_norm)
 WHERE EXISTS (
     SELECT 1 FROM deck_cards s
      WHERE s.deck_id = deck_cards.deck_id
        AND s.name_norm NOT LIKE '%//%'
        AND NOT EXISTS (SELECT 1 FROM cards c WHERE c.name_norm = s.name_norm)
        AND lower((SELECT MIN(a.canonical_name) FROM aliases a
                    WHERE a.alias_norm = s.name_norm AND a.canonical_name LIKE '% // %'))
            = deck_cards.name_norm);

-- ...and drop the stale row it was added from.
DELETE FROM deck_cards
 WHERE name_norm NOT LIKE '%//%'
   AND NOT EXISTS (SELECT 1 FROM cards c WHERE c.name_norm = deck_cards.name_norm)
   AND EXISTS (
     SELECT 1 FROM deck_cards t
      WHERE t.deck_id = deck_cards.deck_id
        AND t.name_norm = lower((SELECT MIN(a.canonical_name) FROM aliases a
                                  WHERE a.alias_norm = deck_cards.name_norm
                                    AND a.canonical_name LIKE '% // %')));

-- Every other stale row takes the full name, and the oracle id it lacked.
UPDATE deck_cards
   SET name = (SELECT MIN(a.canonical_name) FROM aliases a
                WHERE a.alias_norm = deck_cards.name_norm AND a.canonical_name LIKE '% // %'),
       name_norm = lower((SELECT MIN(a.canonical_name) FROM aliases a
                           WHERE a.alias_norm = deck_cards.name_norm AND a.canonical_name LIKE '% // %')),
       oracle_id = COALESCE(oracle_id, (
         SELECT MIN(c.oracle_id) FROM cards c
          WHERE c.name_norm = lower((SELECT MIN(a.canonical_name) FROM aliases a
                                      WHERE a.alias_norm = deck_cards.name_norm
                                        AND a.canonical_name LIKE '% // %'))))
 WHERE name_norm NOT LIKE '%//%'
   AND NOT EXISTS (SELECT 1 FROM cards c WHERE c.name_norm = deck_cards.name_norm)
   AND EXISTS (SELECT 1 FROM aliases a
                WHERE a.alias_norm = deck_cards.name_norm AND a.canonical_name LIKE '% // %');
