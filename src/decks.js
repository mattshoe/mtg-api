// Deck operations that are more than one statement: disassembling a deck,
// and replacing its list.
//
// Disassembling reads as "move the cards to bulk", but nothing about a
// card moves: `cards` already records what is
// owned, and `deck_cards` is the only thing claiming any of it. Bulk is
// not a place, it is the `card_usage` view's `free` column — owned minus
// the copies some deck has spoken for. Delete the deck and its list and
// every copy it held falls free on its own.
//
// Which means the whole job is three deletes, and the only thing that
// matters is that they are one batch: a half-deleted deck leaves
// deck_cards rows pointing at a decks row that is gone, and those keep
// counting against `free` forever.

import { parseList, normalize } from './parse.js';
import { addCards } from './cards.js';

/**
 * What a deck is holding, by name, for the confirmation step.
 * Only rows backed by something owned — a gap was never held.
 */
async function heldBy(db, deckId) {
  const r = await db.prepare(`
    SELECT dc.name, SUM(dc.qty) AS qty
      FROM deck_cards dc
     WHERE dc.deck_id = ? AND dc.in_collection = 1
     GROUP BY dc.name_norm
     ORDER BY dc.name`).bind(deckId).all();
  return r.results || [];
}

/**
 * Delete a deck and free everything it was holding.
 *
 * @param {D1Database} db
 * @param {{slug?: string, dry_run?: boolean}} body
 */
export async function disassembleDeck(db, body) {
  const slug = String(body?.slug || '').trim();
  if (!slug) return { status: 400, body: { error: 'slug is required' } };

  const deck = await db.prepare(
    'SELECT id, slug, name, owner, card_count FROM decks WHERE slug = ?',
  ).bind(slug).first();
  if (!deck) return { status: 404, body: { error: `no deck with slug "${slug}"` } };

  const held = await heldBy(db, deck.id);
  const freed = held.reduce((a, r) => a + r.qty, 0);

  const counts = await db.prepare(`
    SELECT (SELECT COUNT(*) FROM deck_cards WHERE deck_id = ?1) AS cards,
           (SELECT COUNT(*) FROM deck_notes WHERE deck_id = ?1) AS notes`)
    .bind(deck.id).first();

  const out = {
    deck: { slug: deck.slug, name: deck.name, owner: deck.owner },
    freed,
    cards: held,
    rows: { deck_cards: counts?.cards ?? 0, deck_notes: counts?.notes ?? 0 },
  };

  if (body?.dry_run) return { status: 200, body: { ...out, applied: false, dry_run: true } };

  // Children first, so an interrupted batch can never orphan them.
  await db.batch([
    db.prepare('DELETE FROM deck_notes WHERE deck_id = ?').bind(deck.id),
    db.prepare('DELETE FROM deck_cards WHERE deck_id = ?').bind(deck.id),
    db.prepare('DELETE FROM decks WHERE id = ?').bind(deck.id),
  ]);

  return { status: 200, body: { ...out, applied: true, dry_run: false } };
}

// --------------------------------------------------------------- editing

const MAX_ROWS = 1000;

/**
 * Basics are not tracked in the collection any more, so a deck slot for
 * one is never a gap. Without this every edited deck would report its
 * twenty Forests as cards to go and buy, and owned_count would fall by
 * however many lands it runs.
 */
const BASICS = new Set(['plains', 'island', 'swamp', 'mountain', 'forest', 'wastes']);
const isBasic = (nameNorm) => BASICS.has(nameNorm.replace(/^snow-covered /, ''));
// D1 caps a statement at 100 bound parameters. Nine per inserted row, and
// names carry apostrophes so they have to stay bound — hence one statement
// per row, batched, rather than one big multi-row INSERT.
const CHUNK = 90;

/** The commander's name, without the prose the column trails it with. */
const commanderNorm = (s) => normalize(String(s || '').replace(/\s*\([^)]*\)\s*$/, ''));

