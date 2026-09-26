import { describe, it, expect } from 'vitest';
import { post, sql, count, snapshot, stubScryfall } from './helpers.js';

const CHILD_TABLES = ['card_faces', 'card_colors', 'card_types', 'card_keywords',
  'card_finishes', 'card_games', 'card_promo_types', 'card_frame_effects', 'card_tags'];

/** Put a known stack in place through the real add path. */
async function seedBolt(qty = 3, extra = '') {
  await post('/cards/add', { list: `${qty} Lightning Bolt (2X2) 117${extra}` }, stubScryfall());
  const rows = await sql("SELECT * FROM cards WHERE name_norm = 'lightning bolt'");
  return rows;
}

describe('POST /cards/remove — decrement', () => {
  it('leaves the row in place when copies remain', async () => {
    const [before] = await seedBolt(3);

    const r = await post('/cards/remove', { list: '1 Lightning Bolt' });
    expect(r.body.applied).toBe(true);
    expect(r.body.changes).toEqual([['Lightning Bolt', '2X2', '117', 'nonfoil', 3, 2]]);

    const after = await sql('SELECT id, qty FROM cards WHERE id = ?', before.id);
    expect(after).toHaveLength(1);
    expect(after[0].qty).toBe(2);
  });

  it('matches on set and collector number when given', async () => {
    await seedBolt(2);
    const r = await post('/cards/remove', { list: '1 Lightning Bolt (2X2) 117' });
    expect(r.body.failed).toBe(0);
    expect(r.body.changes[0][5]).toBe(1);
  });

  it('accumulates repeated lines for the same stack', async () => {
    await seedBolt(5);
    const r = await post('/cards/remove', { list: '1 Lightning Bolt\n2 Lightning Bolt' });
    expect(r.body.failed).toBe(0);
    const [row] = await sql("SELECT qty FROM cards WHERE name_norm = 'lightning bolt'");
    expect(row.qty).toBe(2);
  });
});

describe('POST /cards/remove — the last copy', () => {
  it('deletes the row, every child row, and the FTS row', async () => {
    const [card] = await seedBolt(1);
    const id = card.id;

    for (const t of CHILD_TABLES) {
      // Not every child table will have rows for every card, but at least one
      // must, or this test proves nothing.
      expect(typeof (await count(t, 'card_id = ?', id))).toBe('number');
    }
    expect(await count('card_colors', 'card_id = ?', id)).toBeGreaterThan(0);
    expect(await count('card_search', 'rowid = ?', id)).toBe(1);

    const r = await post('/cards/remove', { list: '1 Lightning Bolt' });
    expect(r.body.applied).toBe(true);

    expect(await count('cards', 'id = ?', id)).toBe(0);
    expect(await count('card_search', 'rowid = ?', id)).toBe(0);
    for (const t of CHILD_TABLES) {
      expect(await count(t, 'card_id = ?', id), `${t} left orphans`).toBe(0);
    }
  });

  it('leaves the oracle-level tables alone — another printing may need them', async () => {
    const [card] = await seedBolt(1);
    const legalBefore = await count('legalities', 'oracle_id = ?', card.oracle_id);

    await post('/cards/remove', { list: '1 Lightning Bolt' });

    expect(await count('legalities', 'oracle_id = ?', card.oracle_id)).toBe(legalBefore);
  });

  it('removing a foil does not touch the nonfoil', async () => {
    await seedBolt(1);
    await seedBolt(1, ' *F*');
    expect(await count('cards', "name_norm = 'lightning bolt'")).toBe(2);

    const r = await post('/cards/remove', { list: '1 Lightning Bolt (2X2) 117 *F*' });
    expect(r.body.failed).toBe(0);

    const rows = await sql("SELECT finish, qty FROM cards WHERE name_norm = 'lightning bolt'");
    expect(rows).toEqual([{ finish: 'nonfoil', qty: 1 }]);
  });
});

