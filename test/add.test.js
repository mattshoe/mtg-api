import { describe, it, expect } from 'vitest';
import { env } from 'cloudflare:test';
import { post, sql, exec, count, snapshot, stubScryfall } from './helpers.js';
import { KAYLA } from './helpers.js';

// From test/fixtures/scryfall/collection-basic.json — the printing the stub
// hands back for "Lightning Bolt (2X2) 117".
const BOLT_SCRYFALL_ID = 'f29ba16f-c8fb-42fe-aabf-87089cb214a7';

const BOLT = '1 Lightning Bolt (2X2) 117';
const YAVIMAYA = '1 Yavimaya, Cradle of Growth (M3C) 409';
const FABLE = '1 Fable of the Mirror-Breaker';

/** The one card row this add should have produced. */
async function stack(name, finish = 'nonfoil', ownerId = 1) {
  const rows = await sql(
    'SELECT * FROM cards WHERE owner_id = ? AND name_norm = ? AND finish = ?',
    ownerId, name.toLowerCase(), finish,
  );
  return rows;
}

describe('POST /cards/add — a new card', () => {
  it('inserts one row with the right quantity and finish', async () => {
    const r = await post('/cards/add', { list: `4 ${BOLT.slice(2)}` }, stubScryfall());
    expect(r.status).toBe(200);
    expect(r.body.applied).toBe(true);
    expect(r.body.failed).toBe(0);
    expect(r.body.changes).toEqual([['Lightning Bolt', '2X2', '117', 'nonfoil', 0, 4, 'f29ba16f-c8fb-42fe-aabf-87089cb214a7']]);

    const rows = await stack('Lightning Bolt');
    expect(rows).toHaveLength(1);
    expect(rows[0].qty).toBe(4);
    expect(rows[0].setcode).toBe('2x2');
    expect(rows[0].name_norm).toBe('lightning bolt');
    expect(rows[0].owner_id).toBe(1);
  });

  it('derives every child table from the Scryfall record', async () => {
    await post('/cards/add', { list: BOLT }, stubScryfall());
    const [card] = await stack('Lightning Bolt');

    const colors = await sql('SELECT color, kind FROM card_colors WHERE card_id = ?', card.id);
    expect(colors.filter((c) => c.kind === 'color').map((c) => c.color)).toEqual(['R']);
    expect(colors.filter((c) => c.kind === 'identity').map((c) => c.color)).toEqual(['R']);

    const types = await sql('SELECT type, kind FROM card_types WHERE card_id = ?', card.id);
    expect(types.map((t) => `${t.kind}:${t.type}`)).toContain('type:Instant');

    expect(await count('card_finishes', 'card_id = ?', card.id)).toBeGreaterThan(0);
    expect(await count('card_games', 'card_id = ?', card.id)).toBeGreaterThan(0);
    expect(await count('card_search', 'rowid = ?', card.id)).toBe(1);
  });

  it('writes legalities once per oracle card, not once per printing', async () => {
    await post('/cards/add', { list: BOLT }, stubScryfall());
    const [card] = await stack('Lightning Bolt');
    const before = await count('legalities', 'oracle_id = ?', card.oracle_id);
    expect(before).toBeGreaterThan(0);

    // A second printing of the same oracle card must not duplicate them.
    await post('/cards/add', { list: '1 Lightning Bolt (2X2) 117 *F*' }, stubScryfall());
    expect(await count('legalities', 'oracle_id = ?', card.oracle_id)).toBe(before);
  });

  it('tags a new card at once when another printing of it is already tagged', async () => {
    // Tags come from a bulk file the Worker cannot read, so a card nobody
    // has owned before waits for the nightly script. One whose oracle card
    // is already here has nothing to wait for.
    await post('/cards/add', { list: BOLT }, stubScryfall());
    const [nonfoil] = await stack('Lightning Bolt');
    await exec("INSERT INTO card_tags (card_id, tag, kind) VALUES (?, 'burn', 'oracle')", nonfoil.id);

    await post('/cards/add', { list: '1 Lightning Bolt (2X2) 117 *F*' }, stubScryfall());
    const [foil] = await stack('Lightning Bolt', 'foil');

    const tags = await sql('SELECT tag, kind FROM card_tags WHERE card_id = ?', foil.id);
    expect(tags, 'the foil stack arrived with no tags').toEqual([{ tag: 'burn', kind: 'oracle' }]);
    const [indexed] = await sql('SELECT tags FROM card_search WHERE rowid = ?', foil.id);
    expect(indexed.tags, 'the search index does not know the foil is tagged').toBe('burn');
  });

  it('stores no not_legal rows', async () => {
    await post('/cards/add', { list: BOLT }, stubScryfall());
    expect(await count('legalities', "status = 'not_legal'")).toBe(0);
  });

  it('sets the sensible defaults on a card with no colors', async () => {
    await post('/cards/add', { list: YAVIMAYA }, stubScryfall());
    const [card] = await stack('Yavimaya, Cradle of Growth');
    expect(card.colors).toBeNull();
    expect(card.type_line).toContain('Land');
    expect(card.color_identity_count).toBe(card.color_identity ? card.color_identity.length : 0);
  });
});

