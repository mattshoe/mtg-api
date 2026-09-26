import { describe, it, expect } from 'vitest';
import { post, postAnon, get, sql, count, snapshot } from './helpers.js';

// Deck 1 in the fixture is 'chaos-incarnate-precon', matt's, with five
// deck_cards rows all in_collection = 1.
const SLUG = 'chaos-incarnate-precon';

/** What card_usage says about a card, for the owner that fixture uses. */
async function usage(nameNorm, owner = 'matt') {
  const [row] = await sql(
    'SELECT owned, in_decks, free FROM card_usage WHERE name_norm = ? AND owner = ?',
    nameNorm, owner,
  );
  return row;
}

describe('POST /decks/disassemble — the gate', () => {
  it('refuses without a token, and changes nothing', async () => {
    const before = await snapshot();
    const r = await postAnon('/decks/disassemble', { slug: SLUG });
    expect(r.status).toBe(401);
    expect(r.body.admin_required).toBe(true);
    expect(await snapshot()).toEqual(before);
  });

  it('refuses a dry run without a token too', async () => {
    const r = await postAnon('/decks/disassemble', { slug: SLUG, dry_run: true });
    expect(r.status).toBe(401);
  });

  it('rejects GET', async () => {
    const before = await snapshot();
    expect((await get('/decks/disassemble')).status).toBe(405);
    expect(await snapshot()).toEqual(before);
  });
});

describe('POST /decks/disassemble — bad input', () => {
  it('needs a slug', async () => {
    const before = await snapshot();
    const r = await post('/decks/disassemble', {});
    expect(r.status).toBe(400);
    expect(r.body.error).toMatch(/slug/);
    expect(await snapshot()).toEqual(before);
  });

  it('404s on a slug that does not exist, and changes nothing', async () => {
    const before = await snapshot();
    const r = await post('/decks/disassemble', { slug: 'no-such-deck' });
    expect(r.status).toBe(404);
    expect(r.body.error).toMatch(/no-such-deck/);
    expect(await snapshot()).toEqual(before);
  });
});

describe('POST /decks/disassemble — dry run', () => {
  it('reports what would be freed and writes nothing', async () => {
    const before = await snapshot();
    const r = await post('/decks/disassemble', { slug: SLUG, dry_run: true });

    expect(r.status).toBe(200);
    expect(r.body.applied).toBe(false);
    expect(r.body.dry_run).toBe(true);
    expect(r.body.deck).toMatchObject({ slug: SLUG, owner: 'matt' });
    expect(r.body.freed).toBeGreaterThan(0);
    expect(r.body.cards.length).toBeGreaterThan(0);

    expect(await snapshot()).toEqual(before);
  });

  it('counts what the deck holds, not what it lists', async () => {
    // The proxy deck lists far more than it owns: most of its rows are
    // gaps (in_collection = 0) and a gap was never holding anything.
    const slug = 'halo-proxy-astor-equipment';
    const listed = await count('deck_cards', 'deck_id = (SELECT id FROM decks WHERE slug = ?)', slug);
    const owned = await count(
      'deck_cards', 'deck_id = (SELECT id FROM decks WHERE slug = ?) AND in_collection = 1', slug,
    );
    expect(owned).toBeLessThan(listed);

    const r = await post('/decks/disassemble', { slug, dry_run: true });
    expect(r.body.rows.deck_cards).toBe(listed);
    expect(r.body.freed).toBe(owned);
    expect(r.body.cards).toHaveLength(owned);
  });
});

describe('POST /decks/disassemble — applied', () => {
  it('deletes the deck, its cards and its notes', async () => {
    const deckCards = await count('deck_cards', 'deck_id = (SELECT id FROM decks WHERE slug = ?)', SLUG);
    expect(deckCards).toBeGreaterThan(0);

    const r = await post('/decks/disassemble', { slug: SLUG });
    expect(r.status).toBe(200);
    expect(r.body.applied).toBe(true);
    expect(r.body.rows.deck_cards).toBe(deckCards);

    expect(await count('decks', 'slug = ?', SLUG)).toBe(0);
    // Nothing left pointing at the deck that is gone.
    expect(await count('deck_cards', 'deck_id NOT IN (SELECT id FROM decks)')).toBe(0);
    expect(await count('deck_notes', 'deck_id NOT IN (SELECT id FROM decks)')).toBe(0);
  });

  it('frees the cards it was holding, which is the whole point', async () => {
    // Ambition's Cost is only in this deck: it should go entirely free.
    // Arcane Signet is in this one and another, so it proves only this
    // deck's claim is released and not the other's.
    const costBefore = await usage("ambition's cost");
    const signetBefore = await usage('arcane signet');
    expect(costBefore.in_decks).toBe(1);
    expect(signetBefore.in_decks).toBeGreaterThanOrEqual(2);

    await post('/decks/disassemble', { slug: SLUG });

    const costAfter = await usage("ambition's cost");
    expect(costAfter.owned).toBe(costBefore.owned);       // nothing left the collection
    expect(costAfter.in_decks).toBe(0);
    expect(costAfter.free).toBe(costBefore.owned);

    const signetAfter = await usage('arcane signet');
    expect(signetAfter.owned).toBe(signetBefore.owned);
    expect(signetAfter.in_decks).toBe(signetBefore.in_decks - 1);
    expect(signetAfter.free).toBe(signetBefore.free + 1);
  });

  it('takes nothing out of the collection', async () => {
    const cards = await count('cards');
    const copies = (await sql('SELECT SUM(qty) AS n FROM cards'))[0].n;

    await post('/decks/disassemble', { slug: SLUG });

    expect(await count('cards')).toBe(cards);
    expect((await sql('SELECT SUM(qty) AS n FROM cards'))[0].n).toBe(copies);
  });

  it('leaves every other deck alone', async () => {
    const others = await sql('SELECT slug, card_count FROM decks WHERE slug != ? ORDER BY slug', SLUG);
    const rowsPerDeck = await sql(
      'SELECT deck_id, COUNT(*) AS n FROM deck_cards WHERE deck_id != (SELECT id FROM decks WHERE slug = ?) GROUP BY deck_id ORDER BY deck_id',
      SLUG,
    );

    await post('/decks/disassemble', { slug: SLUG });

    expect(await sql('SELECT slug, card_count FROM decks ORDER BY slug')).toEqual(others);
    expect(await sql('SELECT deck_id, COUNT(*) AS n FROM deck_cards GROUP BY deck_id ORDER BY deck_id'))
      .toEqual(rowsPerDeck);
  });

  it('a second disassemble of the same deck is a 404, not a second delete', async () => {
    await post('/decks/disassemble', { slug: SLUG });
    const before = await snapshot();
    const r = await post('/decks/disassemble', { slug: SLUG });
    expect(r.status).toBe(404);
    expect(await snapshot()).toEqual(before);
  });

  it('drops the deck out of the views that listed it', async () => {
    const gapsBefore = await count('deck_gaps', 'slug = ?', SLUG);
    await post('/decks/disassemble', { slug: 'halo-proxy-astor-equipment' });
    expect(await count('deck_gaps', 'slug = ?', 'halo-proxy-astor-equipment')).toBe(0);
    expect(await count('decks_not_built', 'slug = ?', 'halo-proxy-astor-equipment')).toBe(0);
    // and did not disturb the other deck's gaps
    expect(await count('deck_gaps', 'slug = ?', SLUG)).toBe(gapsBefore);
  });
});
