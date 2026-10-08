import { describe, it, expect } from 'vitest';
import { post, postAnon, get, sql, count, snapshot, stubScryfall } from './helpers.js';

// Deck 1 in the fixture lives at key 'd0000001', Matt's (account 1),
// with five deck_cards rows all in_collection = 1.
const DECK = 'd0000001';
const KEY = /^[0-9a-hjkmnp-tv-z]{8}$/;

/** What card_usage says about a card, for the owner that fixture uses. */
async function usage(nameNorm, ownerId = 1) {
  const [row] = await sql(
    'SELECT owned, in_decks, free FROM card_usage WHERE name_norm = ? AND owner_id = ?',
    nameNorm, ownerId,
  );
  return row;
}

describe('POST /decks/disassemble — the gate', () => {
  it('refuses without a token, and changes nothing', async () => {
    const before = await snapshot();
    const r = await postAnon('/decks/disassemble', { key: DECK });
    expect(r.status).toBe(401);
    expect(r.body.admin_required).toBe(true);
    expect(await snapshot()).toEqual(before);
  });

  it('refuses a dry run without a token too', async () => {
    const r = await postAnon('/decks/disassemble', { key: DECK, dry_run: true });
    expect(r.status).toBe(401);
  });

  it('rejects GET', async () => {
    const before = await snapshot();
    expect((await get('/decks/disassemble')).status).toBe(405);
    expect(await snapshot()).toEqual(before);
  });
});

describe('POST /decks/disassemble — bad input', () => {
  it('needs a key', async () => {
    const before = await snapshot();
    const r = await post('/decks/disassemble', {});
    expect(r.status).toBe(400);
    expect(r.body.error).toMatch(/key/);
    expect(await snapshot()).toEqual(before);
  });

  it('404s on a key that does not exist, and changes nothing', async () => {
    const before = await snapshot();
    const r = await post('/decks/disassemble', { key: 'zzzzzzzz' });
    expect(r.status).toBe(404);
    expect(r.body.error).toMatch(/zzzzzzzz/);
    expect(await snapshot()).toEqual(before);
  });
});

describe('POST /decks/disassemble — dry run', () => {
  it('reports what would be freed and writes nothing', async () => {
    const before = await snapshot();
    const r = await post('/decks/disassemble', { key: DECK, dry_run: true });

    expect(r.status).toBe(200);
    expect(r.body.applied).toBe(false);
    expect(r.body.dry_run).toBe(true);
    expect(r.body.deck).toMatchObject({ key: DECK });
    expect(r.body.freed).toBeGreaterThan(0);
    expect(r.body.cards.length).toBeGreaterThan(0);

    expect(await snapshot()).toEqual(before);
  });

  it('counts what the deck holds, not what it lists', async () => {
    // The proxy deck lists far more than it owns: most of its rows are
    // gaps (in_collection = 0) and a gap was never holding anything.
    const key = 'd0000004';
    const listed = await count('deck_cards', 'deck_id = (SELECT id FROM decks WHERE key = ?)', key);
    const owned = await count(
      'deck_cards', 'deck_id = (SELECT id FROM decks WHERE key = ?) AND in_collection = 1', key,
    );
    expect(owned).toBeLessThan(listed);

    const r = await post('/decks/disassemble', { key, dry_run: true });
    expect(r.body.rows.deck_cards).toBe(listed);
    expect(r.body.freed).toBe(owned);
    expect(r.body.cards).toHaveLength(owned);
  });
});

describe('POST /decks/disassemble — applied', () => {
  it('deletes the deck, its cards and its notes', async () => {
    const deckCards = await count('deck_cards', 'deck_id = (SELECT id FROM decks WHERE key = ?)', DECK);
    expect(deckCards).toBeGreaterThan(0);

    const r = await post('/decks/disassemble', { key: DECK });
    expect(r.status).toBe(200);
    expect(r.body.applied).toBe(true);
    expect(r.body.rows.deck_cards).toBe(deckCards);

    expect(await count('decks', 'key = ?', DECK)).toBe(0);
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

    await post('/decks/disassemble', { key: DECK });

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

    await post('/decks/disassemble', { key: DECK });

    expect(await count('cards')).toBe(cards);
    expect((await sql('SELECT SUM(qty) AS n FROM cards'))[0].n).toBe(copies);
  });

  it('leaves every other deck alone', async () => {
    const others = await sql('SELECT key, card_count FROM decks WHERE key != ? ORDER BY key', DECK);
    const rowsPerDeck = await sql(
      'SELECT deck_id, COUNT(*) AS n FROM deck_cards WHERE deck_id != (SELECT id FROM decks WHERE key = ?) GROUP BY deck_id ORDER BY deck_id',
      DECK,
    );

    await post('/decks/disassemble', { key: DECK });

    expect(await sql('SELECT key, card_count FROM decks ORDER BY key')).toEqual(others);
    expect(await sql('SELECT deck_id, COUNT(*) AS n FROM deck_cards GROUP BY deck_id ORDER BY deck_id'))
      .toEqual(rowsPerDeck);
  });

  it('a second disassemble of the same deck is a 404, not a second delete', async () => {
    await post('/decks/disassemble', { key: DECK });
    const before = await snapshot();
    const r = await post('/decks/disassemble', { key: DECK });
    expect(r.status).toBe(404);
    expect(await snapshot()).toEqual(before);
  });

  it('drops the deck out of the views that listed it', async () => {
    const gapsBefore = await count('deck_gaps', 'key = ?', DECK);
    await post('/decks/disassemble', { key: 'd0000004' });
    expect(await count('deck_gaps', 'key = ?', 'd0000004')).toBe(0);
    expect(await count('decks_not_built', 'key = ?', 'd0000004')).toBe(0);
    // and did not disturb the other deck's gaps
    expect(await count('deck_gaps', 'key = ?', DECK)).toBe(gapsBefore);
  });
});

