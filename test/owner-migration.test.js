import { describe, it, expect, beforeEach } from 'vitest';
import { env } from 'cloudflare:test';
import { sql, exec } from './helpers.js';
import before from './fixtures/schema-before-0007.sql?raw';
import migration from '../migrations/0007_owner_is_an_id.sql?raw';

/**
 * `0007_owner_is_an_id.sql`, run against the database it was written
 * for: the schema as it stood before it, with owners as names.
 *
 * Every other test builds from today's `schema.sql`, which already has
 * `owner_id`, so none of them can say whether the live database gets
 * there. This one throws today's schema away, lays down the old one,
 * and runs the migration over rows shaped like production's.
 */

/** Statements in a .sql file. Comments go first: they contain semicolons. */
const statements = (text) => text
  .split('\n').map((l) => l.replace(/--.*$/, '')).join('\n')
  .split(';').map((s) => s.trim()).filter(Boolean);

async function rebuildAsBefore() {
  const objects = await sql(
    `SELECT name, type FROM sqlite_master
      WHERE type IN ('view', 'table') AND name NOT LIKE 'sqlite_%'
        AND name NOT LIKE '_cf_%' AND name NOT LIKE 'd1_%'
        AND name NOT LIKE 'card_search_%'`,
  );
  // Views first, then whatever references a table, then users last.
  const children = ['deck_cards', 'deck_notes', 'identities', 'sessions'];
  const order = (o) => (o.type === 'view' ? 0 : children.includes(o.name) ? 1 : o.name === 'users' ? 3 : 2);
  for (const o of objects.sort((a, b) => order(a) - order(b))) {
    await exec(`DROP ${o.type === 'view' ? 'VIEW' : 'TABLE'} IF EXISTS ${o.name}`);
  }
  await env.DB.batch(statements(before).map((s) => env.DB.prepare(s)));
}

const migrate = () => env.DB.batch(statements(migration).map((s) => env.DB.prepare(s)));

describe('0007: owner is an id', () => {
  beforeEach(async () => {
    await rebuildAsBefore();
    // The live shape: Matt at id 1, his test account at 2, Kayla at 3.
    await exec(`INSERT INTO users (id, key, slug, display_name, role, created_at) VALUES
      (1, 'e7de0cb1', 'matt', 'Matt Shoemaker', 'admin', '2026-10-06'),
      (2, 't4pee71g', 'matthew-shoemaker', 'Matthew Shoemaker', 'user', '2026-10-07'),
      (3, 'bprh3d2s', 'kayla', 'Kayla M', 'user', '2026-10-07')`);
    await exec(`INSERT INTO cards (id, owner, qty, scryfall_id, finish, name, name_norm) VALUES
      (1, 'matt', 2, 's1', 'nonfoil', 'Sol Ring', 'sol ring'),
      (2, 'kayla', 1, 's1', 'nonfoil', 'Sol Ring', 'sol ring'),
      (3, 'kayla', 4, 's2', 'foil', 'Lightning Bolt', 'lightning bolt')`);
    await exec(`INSERT INTO decks (id, slug, name, owner, is_proxy) VALUES
      (1, 'milly-moth', 'Milly Moth', 'matt', 0),
      (2, 'lol-proxy', 'LoL Proxy', 'kayla', 1)`);
    await exec(`INSERT INTO deck_cards (deck_id, qty, name, name_norm, in_collection) VALUES
      (1, 1, 'Sol Ring', 'sol ring', 1)`);
    await exec("INSERT INTO tags (slug, kind, label) VALUES ('ramp', 'oracle', 'Ramp')");
    await exec("INSERT INTO card_tags (card_id, tag_slug, kind) VALUES (1, 'ramp', 'oracle')");
  });

  it('stamps every card and deck with the id of the account whose slug it held', async () => {
    await migrate();
    expect(await sql('SELECT id, owner_id FROM cards ORDER BY id'))
      .toEqual([{ id: 1, owner_id: 1 }, { id: 2, owner_id: 3 }, { id: 3, owner_id: 3 }]);
    expect(await sql('SELECT id, owner_id FROM decks ORDER BY id'))
      .toEqual([{ id: 1, owner_id: 1 }, { id: 2, owner_id: 3 }]);
  });

  it('gives every deck a key of its own', async () => {
    await migrate();
    const keys = (await sql('SELECT key FROM decks')).map((r) => r.key);
    expect(keys).toHaveLength(2);
    keys.forEach((k) => expect(k).toMatch(/^[0-9a-f]{8}$/));
    expect(new Set(keys).size).toBe(2);
  });

  it('renames the tag columns and keeps their values', async () => {
    await migrate();
    expect(await sql('SELECT tag, kind FROM card_tags')).toEqual([{ tag: 'ramp', kind: 'oracle' }]);
    expect(await sql('SELECT tag, label FROM tags')).toEqual([{ tag: 'ramp', label: 'Ramp' }]);
  });

  it('the views answer by owner_id afterwards', async () => {
    await migrate();
    expect(await sql('SELECT owner_id, name_norm, total_qty FROM totals ORDER BY owner_id, name_norm')).toEqual([
      { owner_id: 1, name_norm: 'sol ring', total_qty: 2 },
      { owner_id: 3, name_norm: 'lightning bolt', total_qty: 4 },
      { owner_id: 3, name_norm: 'sol ring', total_qty: 1 },
    ]);
    expect(await sql("SELECT owner_id, in_decks, free FROM card_usage WHERE name_norm = 'sol ring' ORDER BY owner_id"))
      .toEqual([{ owner_id: 1, in_decks: 1, free: 1 }, { owner_id: 3, in_decks: 0, free: 1 }]);
  });

  it('one stack per account, printing and finish is still enforced', async () => {
    await migrate();
    await expect(exec(
      "INSERT INTO cards (owner_id, qty, scryfall_id, finish, name, name_norm) VALUES (3, 1, 's1', 'nonfoil', 'Sol Ring', 'sol ring')",
    )).rejects.toThrow(/UNIQUE/);
  });

  it('leaves a name nobody signed in as without an owner rather than guessing', async () => {
    await exec("INSERT INTO cards (id, owner, qty, scryfall_id, finish, name, name_norm) VALUES (9, 'nobody', 1, 's9', 'nonfoil', 'X', 'x')");
    await migrate();
    expect(await sql('SELECT owner_id FROM cards WHERE id = 9')).toEqual([{ owner_id: null }]);
  });
});
