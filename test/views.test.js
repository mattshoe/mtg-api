import { describe, it, expect } from 'vitest';
import { post, sql, exec, count, stubScryfall } from './helpers.js';

// `totals` used to be a maintained table rebuilt on every write. It is now a
// view. These are the regressions that would follow from getting that wrong,
// because five other things read through it.

describe('totals', () => {
  it('has the columns the old table had', async () => {
    const info = await sql('PRAGMA table_info(totals)');
    expect(info.map((c) => c.name)).toEqual([
      'owner_id', 'name', 'name_norm', 'face1', 'face2', 'total_qty',
      'num_printings', 'has_foil', 'sets', 'cmc', 'type_line', 'rarity',
      'color_identity',
    ]);
  });

  it('sums quantity across printings of the same card', async () => {
    const rows = await sql(`
      SELECT t.owner_id, t.name_norm, t.total_qty, t.num_printings,
             (SELECT SUM(qty) FROM cards c
               WHERE c.owner_id = t.owner_id AND c.name_norm = t.name_norm) AS real_qty,
             (SELECT COUNT(*) FROM cards c
               WHERE c.owner_id = t.owner_id AND c.name_norm = t.name_norm) AS real_printings
        FROM totals t`);
    expect(rows.length).toBeGreaterThan(0);
    for (const r of rows) {
      expect(r.total_qty, `${r.owner_id}/${r.name_norm}`).toBe(r.real_qty);
      expect(r.num_printings, `${r.owner_id}/${r.name_norm}`).toBe(r.real_printings);
    }
  });

  it('never merges the two collections', async () => {
    const dupes = await sql(`
      SELECT name_norm, COUNT(DISTINCT owner_id) AS owners
        FROM totals GROUP BY name_norm HAVING owners > 1`);
    for (const d of dupes) {
      const rows = await sql('SELECT owner_id FROM totals WHERE name_norm = ?', d.name_norm);
      // A card owned by both people is two rows, not one merged row.
      expect(new Set(rows.map((r) => r.owner_id)).size).toBe(d.owners);
    }
    const perOwner = await sql('SELECT COUNT(*) AS n FROM totals GROUP BY owner_id');
    expect(perOwner.length).toBeGreaterThan(1);
  });

  it('has_foil is set when any printing is not nonfoil', async () => {
    const rows = await sql(`
      SELECT t.name_norm, t.owner_id, t.has_foil,
             (SELECT MAX(CASE WHEN finish != 'nonfoil' THEN 1 ELSE 0 END)
                FROM cards c WHERE c.owner_id = t.owner_id AND c.name_norm = t.name_norm) AS real
        FROM totals t`);
    for (const r of rows) expect(r.has_foil).toBe(r.real);
  });

  it('follows an add without anything having to be rebuilt', async () => {
    const before = await sql("SELECT total_qty FROM totals WHERE name_norm = 'lightning bolt'");
    expect(before).toHaveLength(0);

    await post('/cards/add', { list: '4 Lightning Bolt (2X2) 117' }, stubScryfall());

    const after = await sql("SELECT total_qty, num_printings FROM totals WHERE name_norm = 'lightning bolt'");
    expect(after).toEqual([{ total_qty: 4, num_printings: 1 }]);
  });

  it('follows a remove, and the row disappears with the last copy', async () => {
    await post('/cards/add', { list: '2 Lightning Bolt (2X2) 117' }, stubScryfall());
    await post('/cards/remove', { list: '1 Lightning Bolt' });
    expect((await sql("SELECT total_qty FROM totals WHERE name_norm='lightning bolt'"))[0].total_qty).toBe(1);

    await post('/cards/remove', { list: '1 Lightning Bolt' });
    expect(await sql("SELECT * FROM totals WHERE name_norm = 'lightning bolt'")).toHaveLength(0);
  });
});

describe('card_usage', () => {
  it('free is owned minus in_decks, every row', async () => {
    const rows = await sql('SELECT owned, in_decks, free FROM card_usage');
    expect(rows.length).toBeGreaterThan(0);
    for (const r of rows) expect(r.free).toBe(r.owned - r.in_decks);
  });

  it('proxy and PROPOSED decks do not consume cards', async () => {
    const excluded = await sql(`
      SELECT dc.name_norm, d.owner_id, SUM(dc.qty) AS q
        FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
       WHERE (d.is_proxy = 1 OR d.status LIKE 'PROPOSED%')
         AND dc.in_collection = 1
       GROUP BY dc.name_norm, d.owner_id`);

    for (const e of excluded) {
      const real = await sql(`
        SELECT COALESCE(SUM(dc.qty), 0) AS q
          FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
         WHERE dc.name_norm = ? AND d.owner_id = ? AND dc.in_collection = 1
           AND d.is_proxy = 0 AND (d.status IS NULL OR d.status NOT LIKE 'PROPOSED%')`,
      e.name_norm, e.owner_id);
      const usage = await sql(
        'SELECT in_decks FROM card_usage WHERE name_norm = ? AND owner_id = ?', e.name_norm, e.owner_id,
      );
      if (usage.length) expect(usage[0].in_decks).toBe(real[0].q);
    }
  });

  it('does not count one owner\'s deck against the other\'s collection', async () => {
    const rows = await sql(`
      SELECT cu.owner_id, cu.name_norm, cu.in_decks
        FROM card_usage cu WHERE cu.in_decks > 0`);
    for (const r of rows) {
      const real = await sql(`
        SELECT SUM(dc.qty) AS q FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
         WHERE dc.name_norm = ? AND d.owner_id = ? AND dc.in_collection = 1
           AND d.is_proxy = 0 AND (d.status IS NULL OR d.status NOT LIKE 'PROPOSED%')`,
      r.name_norm, r.owner_id);
      expect(r.in_decks).toBe(real[0].q);
    }
  });

  it('a card in no deck is entirely free', async () => {
    await post('/cards/add', { list: '3 Lightning Bolt (2X2) 117' }, stubScryfall());
    const [row] = await sql("SELECT owned, in_decks, free FROM card_usage WHERE name_norm='lightning bolt'");
    expect(row).toEqual({ owned: 3, in_decks: 0, free: 3 });
  });
});