describe('POST /cards/add — an existing card', () => {
  it('increments a row that came from the seed, not from a prior add', async () => {
    // A row already in the database has a low explicit id and sits under the
    // unique index. The add path must find it by (owner_id, scryfall_id, finish)
    // and update it, rather than collide trying to insert alongside it.
    await exec(
      `INSERT INTO cards (owner_id, qty, finish, foil_flag, scryfall_id, name, name_norm,
                          setcode, collector_number)
       VALUES (1, 3, 'nonfoil', '', ?, 'Lightning Bolt', 'lightning bolt', '2x2', '117')`,
      BOLT_SCRYFALL_ID,
    );

    const before = await sql("SELECT id, qty FROM cards WHERE name_norm = 'lightning bolt'");
    expect(before).toHaveLength(1);

    const r = await post('/cards/add', { list: '2 Lightning Bolt (2X2) 117' }, stubScryfall());
    expect(r.body.changes).toEqual([['Lightning Bolt', '2X2', '117', 'nonfoil', 3, 5, 'f29ba16f-c8fb-42fe-aabf-87089cb214a7']]);

    const after = await sql("SELECT id, qty FROM cards WHERE name_norm = 'lightning bolt'");
    expect(after).toHaveLength(1);
    expect(after[0].id).toBe(before[0].id);
    expect(after[0].qty).toBe(5);
  });

  it('a second add of the same printing adds to the same row', async () => {
    await post('/cards/add', { list: '2 Lightning Bolt (2X2) 117' }, stubScryfall());
    const first = await stack('Lightning Bolt');
    expect(first[0].qty).toBe(2);

    const r = await post('/cards/add', { list: '3 Lightning Bolt (2X2) 117' }, stubScryfall());
    expect(r.body.changes).toEqual([['Lightning Bolt', '2X2', '117', 'nonfoil', 2, 5, 'f29ba16f-c8fb-42fe-aabf-87089cb214a7']]);

    const second = await stack('Lightning Bolt');
    expect(second).toHaveLength(1);
    expect(second[0].qty).toBe(5);
    expect(second[0].id).toBe(first[0].id);
  });

  it('folds duplicate lines in one request into a single change', async () => {
    const r = await post('/cards/add', {
      list: '1 Lightning Bolt (2X2) 117\n2 Lightning Bolt (2X2) 117',
    }, stubScryfall());
    expect(r.body.changes).toEqual([['Lightning Bolt', '2X2', '117', 'nonfoil', 0, 3, 'f29ba16f-c8fb-42fe-aabf-87089cb214a7']]);
    expect((await stack('Lightning Bolt'))[0].qty).toBe(3);
  });

  it('keeps foil and nonfoil of the same printing as separate stacks', async () => {
    await post('/cards/add', { list: '1 Lightning Bolt (2X2) 117' }, stubScryfall());
    await post('/cards/add', { list: '1 Lightning Bolt (2X2) 117 *F*' }, stubScryfall());

    const rows = await sql("SELECT finish, qty FROM cards WHERE owner_id = 1 AND name_norm='lightning bolt' ORDER BY finish");
    expect(rows).toEqual([
      { finish: 'foil', qty: 1 },
      { finish: 'nonfoil', qty: 1 },
    ]);
  });
});

