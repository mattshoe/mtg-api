-- The decks that were made in the app before it knew what colour they
-- were.
--
-- Matt: "Why is milly moth deck showing as colorless???"
--
-- `createDeck` wrote `colors: null` and nothing filled it in, so the
-- three decks typed into the wizard were the only colourless ones in a
-- database where every imported deck had the column set. The code is
-- fixed for new decks; these three already exist.
--
-- From the commander's own colour identity, which `cards` stores as
-- the bare WUBRG letters `Deck.identity` reads first. Only decks that
-- have a commander and no colours, so nothing already set is
-- overwritten and nothing is invented for a deck whose commander the
-- collection does not hold.
UPDATE decks
   SET colors = (
     SELECT c.color_identity
       FROM cards c
      WHERE c.name_norm = lower(trim(CASE
              WHEN instr(decks.commander, ' (') > 0
              THEN substr(decks.commander, 1, instr(decks.commander, ' (') - 1)
              ELSE decks.commander END))
        AND c.color_identity IS NOT NULL
        AND c.color_identity != ''
      LIMIT 1)
 WHERE colors IS NULL
   AND commander IS NOT NULL
   AND commander != '';