// --------------------------------------------------------------- editing

/** The deck's list as a decklist, the way the UI hands it back. */
async function listOf(key) {
  const rows = await sql(
    `SELECT dc.qty, dc.name FROM deck_cards dc
       JOIN decks d ON d.id = dc.deck_id WHERE d.key = ? ORDER BY dc.name`, key,
  );
  return rows.map((r) => `${r.qty} ${r.name}`).join('\n');
}

describe('POST /decks/list — the gate and bad input', () => {
  it('refuses without a token', async () => {
    const before = await snapshot();
    const r = await postAnon('/decks/list', { key: DECK, list: '1 Sol Ring' });
    expect(r.status).toBe(401);
    expect(await snapshot()).toEqual(before);
  });

  it('rejects GET', async () => {
    expect((await get('/decks/list')).status).toBe(405);
  });

  it('needs a key and a list', async () => {
    expect((await post('/decks/list', { list: '1 Sol Ring' })).body.error).toMatch(/key/);
    expect((await post('/decks/list', { key: DECK })).body.error).toMatch(/list must be a string/);
  });

  it('404s on an unknown deck', async () => {
    const r = await post('/decks/list', { key: 'zzzzzzzz', list: '1 Sol Ring' });
    expect(r.status).toBe(404);
  });

  it('refuses the whole list when a line cannot be read, rather than dropping it', async () => {
    const before = await listOf(DECK);
    const snap = await snapshot();
    const r = await post('/decks/list', { key: DECK, list: '1 Sol Ring\n0 Lightning Bolt' });
    expect(r.status).toBe(400);
    expect(r.body.errors).toHaveLength(1);
    expect(await listOf(DECK)).toBe(before);
    expect(await snapshot()).toEqual(snap);
  });

  it('refuses an empty list instead of emptying the deck', async () => {
    const before = await listOf(DECK);
    const r = await post('/decks/list', { key: DECK, list: '   \n# nothing\n' });
    expect(r.status).toBe(400);
    expect(r.body.error).toMatch(/empty/);
    expect(await listOf(DECK)).toBe(before);
  });
});