describe('POST /cards/add — double-faced cards', () => {
  it('populates card_faces and both face columns', async () => {
    const r = await post('/cards/add', { list: FABLE }, stubScryfall());
    expect(r.body.failed).toBe(0);

    const rows = await sql("SELECT * FROM cards WHERE name LIKE 'Fable of the Mirror-Breaker%'");
    expect(rows).toHaveLength(1);
    const card = rows[0];
    expect(card.name).toBe('Fable of the Mirror-Breaker // Reflection of Kiki-Jiki');
    expect(card.face1).toBe('Fable of the Mirror-Breaker');
    expect(card.face2).toBe('Reflection of Kiki-Jiki');

    const faces = await sql('SELECT face_index, name FROM card_faces WHERE card_id = ? ORDER BY face_index', card.id);
    expect(faces.map((f) => f.name)).toEqual([
      'Fable of the Mirror-Breaker', 'Reflection of Kiki-Jiki',
    ]);
  });

  it('joins both faces oracle text so single-column search still works', async () => {
    await post('/cards/add', { list: FABLE }, stubScryfall());
    const [card] = await sql("SELECT oracle_text FROM cards WHERE name LIKE 'Fable%'");
    expect(card.oracle_text).toContain('//');
  });

  it('records an alias for each face name', async () => {
    await post('/cards/add', { list: FABLE }, stubScryfall());
    const rows = await sql(
      "SELECT canonical_name FROM aliases WHERE alias_norm = 'reflection of kiki-jiki'",
    );
    expect(rows[0].canonical_name).toBe('Fable of the Mirror-Breaker // Reflection of Kiki-Jiki');
  });

  it('resolves a card asked for by its full two-faced name', async () => {
    // Scryfall's collection endpoint will not take "A // B"; only the front
    // face. The database stores the full name, so this has to keep working.
    const r = await post('/cards/add', {
      list: '1 Fable of the Mirror-Breaker // Reflection of Kiki-Jiki',
    }, stubScryfall());
    expect(r.body.failed).toBe(0);
    expect(r.body.applied).toBe(true);
  });
});

describe('POST /cards/add — dry run', () => {
  it('reports the plan and writes absolutely nothing', async () => {
    const before = await snapshot();
    const r = await post('/cards/add', { list: `4 Lightning Bolt (2X2) 117`, dry_run: true }, stubScryfall());

    expect(r.body.applied).toBe(false);
    expect(r.body.dry_run).toBe(true);
    expect(r.body.changes).toEqual([['Lightning Bolt', '2X2', '117', 'nonfoil', 0, 4, 'f29ba16f-c8fb-42fe-aabf-87089cb214a7']]);
    expect(await snapshot()).toEqual(before);
  });

  it('a dry run over an existing stack shows the real before value', async () => {
    await post('/cards/add', { list: '2 Lightning Bolt (2X2) 117' }, stubScryfall());
    const r = await post('/cards/add', { list: '1 Lightning Bolt (2X2) 117', dry_run: true }, stubScryfall());
    expect(r.body.changes[0][4]).toBe(2);
    expect(r.body.changes[0][5]).toBe(3);
  });
});

