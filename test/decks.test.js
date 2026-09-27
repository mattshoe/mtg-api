import { describe, it, expect } from 'vitest';
import { post, postAnon, get, sql, count, snapshot, stubScryfall } from './helpers.js';

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

// --------------------------------------------------------------- editing

/** The deck's list as a decklist, the way the UI hands it back. */
async function listOf(slug) {
  const rows = await sql(
    `SELECT dc.qty, dc.name FROM deck_cards dc
       JOIN decks d ON d.id = dc.deck_id WHERE d.slug = ? ORDER BY dc.name`, slug,
  );
  return rows.map((r) => `${r.qty} ${r.name}`).join('\n');
}

describe('POST /decks/list — the gate and bad input', () => {
  it('refuses without a token', async () => {
    const before = await snapshot();
    const r = await postAnon('/decks/list', { slug: SLUG, list: '1 Sol Ring' });
    expect(r.status).toBe(401);
    expect(await snapshot()).toEqual(before);
  });

  it('rejects GET', async () => {
    expect((await get('/decks/list')).status).toBe(405);
  });

  it('needs a slug and a list', async () => {
    expect((await post('/decks/list', { list: '1 Sol Ring' })).body.error).toMatch(/slug/);
    expect((await post('/decks/list', { slug: SLUG })).body.error).toMatch(/list must be a string/);
  });

  it('404s on an unknown deck', async () => {
    const r = await post('/decks/list', { slug: 'nope', list: '1 Sol Ring' });
    expect(r.status).toBe(404);
  });

  it('refuses the whole list when a line cannot be read, rather than dropping it', async () => {
    const before = await listOf(SLUG);
    const snap = await snapshot();
    const r = await post('/decks/list', { slug: SLUG, list: '1 Sol Ring\n0 Lightning Bolt' });
    expect(r.status).toBe(400);
    expect(r.body.errors).toHaveLength(1);
    expect(await listOf(SLUG)).toBe(before);
    expect(await snapshot()).toEqual(snap);
  });

  it('refuses an empty list instead of emptying the deck', async () => {
    const before = await listOf(SLUG);
    const r = await post('/decks/list', { slug: SLUG, list: '   \n# nothing\n' });
    expect(r.status).toBe(400);
    expect(r.body.error).toMatch(/empty/);
    expect(await listOf(SLUG)).toBe(before);
  });
});

describe('POST /decks/list — dry run', () => {
  it('reports the diff and writes nothing', async () => {
    const snap = await snapshot();
    const before = await listOf(SLUG);

    const r = await post('/decks/list', {
      slug: SLUG,
      list: "1 Sol Ring\n2 Arcane Signet\n1 Lightning Bolt",
      dry_run: true,
    });

    expect(r.status).toBe(200);
    expect(r.body.applied).toBe(false);
    expect(r.body.rows).toBe(3);
    expect(r.body.card_count).toBe(4);
    expect(r.body.added.map((a) => a[0])).toContain('Lightning Bolt');
    expect(r.body.removed.map((a) => a[0])).toContain("Ambition's Cost");
    expect(r.body.changed).toContainEqual(['Arcane Signet', 1, 2]);

    expect(await listOf(SLUG)).toBe(before);
    expect(await snapshot()).toEqual(snap);
  });
});

