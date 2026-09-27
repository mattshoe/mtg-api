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
const claimedElsewhere = (db, owner, deckId, keys) => claimedBy(db, owner, deckId, keys);

/** The same question, for any owner and any deck to exclude. */
async function claimedBy(db, owner, exceptDeckId, keys) {
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
       GROUP BY dc.name_norm`).bind(owner, exceptDeckId, ...slice).all();
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

const OTHER = { matt: 'kayla', kayla: 'matt' };

/**
 * What each card could be covered by, before anything is decided.
 *
 * Three places a copy can come from, and the wizard asks which:
 *   - `own_free`   spare in this owner's collection, committed to no deck
 *   - `other_free` spare in the other collection, which can be transferred
 *   - anything left is new, and has to be bought
 */
async function sourcingPlan(db, owner, wanted, ownedIdx, elsewhereIdx) {
  const other = OTHER[owner];
  const keys = [...wanted.keys()];
  const [otherOwned, otherClaimed] = other
    ? await Promise.all([ownedIndex(db, other, keys), claimedBy(db, other, -1, keys)])
    : [new Map(), new Map()];

  const plan = [];
  for (const w of wanted.values()) {
    const ownHave = ownedIdx.get(w.name_norm)?.owned_qty ?? 0;
    const ownFree = Math.max(0, ownHave - (elsewhereIdx.get(w.name_norm) ?? 0));
    const otherHave = otherOwned.get(w.name_norm)?.owned_qty ?? 0;
    const otherFree = Math.max(0, otherHave - (otherClaimed.get(w.name_norm) ?? 0));
    plan.push({
      name: w.typed,
      name_norm: w.name_norm,
      need: w.qty,
      own_free: ownFree,
      other_free: otherFree,
      other_owner: other || null,
      basic: isBasic(w.name_norm),
    });
  }
  return plan;
}

/**
 * Turn the plan plus the caller's choices into counts.
 *
 * `sources` is name_norm -> 'bulk' | 'transfer' | 'buy', and decides only
 * where the copies bulk cannot cover come from. 'buy' is the exception: it
 * means leave bulk alone entirely and get new copies, which is what someone
 * picking "net-new" for a card they already own is asking for.
 */
function decideSources(plan, sources = {}, physical = true) {
  const out = [];
  for (const p of plan) {
    const choice = sources[p.name_norm] || 'bulk';
    if (!physical || p.basic) {
      out.push({ ...p, choice, from_bulk: p.need, transfer: 0, buy: 0 });
      continue;
    }
    const fromBulk = choice === 'buy' ? 0 : Math.min(p.need, p.own_free);
    let left = p.need - fromBulk;
    const transfer = choice === 'transfer' ? Math.min(left, p.other_free) : 0;
    left -= transfer;
    out.push({ ...p, choice, from_bulk: fromBulk, transfer, buy: left });
  }
  return out;
}

/**
 * Move copies of a card between collections.
 *
 * A transfer is a real inventory move: the other owner loses the copies and
 * this owner gains them, printing for printing. The new rows are copies of
 * the old ones rather than a fresh Scryfall lookup, because the card is
 * already described correctly — and that keeps a transfer working offline.
 */
async function transferStatements(db, from, to, byName) {
  const statements = [];
  const names = [...byName.keys()];
  if (!names.length) return statements;

  const max = await db.prepare('SELECT COALESCE(MAX(id), 0) AS n FROM cards').first();
  let nextId = (max?.n ?? 0) + 1;

  const CHILD = ['card_faces', 'card_colors', 'card_types', 'card_keywords',
    'card_finishes', 'card_games', 'card_promo_types', 'card_frame_effects', 'card_tags'];

  for (let i = 0; i < names.length; i += CHUNK) {
    const slice = names.slice(i, i + CHUNK);
    const marks = slice.map(() => '?').join(',');
    const src = await db.prepare(`
      SELECT id, name_norm, scryfall_id, finish, qty
        FROM cards WHERE owner = ? AND name_norm IN (${marks}) AND qty > 0
       ORDER BY finish = 'nonfoil' DESC, id`).bind(from, ...slice).all();
    const dst = await db.prepare(`
      SELECT id, scryfall_id, finish, qty
        FROM cards WHERE owner = ? AND name_norm IN (${marks})`).bind(to, ...slice).all();

    const destBy = new Map((dst.results || []).map((r) => [`${r.scryfall_id}|${r.finish}`, r]));

    for (const row of src.results || []) {
      let want = byName.get(row.name_norm) || 0;
      if (want <= 0) continue;
      const take = Math.min(want, row.qty);
      byName.set(row.name_norm, want - take);

      // Out of the giver's stack, and the row goes when it empties.
      if (take === row.qty) {
        statements.push(db.prepare('DELETE FROM cards WHERE id = ?').bind(row.id));
        statements.push(db.prepare('DELETE FROM card_search WHERE rowid = ?').bind(row.id));
        for (const t of CHILD) {
          statements.push(db.prepare(`DELETE FROM ${t} WHERE card_id = ?`).bind(row.id));
        }
      } else {
        statements.push(db.prepare('UPDATE cards SET qty = qty - ? WHERE id = ?').bind(take, row.id));
      }

      // Into the receiver's, merging with the same printing where there is one.
      const key = `${row.scryfall_id}|${row.finish}`;
      const hit = destBy.get(key);
      if (hit) {
        statements.push(db.prepare('UPDATE cards SET qty = qty + ? WHERE id = ?').bind(take, hit.id));
      } else {
        const newId = nextId;
        nextId += 1;
        statements.push(db.prepare(`
          INSERT INTO cards SELECT ?, ?, ?, finish, foil_flag, scryfall_id, oracle_id,
            name, name_norm, face1, face2, mana_cost, cmc, oracle_text, flavor_text,
            power, toughness, loyalty, defense, type_line, supertypes, types, subtypes,
            colors, color_identity, color_identity_count, produced_mana, rarity, setcode,
            set_name, set_type, released_at, collector_number, artist, layout, frame,
            border_color, watermark, security_stamp, reserved, game_changer, full_art,
            textless, promo, reprint, variation, oversized, story_spotlight, booster,
            edhrec_rank FROM cards WHERE id = ?`).bind(newId, to, take, row.id));
        for (const t of CHILD) {
          const cols = t === 'card_tags' ? 'tag_slug, kind'
            : t === 'card_faces' ? 'face_index, name, mana_cost, type_line, oracle_text, flavor_text, power, toughness, loyalty, defense, artist, colors'
              : COLUMN_OF[t];
          statements.push(db.prepare(
            `INSERT INTO ${t} SELECT ?, ${cols} FROM ${t} WHERE card_id = ?`,
          ).bind(newId, row.id));
        }
        statements.push(db.prepare(`
          INSERT INTO card_search (rowid, name, type_line, oracle_text, flavor_text, keywords, tags)
          SELECT ?, name, type_line, oracle_text, flavor_text, NULL, NULL FROM cards WHERE id = ?`)
          .bind(newId, row.id));
        destBy.set(key, { id: newId, qty: take });
      }
    }
  }
  return statements;
}

/** The non-key column of each simple child table, for a copying insert. */
const COLUMN_OF = {
  card_colors: 'color, kind',
  card_types: 'type, kind',
  card_keywords: 'keyword',
  card_finishes: 'finish',
  card_games: 'game',
  card_promo_types: 'promo_type',
  card_frame_effects: 'frame_effect',
};

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

  const deck = await db.prepare(
    'SELECT id, slug, name, owner, commander, is_proxy, status FROM decks WHERE slug = ?',
  ).bind(slug).first();
  if (!deck) return { status: 404, body: { error: `no deck with slug "${slug}"` } };

  return planAndWrite(db, deck, body, fetchImpl);
}

/**
 * The work behind both editing a list and creating a deck with one.
 *
 * `deck` is a row for an existing deck, or a deck-shaped object with
 * `id: null` for one that does not exist yet — everything here treats an
 * absent id as "no deck_cards rows and no prior claim", which is exactly
 * what a new deck is.
 */
async function planAndWrite(db, deck, body, fetchImpl) {
  if (typeof body?.list !== 'string') return { status: 400, body: { error: 'list must be a string' } };

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
    return {
      status: 400,
      body: {
        error: deck.id
          ? 'the list is empty — disassemble the deck instead'
          : 'the list is empty',
      },
    };
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
    deck.id
      ? db.prepare(
        'SELECT qty, name, name_norm, role, section, in_collection FROM deck_cards WHERE deck_id = ?',
      ).bind(deck.id).all()
      : { results: [] },
    claimedElsewhere(db, deck.owner, deck.id ?? -1, keys),
  ]);

  // A proxy or merely PROPOSED deck is not made of real cards — card_usage
  // does not count it against anything — so it neither draws from bulk nor
  // causes anything to be acquired.
  const physical = !deck.is_proxy && !(deck.status || '').startsWith('PROPOSED');
  const current = new Map((currentRows.results || []).map((r) => [r.name_norm, r]));
  const cmdr = commanderNorm(deck.commander);
  // A deck being created has no stored commander to compare against, so
  // whatever the field says is by definition the new one.

  // Where every copy is coming from. Putting a card in a deck means it came
  // from somewhere: out of bulk if a copy was spare, out of the other
  // collection if the caller said to transfer it, and otherwise bought — so
  // the collection is made to say so rather than the deck claiming a card
  // that does not exist.
  //
  // `own_free` is clamped at zero: another deck being short already is that
  // deck's problem, and editing this one should make this one whole rather
  // than pay off a debt somewhere else.
  const plan = await sourcingPlan(db, deck.owner, wanted, owned, elsewhere);
  const decided = decideSources(plan, body?.sources, physical);
  const shortfall = decided.filter((d) => d.buy > 0).map((d) => [d.name, d.buy]);
  const transfers = decided.filter((d) => d.transfer > 0);

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
    transferred: transfers.map((d) => [d.name, d.transfer, d.other_owner]),
    // What every card could come from, so the caller can choose before
    // committing rather than discover afterwards.
    sourcing: decided.map((d) => ({
      name: d.name,
      name_norm: d.name_norm,
      need: d.need,
      own_free: d.own_free,
      other_free: d.other_free,
      other_owner: d.other_owner,
      basic: d.basic,
      choice: d.choice,
      from_bulk: d.from_bulk,
      transfer: d.transfer,
      buy: d.buy,
    })),
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
    const purchase = {
      owner: deck.owner,
      list: shortfall.map(([name, n]) => `${n} ${name}`).join('\n'),
    };
    const refuse = (a) => ({
      status: a.status === 200 ? 400 : a.status,
      body: {
        error: 'could not acquire the cards this list needs, so nothing was changed',
        errors: a.body?.errors || [a.body?.error].filter(Boolean),
        acquired: shortfall,
      },
    });

    // Check the whole purchase resolves before any of it is written.
    // /cards/add applies the lines it understood and reports the rest,
    // which is right for a bare import but wrong here: a deck that failed
    // to save must not leave half its shopping list in the collection.
    const check = await addCards(db, { ...purchase, dry_run: true }, fetchImpl);
    if (check.status !== 200 || check.body?.failed) return refuse(check);

    acquisition = await addCards(db, purchase, fetchImpl);
    if (acquisition.status !== 200 || acquisition.body?.failed) return refuse(acquisition);
  }

  // Transfers move real rows between collections, so they go in the same
  // batch as the deck: a deck that claims transferred cards while the
  // transfer failed would be claiming someone else's.
  const statements = [];
  if (transfers.length) {
    const byName = new Map(transfers.map((d) => [d.name_norm, d.transfer]));
    statements.push(...await transferStatements(db, OTHER[deck.owner], deck.owner, byName));
  }
  let deckId = deck.id;

  if (!deckId) {
    // Pick the id rather than relying on last_insert_rowid() between
    // statements, so creating the deck and filling it stay one batch and
    // a failure cannot leave an empty deck behind. The UNIQUE slug is
    // what actually guards against a collision.
    const max = await db.prepare('SELECT COALESCE(MAX(id), 0) AS n FROM decks').first();
    deckId = (max?.n ?? 0) + 1;
    statements.push(db.prepare(`
      INSERT INTO decks (id, slug, name, owner, format, recorded_date, status,
                         colors, commander, theme, bracket, is_proxy, card_count, owned_count)
      VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)`).bind(
      deckId, deck.slug, deck.name, deck.owner, deck.format,
      new Date().toISOString().slice(0, 10), deck.status ?? null,
      deck.colors ?? null, deck.commander ?? null, deck.theme ?? null,
      deck.bracket ?? null, deck.is_proxy ? 1 : 0, cardCount, ownedCount,
    ));
  } else {
    statements.push(db.prepare('DELETE FROM deck_cards WHERE deck_id = ?').bind(deckId));
  }

  statements.push(...rows.map((r) => db.prepare(`
      INSERT INTO deck_cards (deck_id, qty, name, name_norm, raw_name, oracle_id, role, section, in_collection)
      VALUES (?,?,?,?,?,?,?,?,?)`).bind(
    deckId, r.qty, r.name, r.name_norm, r.raw_name, r.oracle_id, r.role, r.section, r.in_collection,
  )));

  if (deck.id) {
    statements.push(db.prepare('UPDATE decks SET card_count = ?, owned_count = ? WHERE id = ?')
      .bind(cardCount, ownedCount, deckId));
    if (hasCommanderField && newCommander !== null) {
      statements.push(db.prepare('UPDATE decks SET commander = ? WHERE id = ?')
        .bind(newCommander, deckId));
    }
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

// -------------------------------------------------------------- creating

/**
 * The formats the wizard offers. Commander is the only one that has a
 * commander or a bracket, which is what the wizard branches on.
 */
export const FORMATS = [
  { id: 'commander', label: 'Commander / EDH', singleton: true, size: 100 },
  { id: 'brawl', label: 'Brawl', singleton: true, size: 60 },
  { id: 'standard', label: 'Standard', size: 60 },
  { id: 'pioneer', label: 'Pioneer', size: 60 },
  { id: 'modern', label: 'Modern', size: 60 },
  { id: 'legacy', label: 'Legacy', size: 60 },
  { id: 'vintage', label: 'Vintage', size: 60 },
  { id: 'pauper', label: 'Pauper', size: 60 },
  { id: 'limited', label: 'Limited / Draft', size: 40 },
  { id: 'casual', label: 'Casual / kitchen table' },
];

const FORMAT_IDS = new Set(FORMATS.map((f) => f.id));
const COMMANDER_FORMATS = new Set(FORMATS.filter((f) => f.singleton).map((f) => f.id));
const OWNERS = new Set(['matt', 'kayla']);

/** A name -> a slug that is safe in a URL and unlikely to collide. */
export function slugify(name) {
  return String(name || '')
    .toLowerCase()
    .normalize('NFKD').replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 60);
}

/**
 * Create a deck from the wizard, list and all.
 *
 * Everything the wizard asks for is validated here rather than only in
 * the browser, because the browser is not the only caller and a deck with
 * no owner or a duplicate slug is not worth being able to make.
 */
export async function createDeck(db, body, fetchImpl) {
  const name = String(body?.name || '').trim();
  const format = String(body?.format || '').trim().toLowerCase();
  const owner = String(body?.owner || '').trim().toLowerCase();

  if (!name) return { status: 400, body: { error: 'the deck needs a name' } };
  if (name.length > 200) return { status: 400, body: { error: 'that name is too long' } };
  if (!FORMAT_IDS.has(format)) {
    return { status: 400, body: { error: 'pick a format', formats: [...FORMAT_IDS] } };
  }
  if (!OWNERS.has(owner)) {
    return { status: 400, body: { error: 'pick whose deck this is', owners: [...OWNERS] } };
  }

  const wantsCommander = COMMANDER_FORMATS.has(format);
  const commander = wantsCommander ? String(body?.commander || '').trim() : '';
  if (wantsCommander && !commander) {
    return { status: 400, body: { error: `a ${format} deck needs a commander` } };
  }

  const bracket = wantsCommander && body?.bracket ? String(body.bracket).trim() : null;
  if (bracket && !/^[1-5]$/.test(bracket)) {
    return { status: 400, body: { error: 'bracket is 1 to 5' } };
  }

  const slug = String(body?.slug || '').trim() || slugify(name);
  if (!slug) return { status: 400, body: { error: 'that name does not make a usable slug' } };
  const clash = await db.prepare('SELECT slug FROM decks WHERE slug = ?').bind(slug).first();
  if (clash) {
    return { status: 409, body: { error: `a deck already exists at "${slug}"`, slug } };
  }

  const deck = {
    id: null,
    slug,
    name,
    owner,
    format,
    commander: commander || null,
    theme: String(body?.theme || '').trim() || null,
    bracket,
    is_proxy: body?.is_proxy ? 1 : 0,
    status: body?.status ? String(body.status).trim() : null,
    colors: null,
  };

  const r = await planAndWrite(db, deck, { ...body, commander }, fetchImpl);
  if (r.status !== 200) return r;
  return { status: r.body.applied ? 201 : 200, body: { ...r.body, created: r.body.applied, slug } };
}