// Matt, four times: "GET RID OF THE FUCKING RULINGS. DO NOT EVER SET
// THEM ON A NEW CARD."
//
// The fetch is gone, but gone is not the same as cannot come back. Scryfall
// has no bulk rulings endpoint, so anything that wants them has to ask per
// card, inside whatever HTTP request the person is waiting on. That is the
// shape this forbids.
describe('POST /cards/add — rulings are never set', () => {
  it('writes no rulings row for a brand new card', async () => {
    const before = await count('rulings');
    const r = await post('/cards/add', { list: '1 Fable of the Mirror-Breaker' });
    expect(r.body.applied).toBe(true);
    // Looked up by prefix: it is a two-faced card and `name_norm` carries
    // both faces, which is exactly the kind of guess worth not making.
    const card = (await sql("SELECT oracle_id, name_norm FROM cards WHERE name_norm LIKE 'fable of the mirror%'"))[0];
    expect(card?.oracle_id, 'the card itself must still land').toBeTruthy();
    expect(await count('rulings', 'oracle_id = ?', card.oracle_id)).toBe(0);
    expect(await count('rulings'), 'the table must be exactly as it was').toBe(before);
  });

  it('asks Scryfall for nothing but the names', async () => {
    const stub = stubScryfall();
    const paths = [];
    const watch = async (url, init) => {
      paths.push(new URL(url).pathname);
      return stub(url, init);
    };
    const r = await post('/cards/add', {
      list: '1 Fable of the Mirror-Breaker\n1 Lightning Bolt (2X2) 117',
    }, watch);
    expect(r.body.applied).toBe(true);
    expect(paths.filter((p) => p.endsWith('/rulings')), 'a rulings request went out').toEqual([]);
    expect(paths.every((p) => p === '/cards/collection'), `unexpected calls: ${paths}`).toBe(true);
  });

  it('has no rulings method on the Scryfall client to call', async () => {
    const { makeClient } = await import('../src/scryfall.js');
    expect(makeClient(fetch).rulings).toBeUndefined();
  });
});

describe('POST /cards/add — failures', () => {
  it('applies the good lines and reports the bad ones', async () => {
    const r = await post('/cards/add', {
      list: [
        '1 Lightning Bolt (2X2) 117',
        '1 Yavimaya, Cradle of Growth (M3C) 409',
        '1 Not A Real Card At All',
        '1 Misty Rainforest (MH2) 250',
      ].join('\n'),
    }, stubScryfall());

    expect(r.body.applied).toBe(true);
    expect(r.body.resolved).toBe(3);
    expect(r.body.failed).toBe(1);
    expect(r.body.errors[0]).toMatch(/Not A Real Card/);
    expect(await count('cards', "owner_id = 1 AND name_norm='lightning bolt'")).toBe(1);
  });

  it('a Scryfall outage leaves the database untouched', async () => {
    const before = await snapshot();
    const r = await post('/cards/add', { list: BOLT }, stubScryfall({ fail: true }));
    expect(r.status).toBe(502);
    expect(r.body.applied).toBe(false);
    expect(await snapshot()).toEqual(before);
  });

  it('a Scryfall 500 leaves the database untouched', async () => {
    const before = await snapshot();
    const r = await post('/cards/add', { list: BOLT }, stubScryfall({ status: 500 }));
    expect(r.status).toBe(502);
    expect(await snapshot()).toEqual(before);
  });

  it('an empty list is a 400', async () => {
    const r = await post('/cards/add', { list: '' }, stubScryfall());
    expect(r.status).toBe(400);
  });

  it('refuses a finish the printing was never sold in', async () => {
    // Sol Ring in the recorded FRC printing is nonfoil only.
    const r = await post('/cards/add', { list: '1 Sol Ring foil' }, stubScryfall());
    expect(r.body.failed).toBe(1);
    expect(r.body.errors[0]).toMatch(/no foil printing/);
  });
});