describe('POST /decks/list — applied', () => {
  it('replaces the list, and the deck is exactly what was sent', async () => {
    const r = await post('/decks/list', {
      slug: SLUG,
      list: '1 Sol Ring\n3 Arcane Signet',
    });
    expect(r.status).toBe(200);
    expect(r.body.applied).toBe(true);

    const rows = await sql(
      `SELECT dc.qty, dc.name FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
        WHERE d.slug = ? ORDER BY dc.name`, SLUG,
    );
    expect(rows).toEqual([
      { qty: 3, name: 'Arcane Signet' },
      { qty: 1, name: 'Sol Ring' },
    ]);
  });

  it('keeps card_count and owned_count in step with the list', async () => {
    // Everything a real deck lists ends up backed, so the two agree.
    await post('/decks/list', { slug: SLUG, list: '4 Sol Ring\n2 Lightning Bolt' }, stubScryfall());
    const [deck] = await sql('SELECT card_count, owned_count FROM decks WHERE slug = ?', SLUG);
    expect(deck.card_count).toBe(6);
    expect(deck.owned_count).toBe(6);
  });

  it('leaves a real deck with no gaps at all, because it buys what is short', async () => {
    await post('/decks/list', { slug: SLUG, list: '1 Sol Ring\n1 Lightning Bolt' }, stubScryfall());
    const rows = await sql(
      `SELECT dc.name, dc.in_collection FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
        WHERE d.slug = ? ORDER BY dc.name`, SLUG,
    );
    expect(rows).toEqual([
      { name: 'Lightning Bolt', in_collection: 1 },
      { name: 'Sol Ring', in_collection: 1 },
    ]);
    expect(await count('deck_gaps', 'slug = ?', SLUG)).toBe(0);
  });

  it('adds up a card written on two lines', async () => {
    const r = await post('/decks/list', { slug: SLUG, list: '1 Sol Ring\n2 Sol Ring' });
    expect(r.body.rows).toBe(1);
    const [row] = await sql(
      `SELECT dc.qty FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id WHERE d.slug = ?`, SLUG,
    );
    expect(row.qty).toBe(3);
  });

  it('marks the commander, and lands as lands', async () => {
    // Kardur is deck 1's commander per the fixture.
    await post('/decks/list', { slug: SLUG, list: '1 Kardur, Doomscourge\n1 Arcane Signet' });
    const rows = await sql(
      `SELECT dc.name, dc.role FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
        WHERE d.slug = ? ORDER BY dc.name`, SLUG,
    );
    expect(rows.find((r) => r.name.startsWith('Kardur'))?.role).toBe('commander');
    expect(rows.find((r) => r.name === 'Arcane Signet')?.role).toBe('spell');
  });

  it('frees what it dropped and claims what it added', async () => {
    const before = await usage("ambition's cost");
    expect(before.in_decks).toBe(1);

    // a list without Ambition's Cost
    await post('/decks/list', { slug: SLUG, list: '1 Arcane Signet' });

    const after = await usage("ambition's cost");
    expect(after.owned).toBe(before.owned);
    expect(after.in_decks).toBe(0);
    expect(after.free).toBe(before.owned);
  });

  it('removes nothing from the collection and leaves other decks alone', async () => {
    // An edit may add to the collection, to back what the deck now wants.
    // It must never take anything out of it, or touch another deck.
    const copies = (await sql('SELECT SUM(qty) AS n FROM cards'))[0].n;
    const otherRows = await sql(
      `SELECT deck_id, COUNT(*) n FROM deck_cards
        WHERE deck_id != (SELECT id FROM decks WHERE slug = ?) GROUP BY deck_id ORDER BY deck_id`, SLUG,
    );

    await post('/decks/list', { slug: SLUG, list: "1 Ambition's Cost" }, stubScryfall());

    expect((await sql('SELECT SUM(qty) AS n FROM cards'))[0].n).toBeGreaterThanOrEqual(copies);
    expect(await sql(
      `SELECT deck_id, COUNT(*) n FROM deck_cards
        WHERE deck_id != (SELECT id FROM decks WHERE slug = ?) GROUP BY deck_id ORDER BY deck_id`, SLUG,
    )).toEqual(otherRows);
  });

  it('survives a round trip: export the list, send it straight back', async () => {
    const before = await listOf(SLUG);
    const r = await post('/decks/list', { slug: SLUG, list: before });
    expect(r.status).toBe(200);
    expect(r.body.added).toEqual([]);
    expect(r.body.removed).toEqual([]);
    expect(r.body.changed).toEqual([]);
    expect(await listOf(SLUG)).toBe(before);
  });

  it('takes a name with an apostrophe without breaking the SQL', async () => {
    const r = await post('/decks/list', { slug: SLUG, list: "2 Ambition's Cost" });
    expect(r.status).toBe(200);
    const rows = await sql(
      `SELECT dc.name, dc.qty FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id WHERE d.slug = ?`, SLUG,
    );
    expect(rows).toEqual([{ name: "Ambition's Cost", qty: 2 }]);
  });
});

