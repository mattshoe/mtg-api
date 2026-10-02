import { describe, it, expect } from 'vitest';
import { post, postAnon, get } from './helpers.js';

// The slug is what the address bar shows. A deck called one thing
// living at the address of another is a link that lies, so the slug
// moves with the name. `deck_cards` hangs off `decks.id`, so the cards
// come along without being touched.

const anyDeck = async () => {
  const r = await get('/query?sql=' + encodeURIComponent('SELECT slug, name FROM decks LIMIT 1'));
  return { slug: r.body.rows[0][0], name: r.body.rows[0][1] };
};

const deckAt = async (slug) => {
  const r = await post('/query', {
    sql: 'SELECT slug, name FROM decks WHERE slug = ?',
    params: [slug],
  });
  return r.body.rows[0];
};

describe('POST /decks/rename', () => {
  it('needs a token', async () => {
    const d = await anyDeck();
    expect((await postAnon('/decks/rename', { slug: d.slug, name: 'Nope' })).status).toBe(401);
  });

  it('rejects GET', async () => {
    expect((await get('/decks/rename')).status).toBe(405);
  });

  it('wants a slug and a name', async () => {
    expect((await post('/decks/rename', { name: 'x' })).body.error).toMatch(/slug/);
    expect((await post('/decks/rename', { slug: 'x' })).body.error).toMatch(/needs a name/);
  });

  it('says so when the deck does not exist', async () => {
    const r = await post('/decks/rename', { slug: 'no-such-deck', name: 'Whatever' });
    expect(r.status).toBe(404);
    expect(r.body.error).toMatch(/no deck called/);
  });

  it('refuses a name with nothing in it to make a slug from', async () => {
    const d = await anyDeck();
    const r = await post('/decks/rename', { slug: d.slug, name: '!!!' });
    expect(r.status).toBe(400);
    expect(r.body.error).toMatch(/letters or digits/);
  });

  it('refuses a name longer than anything sensible', async () => {
    const d = await anyDeck();
    const r = await post('/decks/rename', { slug: d.slug, name: 'x'.repeat(200) });
    expect(r.status).toBe(400);
  });

  it('moves the name and the slug together', async () => {
    const d = await anyDeck();
    const r = await post('/decks/rename', { slug: d.slug, name: 'Renamed By A Test' });
    expect(r.status).toBe(200);
    expect(r.body.renamed).toBe(true);
    expect(r.body.slug).toBe('renamed-by-a-test');
    expect(await deckAt('renamed-by-a-test')).toEqual(['renamed-by-a-test', 'Renamed By A Test']);
    expect(await deckAt(d.slug)).toBe(undefined);
  });

  it('keeps the cards, because they hang off the id and not the slug', async () => {
    const d = await anyDeck();
    const before = await post('/query', {
      sql: 'SELECT COUNT(*) FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id WHERE d.slug = ?',
      params: [d.slug],
    });
    await post('/decks/rename', { slug: d.slug, name: 'Still Has Its Cards' });
    const after = await post('/query', {
      sql: 'SELECT COUNT(*) FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id WHERE d.slug = ?',
      params: ['still-has-its-cards'],
    });
    expect(after.body.rows[0][0]).toBe(before.body.rows[0][0]);
  });

  it('will not move a deck on top of another one', async () => {
    const r = await post('/query', { sql: 'SELECT slug FROM decks LIMIT 2' });
    const [a, b] = r.body.rows.map((row) => row[0]);
    // Give the second one a name whose slug is known, then try to take it.
    await post('/decks/rename', { slug: b, name: 'Taken Already' });
    const clash = await post('/decks/rename', { slug: a, name: 'Taken Already' });
    expect(clash.status).toBe(409);
    expect(clash.body.error).toMatch(/already lives at/);
    expect(await deckAt('taken-already')).toEqual(['taken-already', 'Taken Already']);
  });

  it('renaming to the name it already has is a no-op', async () => {
    const d = await anyDeck();
    // Settle it first, so its slug and its name agree.
    await post('/decks/rename', { slug: d.slug, name: 'Settled Name' });
    const again = await post('/decks/rename', { slug: 'settled-name', name: 'Settled Name' });
    expect(again.status).toBe(200);
    expect(again.body.renamed).toBe(false);
    expect(await deckAt('settled-name')).toEqual(['settled-name', 'Settled Name']);
  });

  it('but the same name over a slug that drifted repairs the slug', async () => {
    // Seeded decks can carry a slug that is not what their name makes.
    // Asking for the name they already have is how you straighten that
    // out, so it is a real rename rather than nothing.
    const r = await post('/query', {
      sql: 'SELECT slug, name FROM decks WHERE slug != ? LIMIT 1',
      params: ['settled-name'],
    });
    const [slug, name] = r.body.rows[0];
    const made = name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
    if (made === slug) return;
    const fixed = await post('/decks/rename', { slug, name });
    expect(fixed.body.renamed).toBe(true);
    expect(fixed.body.slug).toBe(made);
  });

  it('a dry run reports the new slug and writes nothing', async () => {
    const d = await anyDeck();
    const r = await post('/decks/rename', { slug: d.slug, name: 'Only Pretending', dry_run: true });
    expect(r.body.renamed).toBe(false);
    expect(r.body.slug).toBe('only-pretending');
    expect(await deckAt(d.slug)).toEqual([d.slug, d.name]);
    expect(await deckAt('only-pretending')).toBe(undefined);
  });

  it('trims the name rather than storing the spaces', async () => {
    const d = await anyDeck();
    const r = await post('/decks/rename', { slug: d.slug, name: '  Spaced Out  ' });
    expect(r.body.name).toBe('Spaced Out');
    expect(await deckAt('spaced-out')).toEqual(['spaced-out', 'Spaced Out']);
  });

  it('is listed among the endpoints', async () => {
    const r = await get('/nope');
    expect(JSON.stringify(r.body)).toMatch(/decks\/rename/);
  });
});