describe('POST /cards/add — owners', () => {
  it("adding for kayla does not touch matt's collection", async () => {
    const mattBefore = await count('cards', "owner_id = 1");
    await post('/cards/add', { collection: KAYLA, list: BOLT }, stubScryfall());

    expect(await count('cards', "owner_id = 1")).toBe(mattBefore);
    const rows = await sql("SELECT owner_id, qty FROM cards WHERE name_norm = 'lightning bolt'");
    expect(rows).toEqual([{ owner_id: 3, qty: 1 }]);
  });

  it('the same printing can be owned by both people independently', async () => {
    await post('/cards/add', { list: '2 Lightning Bolt (2X2) 117' }, stubScryfall());
    await post('/cards/add', { collection: KAYLA, list: '3 Lightning Bolt (2X2) 117' }, stubScryfall());
    const rows = await sql("SELECT owner_id, qty FROM cards WHERE name_norm='lightning bolt' ORDER BY owner_id");
    expect(rows).toEqual([{ owner_id: 1, qty: 2 }, { owner_id: 3, qty: 3 }]);
  });

  it('an owner name in the body is refused rather than read', async () => {
    // It used to be lowercased and trusted. Ownership is an id now, and
    // a name in the body is not one.
    const before = await snapshot();
    const r = await post('/cards/add', { owner: 'MATT', list: BOLT }, stubScryfall());
    expect(r.status).toBe(400);
    expect(await snapshot()).toEqual(before);
  });
});

describe('POST /cards/add — batching', () => {
  it('splits more than 75 identifiers across multiple Scryfall calls', async () => {
    const stub = stubScryfall();
    // 80 distinct lines; most will not resolve against the stub, which is
    // fine — the assertion is about how many calls went out.
    const list = Array.from({ length: 80 }, (_, i) => `1 Filler Card ${i}`).join('\n');
    await post('/cards/add', { list }, stub);
    expect(stub.calls.collection).toBe(2);
    expect(stub.calls.identifiers).toHaveLength(80);
  });

  // This used to assert that 1,001 lines were refused outright. Matt:
  // "THERE IS NO FUCKING LIMIT." He is right — the cap was a number from
  // the first commit with nothing written beside it, and what it hid was
  // `SQLITE_TOOBIG` from the id lookups. See test/import-size.test.js.
  it('does not refuse a long list for being long', async () => {
    const list = Array.from({ length: 1001 }, (_, i) => `1 Card ${i}`).join('\n');
    const r = await post('/cards/add', { list }, stubScryfall());
    expect(r.status, 'a long list must not be refused for its length').not.toBe(400);
  });
});

describe('POST /cards/add — large uploads', () => {
  it('handles more distinct printings than D1 allows bound parameters', { timeout: 30000 }, async () => {
    const stub = stubScryfall();
    const many = Array.from({ length: 250 }, (_, i) => ({
      id: `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`,
      name: `Bulk Card ${i}`,
      set: 'tst',
      collector_number: String(i),
      type_line: 'Artifact',
      oracle_id: `11111111-0000-4000-8000-${String(i).padStart(12, '0')}`,
      finishes: ['nonfoil'],
      colors: [], color_identity: [], keywords: [], games: ['paper'],
      legalities: { commander: 'legal' },
      prices: { usd: '0.10' },
    }));
    const byName = new Map(many.map((c) => [c.name.toLowerCase(), c]));
    const wide = async (url, init) => {
      const u = new URL(url);
      if (u.pathname === '/cards/collection') {
        const { identifiers } = JSON.parse(init.body);
        return Response.json({
          data: identifiers.map((i) => byName.get((i.name || '').toLowerCase())).filter(Boolean),
          not_found: [],
        });
      }
      return stub(url, init);
    };

    const list = many.map((c) => `1 ${c.name}`).join('\n');
    const r = await post('/cards/add', { list }, wide);

    expect(r.status).toBe(200);
    expect(r.body.applied).toBe(true);
    expect(r.body.failed).toBe(0);
    expect(r.body.resolved).toBe(250);
    expect(await count('cards', "setcode = 'tst'")).toBe(250);
  });

  it('a second pass over the same large list increments rather than duplicating', { timeout: 30000 }, async () => {
    // This is the path that reads existing stacks back, which is where the
    // parameter limit actually bit.
    const stub = stubScryfall();
    const many = Array.from({ length: 150 }, (_, i) => ({
      id: `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`,
      name: `Bulk Card ${i}`,
      set: 'tst', collector_number: String(i), type_line: 'Artifact',
      oracle_id: `11111111-0000-4000-8000-${String(i).padStart(12, '0')}`,
      finishes: ['nonfoil'], colors: [], color_identity: [], keywords: [],
      games: ['paper'], legalities: {}, prices: {},
    }));
    const byName = new Map(many.map((c) => [c.name.toLowerCase(), c]));
    const wide = async (url, init) => {
      const u = new URL(url);
      if (u.pathname === '/cards/collection') {
        const { identifiers } = JSON.parse(init.body);
        return Response.json({
          data: identifiers.map((i) => byName.get((i.name || '').toLowerCase())).filter(Boolean),
          not_found: [],
        });
      }
      return stub(url, init);
    };
    const list = many.map((c) => `1 ${c.name}`).join('\n');

    await post('/cards/add', { list }, wide);
    await post('/cards/add', { list }, wide);

    expect(await count('cards', "setcode = 'tst'")).toBe(150);
    const qtys = await sql("SELECT DISTINCT qty FROM cards WHERE setcode = 'tst'");
    expect(qtys).toEqual([{ qty: 2 }]);
  });
});

