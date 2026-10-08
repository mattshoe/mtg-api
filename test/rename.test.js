import { describe, it, expect } from 'vitest';
import { post, postAnon, get } from './helpers.js';

// A deck lives at its key, which is random and never changes, so a
// rename changes the name and nothing else: the link somebody pasted
// still opens the deck. The slug that used to move with the name is
// retired. `deck_cards` hangs off `decks.id`, so the cards come along
// without being touched.

const anyDeck = async () => {
  const r = await get('/query?sql=' + encodeURIComponent('SELECT key, name FROM decks LIMIT 1'));
  return { key: r.body.rows[0][0], name: r.body.rows[0][1] };
};

const deckAt = async (key) => {
  const r = await post('/query', {
    sql: 'SELECT key, name FROM decks WHERE key = ?',
    params: [key],
  });
  return r.body.rows[0];
};

describe('POST /decks/rename', () => {
  it('needs a token', async () => {
    const d = await anyDeck();
    expect((await postAnon('/decks/rename', { key: d.key, name: 'Nope' })).status).toBe(401);
  });

  it('rejects GET', async () => {
    expect((await get('/decks/rename')).status).toBe(405);
  });

  it('wants a key and a name', async () => {
    expect((await post('/decks/rename', { name: 'x' })).body.error).toMatch(/key/);
    const d = await anyDeck();
    expect((await post('/decks/rename', { key: d.key })).body.error).toMatch(/needs a name/);
    expect((await post('/decks/rename', { key: d.key, name: '   ' })).body.error).toMatch(/needs a name/);
  });

  it('says so when the deck does not exist', async () => {
    const r = await post('/decks/rename', { key: 'zzzzzzzz', name: 'Whatever' });
    expect(r.status).toBe(404);
    expect(r.body.error).toMatch(/no deck with key/);
  });

  it('takes a name with no letters in it, because nothing is built from the name any more', async () => {
    const d = await anyDeck();
    const r = await post('/decks/rename', { key: d.key, name: '!!!' });
    expect(r.status).toBe(200);
    expect(await deckAt(d.key)).toEqual([d.key, '!!!']);
  });

  it('refuses a name longer than anything sensible', async () => {
    const d = await anyDeck();
    const r = await post('/decks/rename', { key: d.key, name: 'x'.repeat(200) });
    expect(r.status).toBe(400);
    expect(await deckAt(d.key)).toEqual([d.key, d.name]);
  });

  it('changes the name and keeps the address', async () => {
    const d = await anyDeck();
    const r = await post('/decks/rename', { key: d.key, name: 'Renamed By A Test' });
    expect(r.status).toBe(200);
    expect(r.body.renamed).toBe(true);
    expect(r.body.key).toBe(d.key);
    expect(r.body).not.toHaveProperty('slug');
    expect(await deckAt(d.key)).toEqual([d.key, 'Renamed By A Test']);
  });

  it('keeps the cards, because they hang off the id and not the name', async () => {
    const d = await anyDeck();
    const cards = () => post('/query', {
      sql: 'SELECT COUNT(*) FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id WHERE d.key = ?',
      params: [d.key],
    });
    const before = await cards();
    expect(before.body.rows[0][0]).toBeGreaterThan(0);
    await post('/decks/rename', { key: d.key, name: 'Still Has Its Cards' });
    const after = await cards();
    expect(after.body.rows[0][0]).toBe(before.body.rows[0][0]);
  });

  it('two decks may share a name, because the name is not the address', async () => {
    const r = await post('/query', { sql: 'SELECT key FROM decks LIMIT 2' });
    const [a, b] = r.body.rows.map((row) => row[0]);
    expect((await post('/decks/rename', { key: b, name: 'Taken Already' })).status).toBe(200);
    const same = await post('/decks/rename', { key: a, name: 'Taken Already' });
    expect(same.status).toBe(200);
    expect(await deckAt(a)).toEqual([a, 'Taken Already']);
    expect(await deckAt(b)).toEqual([b, 'Taken Already']);
  });

  it('renaming to the name it already has is a no-op', async () => {
    const d = await anyDeck();
    await post('/decks/rename', { key: d.key, name: 'Settled Name' });
    const again = await post('/decks/rename', { key: d.key, name: 'Settled Name' });
    expect(again.status).toBe(200);
    expect(again.body.renamed).toBe(false);
    expect(await deckAt(d.key)).toEqual([d.key, 'Settled Name']);
  });

  it('and so is a seeded deck asked for the name it was seeded with', async () => {
    // There used to be a slug here that could drift from the name, and
    // asking for the same name repaired it. With nothing derived from
    // the name there is nothing to repair, so it is simply nothing.
    const d = await anyDeck();
    const r = await post('/decks/rename', { key: d.key, name: d.name });
    expect(r.status).toBe(200);
    expect(r.body.renamed).toBe(false);
    expect(await deckAt(d.key)).toEqual([d.key, d.name]);
  });

  it('a dry run reports the new name and writes nothing', async () => {
    const d = await anyDeck();
    const r = await post('/decks/rename', { key: d.key, name: 'Only Pretending', dry_run: true });
    expect(r.body.renamed).toBe(false);
    expect(r.body.key).toBe(d.key);
    expect(r.body.name).toBe('Only Pretending');
    expect(await deckAt(d.key)).toEqual([d.key, d.name]);
  });

  it('trims the name rather than storing the spaces', async () => {
    const d = await anyDeck();
    const r = await post('/decks/rename', { key: d.key, name: '  Spaced Out  ' });
    expect(r.body.name).toBe('Spaced Out');
    expect(await deckAt(d.key)).toEqual([d.key, 'Spaced Out']);
  });

  it('is listed among the endpoints', async () => {
    const r = await get('/nope');
    expect(JSON.stringify(r.body)).toMatch(/decks\/rename/);
  });
});