/**
 * What the owner actually holds, for these names. Drives `in_collection`,
 * which is what deck_gaps reports and what card_usage counts against
 * `free` — so it is recomputed from the collection every time rather than
 * carried over from whatever the row said before.
 */
async function ownedIndex(db, owner, keys) {
  const found = new Map();
  for (let i = 0; i < keys.length; i += CHUNK) {
    const slice = keys.slice(i, i + CHUNK);
    const marks = slice.map(() => '?').join(',');
    const r = await db.prepare(`
      SELECT name_norm, MIN(id) AS id, name, oracle_id, type_line, SUM(qty) AS owned_qty
        FROM cards
       WHERE owner = ? AND name_norm IN (${marks})
       GROUP BY name_norm`).bind(owner, ...slice).all();
    for (const row of r.results || []) found.set(row.name_norm, row);
  }
  return found;
}

/**
 * How many copies the owner's *other* decks have already spoken for.
 *
 * "Other" matters: the deck being edited hands its whole list back to bulk
 * as part of the edit, so its current claim must not count against it.
 * Proxy and PROPOSED decks are skipped for the same reason card_usage
 * skips them — they do not consume real cards.
 */
async function claimedElsewhere(db, owner, deckId, keys) {
  const found = new Map();
  for (let i = 0; i < keys.length; i += CHUNK) {
    const slice = keys.slice(i, i + CHUNK);
    const marks = slice.map(() => '?').join(',');
    const r = await db.prepare(`
      SELECT dc.name_norm, SUM(dc.qty) AS n
        FROM deck_cards dc
        JOIN decks d ON d.id = dc.deck_id
       WHERE d.owner = ? AND d.id != ?
         AND d.is_proxy = 0
         AND (d.status IS NULL OR d.status NOT LIKE 'PROPOSED%')
         AND dc.in_collection = 1
         AND dc.name_norm IN (${marks})
       GROUP BY dc.name_norm`).bind(owner, deckId, ...slice).all();
    for (const row of r.results || []) found.set(row.name_norm, row.n);
  }
  return found;
}

/** Any printing at all, for the oracle_id and type of a card nobody owns. */
async function anyPrintingIndex(db, keys) {
  const found = new Map();
  for (let i = 0; i < keys.length; i += CHUNK) {
    const slice = keys.slice(i, i + CHUNK);
    const marks = slice.map(() => '?').join(',');
    const r = await db.prepare(`
      SELECT name_norm, MIN(id) AS id, name, oracle_id, type_line
        FROM cards WHERE name_norm IN (${marks}) GROUP BY name_norm`).bind(...slice).all();
    for (const row of r.results || []) found.set(row.name_norm, row);
  }
  return found;
}

/**
 * Replace a deck's list wholesale from a decklist.
 *
 * A replace, not a merge: what you send is what the deck becomes. That is
 * why a single unparseable line refuses the whole request instead of
 * applying the rest — dropping a line here silently deletes a card from
 * the deck, which is not what a typo should do.
 *
 * `commander` is its own field, so the 99 stay a plain list with nothing
 * marking one line out from the others. Send it and it defines the
 * commander rows outright; leave it off and the deck's existing commander
 * is matched by name, the way it worked before the field existed.
 */
