import { describe, it, expect } from 'vitest';
import { get, post, sql, count, stubScryfall } from './helpers.js';

const TABLES = [
  'aliases', 'card_faces', 'card_colors', 'card_finishes', 'card_frame_effects',
  'card_games', 'card_keywords', 'card_promo_types', 'card_search', 'card_tags',
  'card_types', 'cards', 'deck_cards', 'deck_notes', 'decks', 'idempotency', 'legalities',
  'identities', 'logs', 'maintenance_log', 'prices', 'rulings', 'sessions', 'tags', 'users',
];

const VIEWS = ['bulk_cards', 'card_prices', 'card_usage', 'deck_conflicts',
  'deck_gaps', 'decks_not_built', 'totals'];

describe('GET /schema', () => {
  it('lists every table', async () => {
    const r = await get('/schema');
    expect(r.status).toBe(200);
    expect(r.body.tables.map((t) => t.name).sort()).toEqual([...TABLES].sort());
  });

  it('lists every view', async () => {
    const r = await get('/schema');
    expect(r.body.views.map((v) => v.name).sort()).toEqual([...VIEWS].sort());
  });

  it('hides the FTS shadow tables and sqlite internals', async () => {
    const r = await get('/schema');
    const names = [...r.body.tables, ...r.body.views].map((t) => t.name);
    for (const n of names) {
      expect(n).not.toMatch(/^card_search_/);
      expect(n).not.toMatch(/^sqlite_/);
      expect(n).not.toMatch(/^(_cf_|d1_)/);
    }
  });

  it('gives real column names and types', async () => {
    const r = await get('/schema');
    const cards = r.body.tables.find((t) => t.name === 'cards');
    expect(cards.columns).toContain('name_norm:text');
    expect(cards.columns).toContain('cmc:real');
    expect(cards.columns).toContain('qty:integer');
  });

  it('row counts match the database', async () => {
    const r = await get('/schema');
    for (const t of [...r.body.tables, ...r.body.views]) {
      // `logs` cannot match: reading the schema logs the read, after the
      // response has already reported the count.
      if (t.name === 'logs') continue;
      expect(t.rows, `${t.name} row count`).toBe(await count(t.name));
    }
  });

  it('names the indexes on each table', async () => {
    const r = await get('/schema');
    expect(r.body.indexes.cards).toContain('idx_cards_norm');
    expect(r.body.indexes.cards).toContain('idx_cards_unique');
  });

  it('every view it advertises actually executes', async () => {
    const r = await get('/schema');
    for (const v of r.body.views) {
      const q = await post('/query', { sql: `SELECT * FROM ${v.name} LIMIT 1` });
      expect(q.status, `${v.name} failed to execute`).toBe(200);
    }
  });

  it('carries the join-key notes an agent needs', async () => {
    const r = await get('/schema');
    const notes = r.body.notes.join(' ');
    expect(notes).toMatch(/name_norm/);
    expect(notes).toMatch(/oracle_id/);
    expect(notes).toMatch(/owner/);
    expect(notes).toMatch(/card_search/);
  });

  it('stays inside its token budget', async () => {
    const r = await get('/schema');
    // Roughly 4 characters per token; the point is that an agent can afford
    // to fetch this before asking its real question.
    expect(r.text.length).toBeLessThan(12000);
  });

  it('follows the data rather than caching a stale count', async () => {
    const before = (await get('/schema')).body.tables.find((t) => t.name === 'cards').rows;
    await post('/cards/add', { list: '1 Lightning Bolt (2X2) 117' }, stubScryfall());
    const after = (await get('/schema')).body.tables.find((t) => t.name === 'cards').rows;
    expect(after).toBe(before + 1);
  });

  it('rejects the wrong method', async () => {
    const r = await post('/schema', {});
    expect(r.status).toBe(405);
  });
});

describe('routing', () => {
  it('the index lists the endpoints', async () => {
    const r = await get('/');
    expect(r.body.service).toBe('mtg-api');
    expect(Object.keys(r.body.endpoints)).toContain('POST /query');
  });

  it('a trailing slash is the same route', async () => {
    expect((await get('/schema/')).status).toBe(200);
  });

  it('an unknown path is a 404 that says what does exist', async () => {
    const r = await get('/nope');
    expect(r.status).toBe(404);
    expect(r.body.see).toBeTruthy();
  });

  it('GET on a POST-only route is a 405', async () => {
    expect((await get('/cards/add')).status).toBe(405);
    expect((await get('/cards/remove')).status).toBe(405);
  });

  it('the index advertises the GET form of /query', async () => {
    const r = await get('/');
    expect(Object.keys(r.body.endpoints)).toContain('GET /query');
  });

  it('sends CORS headers', async () => {
    const r = await get('/');
    expect(r.headers.get('access-control-allow-origin')).toBe('*');
  });
});

describe('the seeded fixture', () => {
  it('has both owners, a DFC, a non-nonfoil finish, and decks', async () => {
    expect(await count('cards', "owner_id = 1")).toBeGreaterThan(0);
    expect(await count('cards', "owner_id = 3")).toBeGreaterThan(0);
    expect(await count('cards', 'face2 IS NOT NULL')).toBeGreaterThan(0);
    expect(await count('cards', "name LIKE '%''%'")).toBeGreaterThan(0);
    expect(await count('decks')).toBeGreaterThan(1);
    expect(await count('deck_cards')).toBeGreaterThan(0);
    expect(await count('card_tags')).toBeGreaterThan(0);
  });

  it('every card has an FTS row', async () => {
    const orphans = await sql(
      'SELECT COUNT(*) AS n FROM cards c WHERE NOT EXISTS (SELECT 1 FROM card_search s WHERE s.rowid = c.id)',
    );
    expect(orphans[0].n).toBe(0);
  });
});