describe('POST /decks/list — dry run', () => {
  it('reports the diff and writes nothing', async () => {
    const snap = await snapshot();
    const before = await listOf(DECK);

    const r = await post('/decks/list', {
      key: DECK,
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

    expect(await listOf(DECK)).toBe(before);
    expect(await snapshot()).toEqual(snap);
  });
});

describe('POST /decks/list — applied', () => {
  it('replaces the list, and the deck is exactly what was sent', async () => {
    const r = await post('/decks/list', {
      key: DECK,
      list: '1 Sol Ring\n3 Arcane Signet',
    });
    expect(r.status).toBe(200);
    expect(r.body.applied).toBe(true);

    const rows = await sql(
      `SELECT dc.qty, dc.name FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
        WHERE d.key = ? ORDER BY dc.name`, DECK,
    );
    expect(rows).toEqual([
      { qty: 3, name: 'Arcane Signet' },
      { qty: 1, name: 'Sol Ring' },
    ]);
  });

  it('keeps card_count and owned_count in step with the list', async () => {
    // Everything a real deck lists ends up backed, so the two agree.
    await post('/decks/list', { key: DECK, list: '4 Sol Ring\n2 Lightning Bolt' }, stubScryfall());
    const [deck] = await sql('SELECT card_count, owned_count FROM decks WHERE key = ?', DECK);
    expect(deck.card_count).toBe(6);
    expect(deck.owned_count).toBe(6);
  });

  it('leaves a real deck with no gaps at all, because it buys what is short', async () => {
    await post('/decks/list', { key: DECK, list: '1 Sol Ring\n1 Lightning Bolt' }, stubScryfall());
    const rows = await sql(
      `SELECT dc.name, dc.in_collection FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
        WHERE d.key = ? ORDER BY dc.name`, DECK,
    );
    expect(rows).toEqual([
      { name: 'Lightning Bolt', in_collection: 1 },
      { name: 'Sol Ring', in_collection: 1 },
    ]);
    expect(await count('deck_gaps', 'key = ?', DECK)).toBe(0);
  });

  it('adds up a card written on two lines', async () => {
    const r = await post('/decks/list', { key: DECK, list: '1 Sol Ring\n2 Sol Ring' });
    expect(r.body.rows).toBe(1);
    const [row] = await sql(
      `SELECT dc.qty FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id WHERE d.key = ?`, DECK,
    );
    expect(row.qty).toBe(3);
  });

  it('marks the commander, and lands as lands', async () => {
    // Kardur is deck 1's commander per the fixture.
    await post('/decks/list', { key: DECK, list: '1 Kardur, Doomscourge\n1 Arcane Signet' });
    const rows = await sql(
      `SELECT dc.name, dc.role FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
        WHERE d.key = ? ORDER BY dc.name`, DECK,
    );
    expect(rows.find((r) => r.name.startsWith('Kardur'))?.role).toBe('commander');
    expect(rows.find((r) => r.name === 'Arcane Signet')?.role).toBe('spell');
  });

  it('frees what it dropped and claims what it added', async () => {
    const before = await usage("ambition's cost");
    expect(before.in_decks).toBe(1);

    // a list without Ambition's Cost
    await post('/decks/list', { key: DECK, list: '1 Arcane Signet' });

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
        WHERE deck_id != (SELECT id FROM decks WHERE key = ?) GROUP BY deck_id ORDER BY deck_id`, DECK,
    );

    await post('/decks/list', { key: DECK, list: "1 Ambition's Cost" }, stubScryfall());

    expect((await sql('SELECT SUM(qty) AS n FROM cards'))[0].n).toBeGreaterThanOrEqual(copies);
    expect(await sql(
      `SELECT deck_id, COUNT(*) n FROM deck_cards
        WHERE deck_id != (SELECT id FROM decks WHERE key = ?) GROUP BY deck_id ORDER BY deck_id`, DECK,
    )).toEqual(otherRows);
  });

  it('survives a round trip: export the list, send it straight back', async () => {
    const before = await listOf(DECK);
    const r = await post('/decks/list', { key: DECK, list: before });
    expect(r.status).toBe(200);
    expect(r.body.added).toEqual([]);
    expect(r.body.removed).toEqual([]);
    expect(r.body.changed).toEqual([]);
    expect(await listOf(DECK)).toBe(before);
  });

  it('takes a name with an apostrophe without breaking the SQL', async () => {
    const r = await post('/decks/list', { key: DECK, list: "2 Ambition's Cost" });
    expect(r.status).toBe(200);
    const rows = await sql(
      `SELECT dc.name, dc.qty FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id WHERE d.key = ?`, DECK,
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
      key: DECK,
      list: '1 Arcane Signet\n20 Swamp\n4 Snow-Covered Island\n1 Wastes',
    });
    expect(r.status).toBe(200);
    expect(r.body.newly_missing).toEqual([]);
    expect(r.body.card_count).toBe(26);
    expect(r.body.owned_count).toBe(26);

    expect(await count('deck_gaps', 'key = ?', DECK)).toBe(0);
  });

  it('still calls a non-basic a gap on a proxy deck, which buys nothing', async () => {
    // A proxy deck is not made of real cards, so an unowned one stays a
    // gap instead of being acquired.
    const key = 'd0000004';
    const r = await post('/decks/list', { key, list: '1 Lightning Bolt' }, stubScryfall());
    expect(r.body.acquired).toEqual([]);
    expect(r.body.owned_count).toBe(0);
    expect(await count('deck_gaps', 'key = ?', key)).toBe(1);
  });
});

describe('POST /decks/list — the commander field', () => {
  it('makes the named card the commander, whatever the deck said before', async () => {
    const r = await post('/decks/list', {
      key: DECK,
      commander: 'Arcane Signet',          // nonsense as a commander, exact as a test
      list: "1 Ambition's Cost",
    });
    expect(r.status).toBe(200);
    const rows = await sql(
      `SELECT dc.name, dc.role FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
        WHERE d.key = ? ORDER BY dc.name`, DECK,
    );
    expect(rows).toEqual([
      { name: "Ambition's Cost", role: 'spell' },
      { name: 'Arcane Signet', role: 'commander' },
    ]);
  });

  it('puts the commander in the deck even when the list does not mention it', async () => {
    const r = await post('/decks/list', { key: DECK, commander: 'Arcane Signet', list: '1 Sol Ring' });
    expect(r.body.rows).toBe(2);
    expect(r.body.card_count).toBe(2);
  });

  it('does not double it up when the list names it too', async () => {
    const r = await post('/decks/list', {
      key: DECK, commander: 'Arcane Signet', list: '1 Arcane Signet\n1 Sol Ring',
    });
    expect(r.body.rows).toBe(2);
    const [row] = await sql(
      `SELECT dc.qty, dc.role FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
        WHERE d.key = ? AND dc.name = 'Arcane Signet'`, DECK,
    );
    expect(row).toEqual({ qty: 1, role: 'commander' });
  });

  it('takes partners, two names in the one field', async () => {
    await post('/decks/list', {
      key: DECK, commander: "Arcane Signet\nAmbition's Cost", list: '1 Sol Ring',
    });
    const roles = await sql(
      `SELECT dc.name, dc.role FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
        WHERE d.key = ? AND dc.role = 'commander' ORDER BY dc.name`, DECK,
    );
    expect(roles.map((r) => r.name)).toEqual(["Ambition's Cost", 'Arcane Signet']);
    const [deck] = await sql('SELECT commander FROM decks WHERE key = ?', DECK);
    expect(deck.commander).toBe("Arcane Signet // Ambition's Cost");
  });

  // The proxy deck, because it buys nothing: these are about the column,
  // not about the collection.
  const PROXY = 'd0000004';

  it('leaves the commander column alone when the name has not changed', async () => {
    // The column carries prose the editor's plain field cannot show, so an
    // unchanged name must not overwrite it.
    const [before] = await sql('SELECT commander FROM decks WHERE key = ?', PROXY);
    expect(before.commander).toMatch(/\(/);          // it has the annotation

    const r = await post('/decks/list', {
      key: PROXY, commander: 'Astor, Bearer of Blades', list: '1 Lightning Bolt',
    }, stubScryfall());
    expect(r.body.commander_changed).toBe(false);

    const [after] = await sql('SELECT commander FROM decks WHERE key = ?', PROXY);
    expect(after.commander).toBe(before.commander);
  });

  it('rewrites the column when the commander really changes', async () => {
    const r = await post('/decks/list',
      { key: PROXY, commander: 'Sol Ring', list: '1 Lightning Bolt' }, stubScryfall());
    expect(r.body.commander_changed).toBe(true);
    const [after] = await sql('SELECT commander FROM decks WHERE key = ?', PROXY);
    expect(after.commander).toBe('Sol Ring');
  });

  it('reports a bad commander line rather than silently dropping it', async () => {
    const before = await listOf(DECK);
    const r = await post('/decks/list', { key: DECK, commander: '0 Nope', list: '1 Sol Ring' });
    expect(r.status).toBe(400);
    expect(r.body.errors[0]).toMatch(/at least 1/);
    expect(await listOf(DECK)).toBe(before);
  });

  it('an empty commander field means the deck has none', async () => {
    const r = await post('/decks/list', { key: DECK, commander: '', list: '1 Sol Ring' });
    expect(r.status).toBe(200);
    expect(await count('deck_cards',
      "deck_id = (SELECT id FROM decks WHERE key = ?) AND role = 'commander'", DECK)).toBe(0);
  });

  it('without the field at all, the old name-matching still applies', async () => {
    // On the proxy deck, so the assertion is about the role and not about
    // going shopping for an Astor.
    const r = await post('/decks/list',
      { key: PROXY, list: '1 Astor, Bearer of Blades\n1 Lightning Bolt' }, stubScryfall());
    expect(r.status).toBe(200);
    const [row] = await sql(
      `SELECT dc.role FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
        WHERE d.key = ? AND dc.name LIKE 'Astor%'`, PROXY,
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
    'SELECT COALESCE(SUM(qty), 0) AS q FROM cards WHERE owner_id = ? AND name_norm = ?', 1, n,
  ).then((r) => r[0].q);

  it('takes a spare copy out of bulk without buying anything', async () => {
    // Ambition's Cost is owned, and this deck is the only claim on it, so
    // the edit can take it straight back out of bulk.
    const before = await owned("ambition's cost");
    expect(before).toBeGreaterThan(0);

    const r = await post('/decks/list', { key: DECK, list: "1 Ambition's Cost" }, stubScryfall());
    expect(r.status).toBe(200);
    expect(r.body.acquired).toEqual([]);
    expect(await owned("ambition's cost")).toBe(before);
  });

  it('buys the shortfall when bulk cannot cover the list', async () => {
    expect(await owned('sol ring')).toBe(0);

    const r = await post('/decks/list', { key: DECK, list: '2 Sol Ring' }, stubScryfall());
    expect(r.status).toBe(200);
    expect(r.body.acquired).toEqual([['Sol Ring', 2]]);
    expect(await owned('sol ring')).toBe(2);

    // the deck is backed, so it is not a gap
    expect(await count('deck_gaps', 'key = ?', DECK)).toBe(0);
    const [deck] = await sql('SELECT card_count, owned_count FROM decks WHERE key = ?', DECK);
    expect(deck.owned_count).toBe(2);
  });

  it('buys only the difference, not the whole line', async () => {
    // One Ambition's Cost is owned and only this deck wants it, so asking
    // for three needs two more, not three.
    const r = await post('/decks/list',
      { key: DECK, list: "3 Ambition's Cost", dry_run: true }, stubScryfall());
    expect(r.body.acquired).toEqual([["Ambition's Cost", 2]]);
  });

  it('counts what another deck has already claimed', async () => {
    // matt owns one Arcane Signet and deck 2 has it. This deck releasing
    // its own claim does not make that copy free, so wanting one here
    // means buying one.
    const [have] = await sql(
      "SELECT SUM(qty) q FROM cards WHERE owner_id = 1 AND name_norm='arcane signet'",
    );
    expect(have.q).toBe(1);

    const r = await post('/decks/list',
      { key: DECK, list: '1 Arcane Signet', dry_run: true }, stubScryfall());
    expect(r.body.acquired).toEqual([['Arcane Signet', 1]]);
  });

  it('does not count the deck\'s own current claim against it', async () => {
    // The one copy this deck already holds comes back to bulk as part of
    // the edit, so asking for it again buys nothing.
    const r = await post('/decks/list',
      { key: DECK, list: "1 Ambition's Cost", dry_run: true }, stubScryfall());
    expect(r.body.acquired).toEqual([]);
  });

  it('hands copies back to bulk when the deck drops them', async () => {
    const before = await usage("ambition's cost");
    expect(before.in_decks).toBe(1);

    const r = await post('/decks/list', { key: DECK, list: '1 Sol Ring' }, stubScryfall());
    expect(r.status).toBe(200);
    expect(r.body.returned.map((x) => x[0])).toContain("Ambition's Cost");

    const after = await usage("ambition's cost");
    expect(after.owned).toBe(before.owned);    // still owned
    expect(after.free).toBe(before.owned);     // and back in bulk
  });

  it('never buys basic lands', async () => {
    const r = await post('/decks/list', { key: DECK, list: '30 Swamp\n4 Wastes' }, stubScryfall());
    expect(r.status).toBe(200);
    expect(r.body.acquired).toEqual([]);
    expect(await count('cards', "name_norm IN ('swamp','wastes')")).toBe(0);
  });

  it('buys nothing for a proxy deck', async () => {
    const before = await count('cards');
    const r = await post('/decks/list',
      { key: 'd0000004', list: '4 Lightning Bolt' }, stubScryfall());
    expect(r.status).toBe(200);
    expect(r.body.acquired).toEqual([]);
    expect(await count('cards')).toBe(before);
  });

  it('a dry run plans the purchase without making it', async () => {
    const snap = await snapshot();
    const r = await post('/decks/list',
      { key: DECK, list: '2 Lightning Bolt', dry_run: true }, stubScryfall());
    expect(r.body.acquired).toEqual([['Lightning Bolt', 2]]);
    expect(await owned('lightning bolt')).toBe(0);
    expect(await snapshot()).toEqual(snap);
  });

  it('leaves the deck alone when a card cannot be acquired', async () => {
    const listBefore = await listOf(DECK);
    const snap = await snapshot();
    const r = await post('/decks/list',
      { key: DECK, list: '1 Utter Nonsense Not A Card' }, stubScryfall());
    expect(r.status).toBe(400);
    expect(r.body.error).toMatch(/nothing was changed/);
    expect(await listOf(DECK)).toBe(listBefore);
    expect(await snapshot()).toEqual(snap);
  });

  it('leaves the deck alone when Scryfall is down', async () => {
    const listBefore = await listOf(DECK);
    const r = await post('/decks/list',
      { key: DECK, list: '2 Lightning Bolt' }, stubScryfall({ fail: true }));
    expect(r.status).toBeGreaterThanOrEqual(400);
    expect(await listOf(DECK)).toBe(listBefore);
  });

  it('never makes another card worse off than it was', async () => {
    // Only cards that already had a card_usage row can be compared: a card
    // nobody owned has no row at all, and acquiring one is what creates it.
    const key = (r) => `${r.owner_id}|${r.name_norm}`;
    const before = new Map(
      (await sql('SELECT owner_id, name_norm, free FROM card_usage')).map((r) => [key(r), r.free]),
    );

    await post('/decks/list', { key: DECK, list: '3 Sol Ring\n1 Lightning Bolt' }, stubScryfall());

    for (const r of await sql('SELECT owner_id, name_norm, free FROM card_usage')) {
      const was = before.get(key(r));
      if (was === undefined) continue;
      expect(r.free).toBeGreaterThanOrEqual(Math.min(was, 0));
    }
  });

  it('leaves the deck it edited with no gaps, and buys exactly the shortfall', async () => {
    const before = (await sql(
      "SELECT COALESCE(SUM(qty),0) q FROM cards WHERE owner_id = 1 AND name_norm='sol ring'",
    ))[0].q;

    const r = await post('/decks/list',
      { key: DECK, list: '3 Sol Ring\n1 Lightning Bolt' }, stubScryfall());

    expect(await count('deck_gaps', 'key = ?', DECK)).toBe(0);
    const bought = r.body.acquired.find(([n]) => n === 'Sol Ring')?.[1] ?? 0;
    const after = (await sql(
      "SELECT COALESCE(SUM(qty),0) q FROM cards WHERE owner_id = 1 AND name_norm='sol ring'",
    ))[0].q;
    expect(after).toBe(before + bought);
  });
});

// -------------------------------------------------------------- creating

describe('POST /decks/create', () => {
  const NEW = { name: 'Test Brew', format: 'commander', commander: 'Sol Ring' };

  /**
   * A new deck knows what colour it is.
   *
   * Matt: "Why is milly moth deck showing as colorless???"
   *
   * Because `createDeck` wrote `colors: null` and nothing ever filled
   * it in. Every deck that came through the original import has the
   * column set, so the three decks made in the app were the only
   * colourless ones in the database — and `Deck.identity` reads an
   * empty string as no pips at all.
   *
   * The commander's own colour identity is the answer, and `cards`
   * already holds it as the bare letters the deck parser reads.
   */
  it('takes its colours from its commander', async () => {
    const r = await post(
      '/decks/create',
      { ...NEW, commander: "Akroma's Will", list: '1 Lightning Bolt', dry_run: false },
      stubScryfall(),
    );
    expect(r.status, JSON.stringify(r.body)).toBe(201);
    const row = (await sql('SELECT colors FROM decks WHERE key = ?1', r.body.key))[0];
    expect(row.colors).toBe('W');
  });

  // A commander the collection has never heard of cannot be bought
  // either, so the deck is refused long before anything asks what
  // colour it is — there is no way through this endpoint to reach the
  // "unknown commander" branch of `paintDeck`. The branch stays
  // because a commander can be removed from the collection later, and
  // a repaint then must not invent a colour from the other
  // ninety-nine cards.

  it('a format with no commander takes them from the cards instead', async () => {
    const r = await post(
      '/decks/create',
      {
        name: 'Modern Thing', format: 'modern',
        list: '1 Lightning Bolt', dry_run: false,
      },
      stubScryfall(),
    );
    expect(r.status).toBe(201);
    const row = (await sql('SELECT colors FROM decks WHERE key = ?1', r.body.key))[0];
    expect(row.colors).toBe('R');
  });

  it('refuses without a token', async () => {
    const snap = await snapshot();
    const r = await postAnon('/decks/create', { ...NEW, list: '1 Lightning Bolt' });
    expect(r.status).toBe(401);
    expect(await snapshot()).toEqual(snap);
  });

  it('insists on a name, a known format and a real owner', async () => {
    const bad = [
      [{ ...NEW, name: '' }, /name/],
      [{ ...NEW, format: 'pauperish' }, /format/],
      // An owner used to be a slug in the body. It is an account id
      // now, which no body can carry, so naming one at all is refused
      // — empty or not — rather than quietly ignored.
      [{ ...NEW, owner: '' }, /`owner` is gone/],
      [{ ...NEW, owner: 'matt' }, /`owner` is gone/],
    ];
    for (const [body, pattern] of bad) {
      const r = await post('/decks/create', { ...body, list: '1 Lightning Bolt' }, stubScryfall());
      expect(r.status, JSON.stringify(body)).toBe(400);
      expect(r.body.error).toMatch(pattern);
    }
    // And a collection nobody has is not somewhere to put a deck.
    const nowhere = await post('/decks/create',
      { ...NEW, collection: 'zzzzzzzz', list: '1 Lightning Bolt' }, stubScryfall());
    expect(nowhere.status).toBe(404);
    expect(await count('decks', "name = 'Test Brew'")).toBe(0);
  });

  it('insists on a commander for a commander deck, but not for standard', async () => {
    const without = { name: 'Test Brew', format: 'commander', list: '1 Lightning Bolt' };
    expect((await post('/decks/create', without, stubScryfall())).body.error).toMatch(/commander/);

    const std = { name: 'Test Std', format: 'standard', list: '4 Lightning Bolt' };
    const r = await post('/decks/create', std, stubScryfall());
    expect(r.status).toBe(201);
    const [deck] = await sql('SELECT format, commander FROM decks WHERE key = ?', r.body.key);
    expect(deck).toEqual({ format: 'standard', commander: null });
  });

  it('rejects a bracket outside 1-5', async () => {
    const r = await post('/decks/create',
      { ...NEW, bracket: '9', list: '1 Lightning Bolt' }, stubScryfall());
    expect(r.body.error).toMatch(/bracket/);
  });

  it('takes the name of an existing deck as a new deck, at its own key', async () => {
    // There was a 409 here when the address came from the name. The
    // address is a random key now, so a name is not a place and two
    // decks may share one without either moving.
    const [old] = await sql('SELECT id, key, name, card_count FROM decks WHERE key = ?', DECK);
    const r = await post('/decks/create',
      { ...NEW, name: old.name, list: '1 Lightning Bolt' }, stubScryfall());
    expect(r.status, JSON.stringify(r.body)).toBe(201);
    expect(r.body.key).toMatch(KEY);
    expect(r.body.key).not.toBe(DECK);
    expect(await sql('SELECT id, key, name, card_count FROM decks WHERE key = ?', DECK)).toEqual([old]);
    expect(await count('decks', 'name = ?', old.name)).toBe(2);
  });

  it('creates the deck, its list, and the cards to back it', async () => {
    const r = await post('/decks/create',
      { ...NEW, bracket: '3', theme: 'artifacts', list: '2 Lightning Bolt' }, stubScryfall());

    expect(r.status).toBe(201);
    expect(r.body.created).toBe(true);
    expect(r.body.key).toMatch(KEY);
    expect(r.body).not.toHaveProperty('slug');

    const [deck] = await sql(
      'SELECT key, name, owner_id, format, commander, bracket, theme, is_proxy, card_count, owned_count FROM decks WHERE key = ?',
      r.body.key,
    );
    expect(deck).toMatchObject({
      key: r.body.key, name: 'Test Brew', owner_id: 1, format: 'commander',
      commander: 'Sol Ring', bracket: '3', theme: 'artifacts', is_proxy: 0,
    });
    expect(deck.card_count).toBe(3);      // the commander plus two bolts
    expect(deck.owned_count).toBe(3);

    const rows = await sql(
      `SELECT dc.qty, dc.name, dc.role FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
        WHERE d.key = ? ORDER BY dc.name`, r.body.key,
    );
    expect(rows).toEqual([
      { qty: 2, name: 'Lightning Bolt', role: 'spell' },
      { qty: 1, name: 'Sol Ring', role: 'commander' },
    ]);

    // it bought what it needed rather than claiming cards from nowhere
    expect(r.body.acquired).toEqual(expect.arrayContaining([['Sol Ring', 1], ['Lightning Bolt', 2]]));
    expect(await count('deck_gaps', 'key = ?', r.body.key)).toBe(0);
  });

  it('gives the new deck a fresh id rather than reusing one', async () => {
    const before = await sql('SELECT id FROM decks ORDER BY id');
    await post('/decks/create', { ...NEW, list: '1 Lightning Bolt' }, stubScryfall());
    const after = await sql('SELECT id FROM decks ORDER BY id');
    expect(after).toHaveLength(before.length + 1);
    expect(new Set(after.map((r) => r.id)).size).toBe(after.length);
  });

  it('a dry run creates nothing', async () => {
    const snap = await snapshot();
    const r = await post('/decks/create',
      { ...NEW, list: '2 Lightning Bolt', dry_run: true }, stubScryfall());
    expect(r.status).toBe(200);
    expect(r.body.created).toBeFalsy();
    expect(r.body.acquired.length).toBeGreaterThan(0);
    expect(await snapshot()).toEqual(snap);
  });

  it('creates nothing at all when a card cannot be resolved', async () => {
    const snap = await snapshot();
    const r = await post('/decks/create',
      { ...NEW, list: '1 Utter Nonsense Not A Card' }, stubScryfall());
    expect(r.status).toBe(400);
    expect(await snapshot()).toEqual(snap);
    expect(await count('decks', "name = 'Test Brew'")).toBe(0);
  });

  it('refuses an empty list', async () => {
    const r = await post('/decks/create', { ...NEW, list: '  \n# nothing' }, stubScryfall());
    expect(r.status).toBe(400);
    expect(r.body.error).toMatch(/empty/);
  });

  it('takes a proxy deck, which buys nothing', async () => {
    const cards = await count('cards');
    const r = await post('/decks/create',
      { ...NEW, name: 'Proxy Brew', is_proxy: true, list: '4 Lightning Bolt' }, stubScryfall());
    expect(r.status).toBe(201);
    expect(r.body.acquired).toEqual([]);
    expect(await count('cards')).toBe(cards);
    const [deck] = await sql('SELECT is_proxy FROM decks WHERE key = ?', r.body.key);
    expect(deck.is_proxy).toBe(1);
  });
});

describe('GET /decks/formats', () => {
  it('lists the formats, saying which take a commander', async () => {
    const r = await get('/decks/formats');
    expect(r.status).toBe(200);
    const ids = r.body.formats.map((f) => f.id);
    expect(ids).toContain('commander');
    expect(ids).toContain('standard');
    expect(r.body.formats.find((f) => f.id === 'commander').singleton).toBe(true);
    expect(r.body.formats.find((f) => f.id === 'standard').singleton).toBeUndefined();
  });
});

// ------------------------------------------------------- choosing a source
//
// A card in a deck came from somewhere. The wizard asks where: out of this
// owner's bulk, out of the other collection, or bought new. These cover the
// arithmetic behind that choice and the inventory move behind a transfer.

describe('POST /decks/list — the sourcing plan', () => {
  const find = (body, name) => body.sourcing.find((s) => s.name === name);

  it('reports, per card, what each collection could cover', async () => {
    const r = await post('/decks/list',
      { key: DECK, list: "2 Ambition's Cost\n1 Sol Ring", dry_run: true }, stubScryfall());

    const cost = find(r.body, "Ambition's Cost");
    // The other collection, by its display name: the household's other
    // half, found by email. Never an id.
    expect(cost).toMatchObject({ need: 2, other_owner: 'Kayla M' });
    expect(cost).not.toHaveProperty('other_id');
    expect(cost.own_free).toBeGreaterThanOrEqual(0);
    expect(cost.own_free + cost.buy + cost.transfer).toBe(2);
  });

  it('defaults to bulk first and buys only the remainder', async () => {
    const r = await post('/decks/list',
      { key: DECK, list: "3 Ambition's Cost", dry_run: true }, stubScryfall());
    const s = find(r.body, "Ambition's Cost");
    expect(s.choice).toBe('bulk');
    expect(s.from_bulk).toBe(s.own_free);
    expect(s.buy).toBe(3 - s.own_free);
  });

  it('"buy" leaves bulk alone and gets the whole quantity new', async () => {
    const r = await post('/decks/list', {
      key: DECK,
      list: "2 Ambition's Cost",
      sources: { "ambition's cost": 'buy' },
      dry_run: true,
    }, stubScryfall());
    const s = find(r.body, "Ambition's Cost");
    expect(s.from_bulk).toBe(0);
    expect(s.buy).toBe(2);
    expect(r.body.acquired).toContainEqual(["Ambition's Cost", 2]);
  });

  it('never sources a basic land from anywhere', async () => {
    const r = await post('/decks/list',
      { key: DECK, list: '1 Sol Ring\n20 Swamp', dry_run: true }, stubScryfall());
    const s = find(r.body, 'Swamp');
    expect(s.basic).toBe(true);
    expect(s.buy).toBe(0);
    expect(s.transfer).toBe(0);
  });

  it('a proxy deck sources nothing at all', async () => {
    const r = await post('/decks/list',
      { key: 'd0000004', list: '4 Lightning Bolt', dry_run: true }, stubScryfall());
    expect(r.body.acquired).toEqual([]);
    expect(r.body.sourcing.every((s) => s.buy === 0 && s.transfer === 0)).toBe(true);
  });
});

describe('POST /decks/list — transferring between collections', () => {
  const MATT_ID = 1;
  const KAYLA_ID = 3;
  const heldBy = (ownerId, nameNorm) => sql(
    'SELECT COALESCE(SUM(qty), 0) AS q FROM cards WHERE owner_id = ? AND name_norm = ?', ownerId, nameNorm,
  ).then((r) => r[0].q);

  it('plans a transfer out of the other collection instead of buying', async () => {
    // Kayla owns Arcane Signet and no deck of hers claims it.
    const kayla = await heldBy(KAYLA_ID, 'arcane signet');
    expect(kayla).toBeGreaterThan(0);

    const r = await post('/decks/list', {
      key: DECK,
      list: '1 Arcane Signet',
      sources: { 'arcane signet': 'transfer' },
      dry_run: true,
    }, stubScryfall());

    expect(r.body.transferred).toContainEqual(['Arcane Signet', 1, 'Kayla M']);
    expect(r.body.acquired).toEqual([]);
  });

  it('actually moves the copies, and buys nothing', async () => {
    const before = {
      matt: await heldBy(MATT_ID, 'arcane signet'),
      kayla: await heldBy(KAYLA_ID, 'arcane signet'),
    };

    const r = await post('/decks/list', {
      key: DECK,
      list: '1 Arcane Signet',
      sources: { 'arcane signet': 'transfer' },
    }, stubScryfall());
    expect(r.status).toBe(200);
    expect(r.body.acquired).toEqual([]);

    expect(await heldBy(KAYLA_ID, 'arcane signet')).toBe(before.kayla - 1);
    expect(await heldBy(MATT_ID, 'arcane signet')).toBe(before.matt + 1);
  });

  it('conserves the total across both collections', async () => {
    const total = () => sql(
      "SELECT COALESCE(SUM(qty),0) AS q FROM cards WHERE name_norm = 'arcane signet'",
    ).then((r) => r[0].q);
    const before = await total();
    await post('/decks/list', {
      key: DECK, list: '1 Arcane Signet', sources: { 'arcane signet': 'transfer' },
    }, stubScryfall());
    expect(await total()).toBe(before);
  });

  it('gives the received card its child rows, not a bare row', async () => {
    await post('/decks/list', {
      key: DECK, list: '1 Arcane Signet', sources: { 'arcane signet': 'transfer' },
    }, stubScryfall());

    const [row] = await sql(
      `SELECT id FROM cards WHERE owner_id = 1 AND name_norm = 'arcane signet' ORDER BY id DESC LIMIT 1`,
    );
    const kids = await sql('SELECT COUNT(*) AS n FROM card_types WHERE card_id = ?', row.id);
    expect(kids[0].n).toBeGreaterThan(0);
    const fts = await sql('SELECT COUNT(*) AS n FROM card_search WHERE rowid = ?', row.id);
    expect(fts[0].n).toBe(1);
  });

  it('falls back to buying whatever the other collection cannot cover', async () => {
    const kayla = await heldBy(KAYLA_ID, 'arcane signet');
    const r = await post('/decks/list', {
      key: DECK,
      list: `${kayla + 4} Arcane Signet`,
      sources: { 'arcane signet': 'transfer' },
      dry_run: true,
    }, stubScryfall());
    const s = r.body.sourcing.find((x) => x.name === 'Arcane Signet');
    expect(s.transfer).toBe(s.other_free);
    expect(s.from_bulk + s.transfer + s.buy).toBe(kayla + 4);
    expect(s.buy).toBeGreaterThan(0);
  });

  it('leaves no orphaned child rows behind in the giving collection', async () => {
    await post('/decks/list', {
      key: DECK, list: '1 Arcane Signet', sources: { 'arcane signet': 'transfer' },
    }, stubScryfall());
    expect(await count('card_types', 'card_id NOT IN (SELECT id FROM cards)')).toBe(0);
    expect(await count('card_search', 'rowid NOT IN (SELECT id FROM cards)')).toBe(0);
  });
});

describe('POST /decks/list — a list the size of a real deck', () => {
  /**
   * A hundred different names, which is what a Commander deck is.
   *
   * Every lookup behind an edit asks about every name at once, and
   * D1 refuses a statement with more than 100 bound parameters — so
   * the chunk size has to leave room for whatever else the statement
   * binds. It did not, and editing any real deck answered "too many
   * SQL variables".
   */
  const bigList = (n) => Array.from({ length: n }, (_, i) => `1 Made Up Card ${i}`).join('\n');

  it('plans a hundred-card list without tripping the parameter limit', async () => {
    const r = await post('/decks/list', { key: DECK, list: bigList(100), dry_run: true });
    expect(r.body.error).toBeUndefined();
    expect(r.status).toBe(200);
    expect(r.body.rows).toBe(100);
  });

  it('and a list well past one chunk', async () => {
    const r = await post('/decks/list', { key: DECK, list: bigList(250), dry_run: true });
    expect(r.body.error).toBeUndefined();
    expect(r.body.rows).toBe(250);
  });

  it('writes one too', async () => {
    // Real names, so the edit does not have to buy a hundred made-up
    // cards before it can get to the write.
    const owned = await sql("SELECT DISTINCT name FROM cards WHERE owner_id = 1 ORDER BY name");
    const list = owned.map((r) => `1 ${r.name}`).join('\n');
    const r = await post('/decks/list', { key: DECK, list, dry_run: false });
    expect(r.body.error).toBeUndefined();
    expect(r.body.applied).toBe(true);
    expect((await listOf(DECK)).split('\n').length).toBe(owned.length);
  });
});