export async function editDeckList(db, body, fetchImpl) {
  const slug = String(body?.slug || '').trim();
  if (!slug) return { status: 400, body: { error: 'slug is required' } };
  if (typeof body?.list !== 'string') return { status: 400, body: { error: 'list must be a string' } };

  const deck = await db.prepare(
    'SELECT id, slug, name, owner, commander, is_proxy, status FROM decks WHERE slug = ?',
  ).bind(slug).first();
  if (!deck) return { status: 404, body: { error: `no deck with slug "${slug}"` } };

  const hasCommanderField = typeof body.commander === 'string';
  const cmdrParsed = hasCommanderField ? parseList(body.commander) : { items: [], errors: [] };
  const { items, errors } = parseList(body.list);
  const allErrors = [...cmdrParsed.errors, ...errors];
  if (allErrors.length) {
    return {
      status: 400,
      body: { error: `${allErrors.length} line(s) could not be read`, errors: allErrors },
    };
  }
  if (!items.length) {
    return { status: 400, body: { error: 'the list is empty — disassemble the deck instead' } };
  }
  if (items.length > MAX_ROWS) {
    return { status: 400, body: { error: `${items.length} lines is over the ${MAX_ROWS} limit` } };
  }

  // The same card written twice is one row with the quantities added.
  // The commander is added first so that writing it into the 99 as well
  // does not produce a second row for it.
  const wanted = new Map();
  const commanders = new Set(cmdrParsed.items.map((it) => normalize(it.name)));
  for (const it of [...cmdrParsed.items, ...items]) {
    const key = normalize(it.name);
    const prev = wanted.get(key);
    if (prev) { if (!commanders.has(key)) prev.qty += it.qty; } else {
      wanted.set(key, { qty: it.qty, typed: it.name, name_norm: key });
    }
  }

  const keys = [...wanted.keys()];
  const [owned, anywhere, currentRows, elsewhere] = await Promise.all([
    ownedIndex(db, deck.owner, keys),
    anyPrintingIndex(db, keys),
    db.prepare(
      'SELECT qty, name, name_norm, role, section, in_collection FROM deck_cards WHERE deck_id = ?',
    ).bind(deck.id).all(),
    claimedElsewhere(db, deck.owner, deck.id, keys),
  ]);

  // A proxy or merely PROPOSED deck is not made of real cards — card_usage
  // does not count it against anything — so it neither draws from bulk nor
  // causes anything to be acquired.
  const physical = !deck.is_proxy && !(deck.status || '').startsWith('PROPOSED');
  const current = new Map((currentRows.results || []).map((r) => [r.name_norm, r]));
  const cmdr = commanderNorm(deck.commander);

  // What the deck needs that bulk cannot cover. Putting a card in a deck
  // means it came from somewhere: out of bulk if a copy was spare, and
  // otherwise it had to be acquired, so the collection is made to say so
  // rather than the deck claiming a card that does not exist.
  const shortfall = [];
  if (physical) {
    for (const w of wanted.values()) {
      if (isBasic(w.name_norm)) continue;
      const have = owned.get(w.name_norm)?.owned_qty ?? 0;
      const spoken = elsewhere.get(w.name_norm) ?? 0;
      // Clamped: another deck being short already is that deck's problem.
      // Editing this one should make this one whole, not pay off a debt
      // somewhere else.
      const spare = Math.max(0, have - spoken);
      if (w.qty > spare) shortfall.push([w.typed, w.qty - spare]);
    }
  }

  const rows = [];
  for (const w of wanted.values()) {
    const card = owned.get(w.name_norm) || anywhere.get(w.name_norm);
    const was = current.get(w.name_norm);
    const type = card?.type_line || '';
    rows.push({
      qty: w.qty,
      // Prefer the database's spelling; fall back to what was typed.
      name: card?.name || w.typed,
      name_norm: w.name_norm,
      raw_name: w.typed,
      oracle_id: card?.oracle_id ?? null,
      role: (hasCommanderField ? commanders.has(w.name_norm) : w.name_norm === cmdr)
        ? 'commander'
        : (/\bLand\b/.test(type) ? 'land' : 'spell'),
      // A heading is the one thing a decklist cannot carry, so keep the
      // one the row already had and leave new rows unsectioned.
      section: was?.section ?? null,
      // Backed by definition on a physical deck: anything short was just
      // acquired above.
      in_collection: (physical || owned.has(w.name_norm) || isBasic(w.name_norm)) ? 1 : 0,
    });
  }

  const cardCount = rows.reduce((a, r) => a + r.qty, 0);
  const ownedCount = rows.reduce((a, r) => a + (r.in_collection ? r.qty : 0), 0);

  const added = rows.filter((r) => !current.has(r.name_norm))
    .map((r) => [r.name, r.qty]);
  const removed = [...current.values()].filter((r) => !wanted.has(r.name_norm))
    .map((r) => [r.name, r.qty]);
  const changed = rows.filter((r) => {
    const was = current.get(r.name_norm);
    return was && was.qty !== r.qty;
  }).map((r) => [r.name, current.get(r.name_norm).qty, r.qty]);
  // Recomputing in_collection can flip a row either way — a card bought or
  // sold since the deck was recorded. Worth showing, not worth hiding.
  const gapsNow = rows.filter((r) => {
    const was = current.get(r.name_norm);
    return !r.in_collection && was && was.in_collection === 1;
  }).map((r) => r.name);

  const typedCommander = cmdrParsed.items.map((it) => it.name).join(' // ');
  // decks.commander carries hand-written prose after the name ("featured
  // alt commander: ..."), which the editor's plain field cannot show and
  // must not quietly delete. Only a real change to the name rewrites it.
  const newCommander = hasCommanderField && normalize(typedCommander) !== cmdr
    ? (typedCommander || null)
    : null;

  const out = {
    deck: { slug: deck.slug, name: deck.name, owner: deck.owner },
    commander: hasCommanderField ? (typedCommander || null) : deck.commander,
    commander_changed: newCommander !== null,
    rows: rows.length,
    card_count: cardCount,
    owned_count: ownedCount,
    added,
    removed,
    changed,
    newly_missing: gapsNow,
    // Copies that had to be bought to back this list, and copies the edit
    // hands back. Bulk is card_usage.free, so returning is just the deck
    // letting go — there is nothing to write for it.
    acquired: shortfall,
    returned: [...current.values()]
      .map((r) => {
        const still = wanted.get(r.name_norm)?.qty ?? 0;
        return r.in_collection && r.qty > still ? [r.name, r.qty - still] : null;
      })
      .filter(Boolean),
  };

  if (body?.dry_run) return { status: 200, body: { ...out, applied: false, dry_run: true } };

  // Buy first, write the deck second. If Scryfall cannot resolve a name
  // the deck is left exactly as it was, rather than pointing at cards the
  // collection does not have.
  let acquisition = null;
  if (shortfall.length) {
    acquisition = await addCards(db, {
      owner: deck.owner,
      list: shortfall.map(([name, n]) => `${n} ${name}`).join('\n'),
    }, fetchImpl);
    if (acquisition.status !== 200 || acquisition.body?.failed) {
      return {
        status: acquisition.status === 200 ? 400 : acquisition.status,
        body: {
          error: 'could not acquire the cards this list needs, so the deck is unchanged',
          errors: acquisition.body?.errors || [acquisition.body?.error].filter(Boolean),
          acquired: shortfall,
        },
      };
    }
  }

  const statements = [
    db.prepare('DELETE FROM deck_cards WHERE deck_id = ?').bind(deck.id),
    ...rows.map((r) => db.prepare(`
      INSERT INTO deck_cards (deck_id, qty, name, name_norm, raw_name, oracle_id, role, section, in_collection)
      VALUES (?,?,?,?,?,?,?,?,?)`).bind(
      deck.id, r.qty, r.name, r.name_norm, r.raw_name, r.oracle_id, r.role, r.section, r.in_collection,
    )),
    db.prepare('UPDATE decks SET card_count = ?, owned_count = ? WHERE id = ?')
      .bind(cardCount, ownedCount, deck.id),
  ];
  if (hasCommanderField && newCommander !== null) {
    statements.push(db.prepare('UPDATE decks SET commander = ? WHERE id = ?')
      .bind(newCommander, deck.id));
  }
  // One batch: a deck that lost its list but never got the new one back is
  // not a state worth being able to reach.
  await db.batch(statements);

  return {
    status: 200,
    body: {
      ...out,
      applied: true,
      dry_run: false,
      acquired_notes: acquisition?.body?.notes || [],
    },
  };
}