describe('POST /decks/list — basic lands', () => {
  it('never calls a basic land a gap, because basics are not tracked', async () => {
    // Nobody owns a Swamp or a Wastes in the fixture, the same way nobody
    // owns any basic in the real collection since they were dropped on
    // purpose. A deck running twenty of them is not short twenty cards.
    expect(await count('cards', "name_norm IN ('swamp','wastes','snow-covered island')")).toBe(0);

    const r = await post('/decks/list', {
      slug: SLUG,
      list: '1 Arcane Signet\n20 Swamp\n4 Snow-Covered Island\n1 Wastes',
    });
    expect(r.status).toBe(200);
    expect(r.body.newly_missing).toEqual([]);
    expect(r.body.card_count).toBe(26);
    expect(r.body.owned_count).toBe(26);

    expect(await count('deck_gaps', 'slug = ?', SLUG)).toBe(0);
  });

  it('still calls a non-basic a gap on a proxy deck, which buys nothing', async () => {
    // A proxy deck is not made of real cards, so an unowned one stays a
    // gap instead of being acquired.
    const slug = 'halo-proxy-astor-equipment';
    const r = await post('/decks/list', { slug, list: '1 Lightning Bolt' }, stubScryfall());
    expect(r.body.acquired).toEqual([]);
    expect(r.body.owned_count).toBe(0);
    expect(await count('deck_gaps', 'slug = ?', slug)).toBe(1);
  });
});