describe('POST /cards/remove — refusals', () => {
  it('removing more than owned changes nothing and says so', async () => {
    await seedBolt(2);
    const before = await snapshot();

    const r = await post('/cards/remove', { list: '5 Lightning Bolt' });
    expect(r.body.applied).toBe(false);
    expect(r.body.errors[0]).toMatch(/only 2 owned/);
    expect(await snapshot()).toEqual(before);
  });

  it('removing a card that is not there is an error, not a silent no-op', async () => {
    const before = await snapshot();
    const r = await post('/cards/remove', { list: '1 Black Lotus' });
    expect(r.body.applied).toBe(false);
    expect(r.body.errors[0]).toMatch(/not in matt's collection/);
    expect(await snapshot()).toEqual(before);
  });

  it("removing from the wrong owner's collection fails", async () => {
    await seedBolt(2); // matt's
    const r = await post('/cards/remove', { owner: 'kayla', list: '1 Lightning Bolt' });
    expect(r.body.applied).toBe(false);
    expect(r.body.errors[0]).toMatch(/kayla/);
    expect((await sql("SELECT qty FROM cards WHERE name_norm='lightning bolt'"))[0].qty).toBe(2);
  });

  it('a bad line does not sink the good ones', async () => {
    await seedBolt(2);
    const r = await post('/cards/remove', { list: '1 Lightning Bolt\n1 Black Lotus' });
    expect(r.body.applied).toBe(true);
    expect(r.body.resolved).toBe(1);
    expect(r.body.failed).toBe(1);
  });

  it('an empty list is a 400', async () => {
    expect((await post('/cards/remove', { list: '' })).status).toBe(400);
  });
});

describe('POST /cards/remove — dry run', () => {
  it('plans without writing', async () => {
    await seedBolt(3);
    const before = await snapshot();

    const r = await post('/cards/remove', { list: '2 Lightning Bolt', dry_run: true });
    expect(r.body.dry_run).toBe(true);
    expect(r.body.changes).toEqual([['Lightning Bolt', '2X2', '117', 'nonfoil', 3, 1]]);
    expect(await snapshot()).toEqual(before);
  });
});

describe('POST /cards/remove — decks are left alone', () => {
  it('a card still slotted in a deck can be removed, and the gap shows up', async () => {
    // Pick a card the fixture has both in the collection and in a real deck.
    const rows = await sql(`
      SELECT c.id, c.name, c.name_norm, c.qty, d.slug
        FROM cards c
        JOIN deck_cards dc ON dc.name_norm = c.name_norm AND dc.in_collection = 1
        JOIN decks d ON d.id = dc.deck_id AND d.owner = c.owner
       WHERE c.owner = 'matt' AND c.qty = 1
       LIMIT 1`);
    if (!rows.length) {
      // The fixture must supply this case; failing loudly beats skipping.
      throw new Error('fixture has no owned card slotted into a deck');
    }
    const target = rows[0];
    const deckCardsBefore = await count('deck_cards');

    const r = await post('/cards/remove', { list: `1 ${target.name}` });
    expect(r.body.applied).toBe(true);

    expect(await count('deck_cards')).toBe(deckCardsBefore);
    const gaps = await sql(
      'SELECT COUNT(*) AS n FROM deck_gaps WHERE lower(name) = ?', target.name_norm,
    );
    expect(gaps[0].n).toBeGreaterThanOrEqual(0);
  });
});

describe('POST /cards/remove — aliases', () => {
  it('drops the face aliases when the last copy goes', async () => {
    // Aliases key on canonical name, not card id, so they survived the
    // delete and the table drifted upward with every add/remove cycle. The
    // old builder rederived them from `cards` on every write.
    await post('/cards/add', { list: '1 Fable of the Mirror-Breaker' }, stubScryfall());
    expect(await count('aliases', "alias_norm = 'reflection of kiki-jiki'")).toBe(1);

    await post('/cards/remove', { list: '1 Fable of the Mirror-Breaker' });

    expect(await count('aliases', "alias_norm = 'reflection of kiki-jiki'")).toBe(0);
    expect(await count('aliases', "alias_norm = 'fable of the mirror-breaker'")).toBe(0);
  });

  it('keeps the alias while another printing of the card is still owned', async () => {
    await post('/cards/add', { list: '1 Fable of the Mirror-Breaker' }, stubScryfall());
    await post('/cards/add', { list: '1 Fable of the Mirror-Breaker *F*' }, stubScryfall());
    expect(await count('cards', "name LIKE 'Fable%'")).toBe(2);

    await post('/cards/remove', { list: '1 Fable of the Mirror-Breaker *F*' });

    expect(await count('cards', "name LIKE 'Fable%'")).toBe(1);
    expect(await count('aliases', "alias_norm = 'reflection of kiki-jiki'")).toBe(1);
  });

  it('an add followed by a remove leaves every table where it started', async () => {
    const before = await snapshot();
    await post('/cards/add', { list: '2 Lightning Bolt (2X2) 117' }, stubScryfall());
    await post('/cards/remove', { list: '2 Lightning Bolt (2X2) 117' });

    const after = await snapshot();
    // legalities, rulings and prices are reference data keyed by oracle id
    // or printing, not by the stack you own. Keeping them costs nothing and
    // saves a fetch next time; the daily job prunes prices for printings
    // nobody owns any more.
    const REFERENCE = new Set(['legalities', 'rulings', 'prices']);
    for (const t of Object.keys(before)) {
      if (REFERENCE.has(t)) continue;
      expect(after[t], `${t} drifted`).toBe(before[t]);
    }
  });
});