// Rows go in many at a time, not one statement each.
//
// Matt, importing 1,099 cards: "The insertion algorithm should not require
// 10,000 fucking statements!!!" He was right. Every row was its own
// INSERT — nine a card — and 1,100 cards came to 12,100 statements across
// 25 batches, every one of them a round trip inside the request his phone
// was waiting on.
//
// SQLite takes `INSERT INTO t VALUES (...),(...),(...)`. The only ceiling
// is D1's 100 bound parameters per query, so a row costing p parameters
// packs floor(100/p) to a statement. Same import, same rows: 4,185
// statements in 9 batches.
describe('POST /cards/add — rows are batched into statements', () => {
  const many = (n) => Array.from({ length: n }, (_, i) => ({
    id: `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`,
    name: `Packed Card ${i}`, set: 'tst', collector_number: String(i),
    type_line: 'Artifact',
    oracle_id: `11111111-0000-4000-8000-${String(i).padStart(12, '0')}`,
    finishes: ['nonfoil'], colors: [], color_identity: [], keywords: [], games: ['paper'],
    legalities: { commander: 'legal', modern: 'legal', legacy: 'legal' },
    prices: { usd: '0.10' },
  }));

  const wideStub = (cards) => {
    const stub = stubScryfall();
    const byName = new Map(cards.map((c) => [c.name.toLowerCase(), c]));
    return async (url, init) => {
      if (new URL(url).pathname === '/cards/collection') {
        const { identifiers } = JSON.parse(init.body);
        return Response.json({
          data: identifiers.map((i) => byName.get(String(i.name || '').toLowerCase())).filter(Boolean),
          not_found: [],
        });
      }
      return stub(url, init);
    };
  };

  it('spends far fewer statements than it writes rows', { timeout: 120000 }, async () => {
    const cards = many(200);
    let stmts = 0;
    const real = env.DB.batch.bind(env.DB);
    env.DB.batch = (arr) => { stmts += arr.length; return real(arr); };
    try {
      const r = await post('/cards/add', { list: cards.map((c) => `1 ${c.name}`).join('\n') }, wideStub(cards));
      expect(r.body.applied).toBe(true);
      expect(await count('cards', "setcode = 'tst'")).toBe(200);
      // One statement per card would be 200 on its own, and every card
      // writes a card row, three legalities, a price, a search row and
      // more. Unbatched this import is 2,200; the cap is deliberately far
      // below that and far above what batching actually produces (760),
      // so it fails on a regression rather than on a tweak.
      expect(stmts, `${stmts} statements for 200 cards`).toBeLessThan(1200);
    } finally {
      env.DB.batch = real;
    }
  });

  // The budget is not negotiable: D1 refuses a query with more than 100
  // bound parameters, and test/setup.js throws on one. A grouper that
  // packed by row count rather than parameter count would sail past it.
  it('never exceeds D1\'s bound parameter limit while packing', { timeout: 120000 }, async () => {
    const cards = many(120);
    const r = await post('/cards/add', { list: cards.map((c) => `1 ${c.name}`).join('\n') }, wideStub(cards));
    expect(r.status, r.body?.error).toBe(200);
    expect(r.body.applied).toBe(true);
  });
});