describe('POST /decks/list — the commander field', () => {
  it('makes the named card the commander, whatever the deck said before', async () => {
    const r = await post('/decks/list', {
      slug: SLUG,
      commander: 'Arcane Signet',          // nonsense as a commander, exact as a test
      list: "1 Ambition's Cost",
    });
    expect(r.status).toBe(200);
    const rows = await sql(
      `SELECT dc.name, dc.role FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
        WHERE d.slug = ? ORDER BY dc.name`, SLUG,
    );
    expect(rows).toEqual([
      { name: "Ambition's Cost", role: 'spell' },
      { name: 'Arcane Signet', role: 'commander' },
    ]);
  });

  it('puts the commander in the deck even when the list does not mention it', async () => {
    const r = await post('/decks/list', { slug: SLUG, commander: 'Arcane Signet', list: '1 Sol Ring' });
    expect(r.body.rows).toBe(2);
    expect(r.body.card_count).toBe(2);
  });

  it('does not double it up when the list names it too', async () => {
    const r = await post('/decks/list', {
      slug: SLUG, commander: 'Arcane Signet', list: '1 Arcane Signet\n1 Sol Ring',
    });
    expect(r.body.rows).toBe(2);
    const [row] = await sql(
      `SELECT dc.qty, dc.role FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
        WHERE d.slug = ? AND dc.name = 'Arcane Signet'`, SLUG,
    );
    expect(row).toEqual({ qty: 1, role: 'commander' });
  });

  it('takes partners, two names in the one field', async () => {
    await post('/decks/list', {
      slug: SLUG, commander: "Arcane Signet\nAmbition's Cost", list: '1 Sol Ring',
    });
    const roles = await sql(
      `SELECT dc.name, dc.role FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
        WHERE d.slug = ? AND dc.role = 'commander' ORDER BY dc.name`, SLUG,
    );
    expect(roles.map((r) => r.name)).toEqual(["Ambition's Cost", 'Arcane Signet']);
    const [deck] = await sql('SELECT commander FROM decks WHERE slug = ?', SLUG);
    expect(deck.commander).toBe("Arcane Signet // Ambition's Cost");
  });

  it('leaves the commander column alone when the name has not changed', async () => {
    // The column carries prose the editor's plain field cannot show, so an
    // unchanged name must not overwrite it.
    const [before] = await sql('SELECT commander FROM decks WHERE slug = ?', SLUG);
    expect(before.commander).toMatch(/\(/);          // it has the annotation

    const r = await post('/decks/list', {
      slug: SLUG, commander: 'Kardur, Doomscourge', list: '1 Sol Ring',
    });
    expect(r.body.commander_changed).toBe(false);

    const [after] = await sql('SELECT commander FROM decks WHERE slug = ?', SLUG);
    expect(after.commander).toBe(before.commander);
  });

  it('rewrites the column when the commander really changes', async () => {
    const r = await post('/decks/list', { slug: SLUG, commander: 'Sol Ring', list: '1 Arcane Signet' });
    expect(r.body.commander_changed).toBe(true);
    const [after] = await sql('SELECT commander FROM decks WHERE slug = ?', SLUG);
    expect(after.commander).toBe('Sol Ring');
  });

  it('reports a bad commander line rather than silently dropping it', async () => {
    const before = await listOf(SLUG);
    const r = await post('/decks/list', { slug: SLUG, commander: '0 Nope', list: '1 Sol Ring' });
    expect(r.status).toBe(400);
    expect(r.body.errors[0]).toMatch(/at least 1/);
    expect(await listOf(SLUG)).toBe(before);
  });

  it('an empty commander field means the deck has none', async () => {
    const r = await post('/decks/list', { slug: SLUG, commander: '', list: '1 Sol Ring' });
    expect(r.status).toBe(200);
    expect(await count('deck_cards',
      "deck_id = (SELECT id FROM decks WHERE slug = ?) AND role = 'commander'", SLUG)).toBe(0);
  });

  it('without the field at all, the old name-matching still applies', async () => {
    const r = await post('/decks/list', { slug: SLUG, list: '1 Kardur, Doomscourge\n1 Sol Ring' });
    expect(r.status).toBe(200);
    const [row] = await sql(
      `SELECT dc.role FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
        WHERE d.slug = ? AND dc.name LIKE 'Kardur%'`, SLUG,
    );
    expect(row.role).toBe('commander');
  });
});

// Editing a list moves real cards: out of bulk when a spare copy exists,
// back to bulk when the deck lets go, and into the collection when there
// was no spare to take.
//
// The Scryfall stub only knows five cards, so anything that has to be
// bought is written in terms of those. Everything else uses cards the
// fixture already owns, where nothing is bought and the stub is never
// reached.
describe('POST /decks/list — bulk', () => {
  const owned = (n) => sql(
    'SELECT COALESCE(SUM(qty), 0) AS q FROM cards WHERE owner = ? AND name_norm = ?', 'matt', n,
  ).then((r) => r[0].q);

  it('takes a spare copy out of bulk without buying anything', async () => {
    // Ambition's Cost is owned, and this deck is the only claim on it, so
    // the edit can take it straight back out of bulk.
    const before = await owned("ambition's cost");
    expect(before).toBeGreaterThan(0);

    const r = await post('/decks/list', { slug: SLUG, list: "1 Ambition's Cost" }, stubScryfall());
    expect(r.status).toBe(200);
    expect(r.body.acquired).toEqual([]);
    expect(await owned("ambition's cost")).toBe(before);
  });

  it('buys the shortfall when bulk cannot cover the list', async () => {
    expect(await owned('sol ring')).toBe(0);

    const r = await post('/decks/list', { slug: SLUG, list: '2 Sol Ring' }, stubScryfall());
    expect(r.status).toBe(200);
    expect(r.body.acquired).toEqual([['Sol Ring', 2]]);
    expect(await owned('sol ring')).toBe(2);

    // the deck is backed, so it is not a gap
    expect(await count('deck_gaps', 'slug = ?', SLUG)).toBe(0);
    const [deck] = await sql('SELECT card_count, owned_count FROM decks WHERE slug = ?', SLUG);
    expect(deck.owned_count).toBe(2);
  });

  it('buys only the difference, not the whole line', async () => {
    // One Ambition's Cost is owned and only this deck wants it, so asking
    // for three needs two more, not three.
    const r = await post('/decks/list',
      { slug: SLUG, list: "3 Ambition's Cost", dry_run: true }, stubScryfall());
    expect(r.body.acquired).toEqual([["Ambition's Cost", 2]]);
  });

  it('counts what another deck has already claimed', async () => {
    // matt owns one Arcane Signet and deck 2 has it. This deck releasing
    // its own claim does not make that copy free, so wanting one here
    // means buying one.
    const [have] = await sql(
      "SELECT SUM(qty) q FROM cards WHERE owner='matt' AND name_norm='arcane signet'",
    );
    expect(have.q).toBe(1);

    const r = await post('/decks/list',
      { slug: SLUG, list: '1 Arcane Signet', dry_run: true }, stubScryfall());
    expect(r.body.acquired).toEqual([['Arcane Signet', 1]]);
  });

  it('does not count the deck\'s own current claim against it', async () => {
    // The one copy this deck already holds comes back to bulk as part of
    // the edit, so asking for it again buys nothing.
    const r = await post('/decks/list',
      { slug: SLUG, list: "1 Ambition's Cost", dry_run: true }, stubScryfall());
    expect(r.body.acquired).toEqual([]);
  });

  it('hands copies back to bulk when the deck drops them', async () => {
    const before = await usage("ambition's cost");
    expect(before.in_decks).toBe(1);

    const r = await post('/decks/list', { slug: SLUG, list: '1 Sol Ring' }, stubScryfall());
    expect(r.status).toBe(200);
    expect(r.body.returned.map((x) => x[0])).toContain("Ambition's Cost");

    const after = await usage("ambition's cost");
    expect(after.owned).toBe(before.owned);    // still owned
    expect(after.free).toBe(before.owned);     // and back in bulk
  });

  it('never buys basic lands', async () => {
    const r = await post('/decks/list', { slug: SLUG, list: '30 Swamp\n4 Wastes' }, stubScryfall());
    expect(r.status).toBe(200);
    expect(r.body.acquired).toEqual([]);
    expect(await count('cards', "name_norm IN ('swamp','wastes')")).toBe(0);
  });

  it('buys nothing for a proxy deck', async () => {
    const before = await count('cards');
    const r = await post('/decks/list',
      { slug: 'halo-proxy-astor-equipment', list: '4 Lightning Bolt' }, stubScryfall());
    expect(r.status).toBe(200);
    expect(r.body.acquired).toEqual([]);
    expect(await count('cards')).toBe(before);
  });

  it('a dry run plans the purchase without making it', async () => {
    const snap = await snapshot();
    const r = await post('/decks/list',
      { slug: SLUG, list: '2 Lightning Bolt', dry_run: true }, stubScryfall());
    expect(r.body.acquired).toEqual([['Lightning Bolt', 2]]);
    expect(await owned('lightning bolt')).toBe(0);
    expect(await snapshot()).toEqual(snap);
  });

  it('leaves the deck alone when a card cannot be acquired', async () => {
    const listBefore = await listOf(SLUG);
    const snap = await snapshot();
    const r = await post('/decks/list',
      { slug: SLUG, list: '1 Utter Nonsense Not A Card' }, stubScryfall());
    expect(r.status).toBe(400);
    expect(r.body.error).toMatch(/unchanged/);
    expect(await listOf(SLUG)).toBe(listBefore);
    expect(await snapshot()).toEqual(snap);
  });

  it('leaves the deck alone when Scryfall is down', async () => {
    const listBefore = await listOf(SLUG);
    const r = await post('/decks/list',
      { slug: SLUG, list: '2 Lightning Bolt' }, stubScryfall({ fail: true }));
    expect(r.status).toBeGreaterThanOrEqual(400);
    expect(await listOf(SLUG)).toBe(listBefore);
  });

  it('never makes another card worse off than it was', async () => {
    // Only cards that already had a card_usage row can be compared: a card
    // nobody owned has no row at all, and acquiring one is what creates it.
    const key = (r) => `${r.owner}|${r.name_norm}`;
    const before = new Map(
      (await sql('SELECT owner, name_norm, free FROM card_usage')).map((r) => [key(r), r.free]),
    );

    await post('/decks/list', { slug: SLUG, list: '3 Sol Ring\n1 Lightning Bolt' }, stubScryfall());

    for (const r of await sql('SELECT owner, name_norm, free FROM card_usage')) {
      const was = before.get(key(r));
      if (was === undefined) continue;
      expect(r.free).toBeGreaterThanOrEqual(Math.min(was, 0));
    }
  });

  it('leaves the deck it edited with no gaps, and buys exactly the shortfall', async () => {
    const before = (await sql(
      "SELECT COALESCE(SUM(qty),0) q FROM cards WHERE owner='matt' AND name_norm='sol ring'",
    ))[0].q;

    const r = await post('/decks/list',
      { slug: SLUG, list: '3 Sol Ring\n1 Lightning Bolt' }, stubScryfall());

    expect(await count('deck_gaps', 'slug = ?', SLUG)).toBe(0);
    const bought = r.body.acquired.find(([n]) => n === 'Sol Ring')?.[1] ?? 0;
    const after = (await sql(
      "SELECT COALESCE(SUM(qty),0) q FROM cards WHERE owner='matt' AND name_norm='sol ring'",
    ))[0].q;
    expect(after).toBe(before + bought);
  });
});