describe('bulk_cards', () => {
  it('is exactly card_usage where free > 0', async () => {
    const a = await count('bulk_cards');
    const b = await count('card_usage', 'free > 0');
    expect(a).toBe(b);
  });

  it('has the same columns as card_usage', async () => {
    const bulk = (await sql('PRAGMA table_info(bulk_cards)')).map((c) => c.name);
    const usage = (await sql('PRAGMA table_info(card_usage)')).map((c) => c.name);
    expect(bulk).toEqual(usage);
  });

  it('picks up a newly added card', async () => {
    await post('/cards/add', { list: '1 Lightning Bolt (2X2) 117' }, stubScryfall());
    expect(await count('bulk_cards', "name_norm = 'lightning bolt'")).toBe(1);
  });
});

describe('deck_gaps', () => {
  it('lists exactly the deck slots not backed by a collection card', async () => {
    expect(await count('deck_gaps')).toBe(await count('deck_cards', 'in_collection = 0'));
  });

  it('every row names a real deck', async () => {
    const rows = await sql('SELECT key FROM deck_gaps');
    for (const r of rows) {
      expect(await count('decks', 'key = ?', r.key)).toBe(1);
    }
  });
});

describe('decks_not_built', () => {
  it('is proxies and PROPOSED decks, for either owner', async () => {
    const view = await sql('SELECT key FROM decks_not_built ORDER BY key');
    const direct = await sql(`
      SELECT key FROM decks
       WHERE NOT (is_proxy = 0 AND (status IS NULL OR status NOT LIKE 'PROPOSED%'))
       ORDER BY key`);
    expect(view).toEqual(direct);
  });

  it('a real built deck is not in it', async () => {
    const built = await sql(`
      SELECT key FROM decks
       WHERE is_proxy = 0 AND (status IS NULL OR status NOT LIKE 'PROPOSED%') LIMIT 1`);
    if (built.length) {
      expect(await count('decks_not_built', 'key = ?', built[0].key)).toBe(0);
    }
  });
});

describe('deck_conflicts', () => {
  it('fires only when a card is slotted more times than it is owned', async () => {
    const rows = await sql('SELECT owner_id, name, owned, in_decks FROM deck_conflicts');
    for (const r of rows) expect(r.in_decks).toBeGreaterThan(r.owned);
  });

  it('appears when a removal takes the collection below what decks need', async () => {
    // Built rather than found: own 2 copies, commit both to two real decks,
    // then sell one. Two decks so the surviving row keeps in_decks at 2.
    const decks = await sql(`
      SELECT id, owner_id FROM decks
       WHERE is_proxy = 0 AND (status IS NULL OR status NOT LIKE 'PROPOSED%')
         AND owner_id = 1 LIMIT 2`);
    if (decks.length < 2) throw new Error('fixture needs two built decks for matt');

    await post('/cards/add', { list: '2 Lightning Bolt (2X2) 117' }, stubScryfall());
    for (const d of decks) {
      await exec(
        `INSERT INTO deck_cards (deck_id, qty, name, name_norm, raw_name, role, in_collection)
         VALUES (?, 1, 'Lightning Bolt', 'lightning bolt', 'Lightning Bolt', 'spell', 1)`,
        d.id,
      );
    }

    // owned 2, in_decks 2 — committed to the hilt but not yet in conflict.
    const before = await sql("SELECT owned, in_decks FROM card_usage WHERE name_norm='lightning bolt'");
    expect(before).toEqual([{ owned: 2, in_decks: 2 }]);
    expect(await count('deck_conflicts', "name = 'Lightning Bolt'")).toBe(0);

    const r = await post('/cards/remove', { list: '1 Lightning Bolt' });
    expect(r.body.applied).toBe(true);

    const conflicts = await sql(
      "SELECT owned, in_decks, decks FROM deck_conflicts WHERE name = 'Lightning Bolt'",
    );
    expect(conflicts).toHaveLength(1);
    expect(conflicts[0].owned).toBe(1);
    expect(conflicts[0].in_decks).toBe(2);
    expect(conflicts[0].decks).toBeTruthy();
  });

  it('a card whose last copy is sold leaves card_usage entirely', async () => {
    // Worth pinning down because it is surprising: totals is derived from
    // cards, so the row vanishes rather than reading owned = 0. The old
    // schema behaved identically, and deck_cards.in_collection is a stored
    // snapshot that does not notice either.
    const [deck] = await sql(`
      SELECT id FROM decks WHERE is_proxy = 0
        AND (status IS NULL OR status NOT LIKE 'PROPOSED%') AND owner_id = 1 LIMIT 1`);
    await post('/cards/add', { list: '1 Lightning Bolt (2X2) 117' }, stubScryfall());
    await exec(
      `INSERT INTO deck_cards (deck_id, qty, name, name_norm, raw_name, role, in_collection)
       VALUES (?, 1, 'Lightning Bolt', 'lightning bolt', 'Lightning Bolt', 'spell', 1)`,
      deck.id,
    );

    await post('/cards/remove', { list: '1 Lightning Bolt' });

    expect(await count('card_usage', "name_norm = 'lightning bolt'")).toBe(0);
    expect(await count('deck_conflicts', "name = 'Lightning Bolt'")).toBe(0);
    expect(await count('deck_cards', "name_norm = 'lightning bolt'")).toBe(1);
  });
});
