import { describe, it, expect, beforeEach } from 'vitest';
import { env } from 'cloudflare:test';
import { sql, exec } from './helpers.js';
import migration from '../migrations/0015_deck_rows_by_full_name.sql?raw';

/**
 * `0015_deck_rows_by_full_name.sql`, run against a real database.
 *
 * Matt: "cracker barrel deck still says 2 cards not owned even after you
 * said it was fixed". #188 made a deck save turn a front face into the
 * full name, but Cracker Barrel's two rows, "Aang, Swift Savior" and
 * "Witch Enchanter", were written before it and still name a front face.
 * The deck page counts what is owned by joining `cards.name_norm`, which
 * only ever holds the full name, so both read as not owned until the deck
 * was saved again. This rewrites rows like that in place.
 */
describe('0015: deck rows named by a front face take the full name', () => {
  const FULL = 'Aang, Swift Savior // Aang and La, Ocean\'s Fury';
  const FRONT = 'Aang, Swift Savior';
  const DECK = 9150;

  beforeEach(async () => {
    await exec(`DELETE FROM deck_cards WHERE deck_id IN (${DECK}, ${DECK + 1})`);
    await exec(`DELETE FROM decks WHERE id IN (${DECK}, ${DECK + 1})`);
    await exec(`DELETE FROM cards WHERE name LIKE 'Aang, Swift Savior%' OR name LIKE 'Breya%'`);
    await exec(`DELETE FROM aliases WHERE alias_norm LIKE 'aang%' OR alias_norm LIKE 'breya%'`);
    await env.DB.batch([
      env.DB.prepare(`INSERT INTO cards (qty, name, name_norm, face1, face2, oracle_id, type_line, owner_id)
        VALUES (1, ?1, lower(?1), ?2, 'Aang and La, Ocean''s Fury', 'aang-oracle', 'Legendary Creature', 1)`)
        .bind(FULL, FRONT),
      env.DB.prepare('INSERT INTO aliases VALUES (?1, ?2), (?3, ?2), (?4, ?2)')
        .bind(FRONT.toLowerCase(), FULL, 'aang and la, ocean\'s fury', FULL.toLowerCase()),
      env.DB.prepare(`INSERT INTO decks (id, name, key, owner_id, is_proxy, card_count, owned_count)
        VALUES (?1, 'Cracker Barrel', 'crackerb', 1, 0, 1, 1), (?2, 'Both ways', 'bothways', 1, 0, 2, 2)`)
        .bind(DECK, DECK + 1),
    ]);
  });

  const row = (deck, name, qty = 1) => env.DB.prepare(`INSERT INTO deck_cards
      (deck_id, qty, name, name_norm, raw_name, oracle_id, role, section, in_collection)
      VALUES (?1, ?2, ?3, lower(?3), ?3, NULL, 'spell', 'Creatures', 1)`).bind(deck, qty, name);

  /** What the deck page counts as owned: the collection joined by name_norm. */
  const owned = (deck) => sql(`
    SELECT dc.name, dc.qty,
           COALESCE((SELECT SUM(t.qty) FROM cards t
                      WHERE t.name_norm = dc.name_norm AND t.owner_id = d.owner_id), 0) AS owned
      FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
     WHERE dc.deck_id = ? ORDER BY dc.name`, deck);

  /** Statements in a .sql file. Comments go first: they contain semicolons. */
  const statements = (text) => text
    .split('\n').map((l) => l.replace(/--.*$/, '')).join('\n')
    .split(';').map((s) => s.trim()).filter(Boolean);
  const migrate = () => env.DB.batch(statements(migration).map((s) => env.DB.prepare(s)));

  it('a deck row naming the front face of a card that is owned reads as owned after it runs', async () => {
    await row(DECK, FRONT).run();
    expect(await owned(DECK), 'the front-face row already joins a card, so this proves nothing')
      .toEqual([{ name: FRONT, qty: 1, owned: 0 }]);

    await migrate();

    expect(await owned(DECK), 'the deck row still names the front face, so the deck says it is not owned')
      .toEqual([{ name: FULL, qty: 1, owned: 1 }]);
    const [r] = await sql('SELECT raw_name, oracle_id, section FROM deck_cards WHERE deck_id = ?', DECK);
    expect(r, 'what was typed, the oracle id or the heading was lost')
      .toEqual({ raw_name: FRONT, oracle_id: 'aang-oracle', section: 'Creatures' });
  });

  it('the deck holds the copy, so it is no longer counted as free', async () => {
    await row(DECK, FRONT).run();
    await migrate();
    const [u] = await sql('SELECT owned, in_decks, free FROM card_usage WHERE name_norm = ? AND owner_id = 1',
      FULL.toLowerCase());
    expect(u, 'the copy sits in bulk as if no deck held it').toMatchObject({ owned: 1, in_decks: 1, free: 0 });
  });

  it('a deck with the card under both names ends with one row carrying both counts', async () => {
    await env.DB.batch([row(DECK + 1, FRONT, 1), row(DECK + 1, FULL, 1)]);
    await migrate();
    expect(await owned(DECK + 1), 'the two rows were not merged into one')
      .toEqual([{ name: FULL, qty: 2, owned: 1 }]);
  });

  it('leaves a name that is itself a card alone', async () => {
    // An art-series "Breya // Breya" is in aliases under "breya" too, and
    // must not swallow the real Breya.
    await env.DB.batch([
      env.DB.prepare(`INSERT INTO cards (qty, name, name_norm, owner_id) VALUES
        (1, 'Breya, Etherium Shaper', 'breya, etherium shaper', 1)`),
      env.DB.prepare(`INSERT INTO aliases VALUES ('breya, etherium shaper', 'Breya, Etherium Shaper // Breya, Etherium Shaper')`),
      row(DECK, 'Breya, Etherium Shaper'),
    ]);
    await migrate();
    expect(await owned(DECK), 'a card owned by its own name was renamed')
      .toEqual([{ name: 'Breya, Etherium Shaper', qty: 1, owned: 1 }]);
  });
});
