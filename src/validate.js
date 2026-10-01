// Do these cards exist?
//
// A decklist is typed by a person, so it contains typos, and a typo that
// reaches the database becomes a card nobody owns and a deck slot nothing
// can fill. The wizard asks this before it lets you go on.
//
// The collection is checked first and Scryfall only for what is left. Most
// of what anyone types is a card already owned, that lookup is free, and it
// keeps a list of familiar cards validating with no network at all.

import { parseList, normalize } from './parse.js';
import { makeClient } from './scryfall.js';

const MAX_NAMES = 1000;
// Scryfall takes 75 identifiers per call; the suggestion lookups after it
// are one call each, so only so many are worth making.
const MAX_SUGGESTIONS = 12;
const CHUNK = 80;   // D1's bound-parameter ceiling is 100

/** Names the collection already knows, by normalized name. */
async function knownLocally(db, keys) {
  const found = new Set();
  for (let i = 0; i < keys.length; i += CHUNK) {
    const slice = keys.slice(i, i + CHUNK);
    const marks = slice.map(() => '?').join(',');
    // The names go in once, as a table to join against, rather than
    // twice as two IN lists. Binding them twice spent two parameters
    // per name, so a list of fifty-one — any real deck — went over
    // D1's ceiling and the check answered "too many SQL variables".
    const values = slice.map(() => '(?)').join(',');
    const r = await db.prepare(
      `WITH want(n) AS (VALUES ${values})
        SELECT DISTINCT c.name_norm AS name_norm FROM cards c JOIN want ON want.n = c.name_norm
         UNION
        SELECT DISTINCT a.alias_norm AS name_norm FROM aliases a JOIN want ON want.n = a.alias_norm`,
    ).bind(...slice).all();
    for (const row of r.results || []) found.add(row.name_norm);
  }
  return found;
}

/**
 * Check a decklist, or a bare list of names, against what exists.
 *
 * @returns {{status: number, body: object}} `cards` carries one entry per
 *   distinct name with `ok`, where it was confirmed, and a `suggestion`
 *   when Scryfall recognised something close.
 */
export async function validateNames(db, body, fetchImpl) {
  let names = [];
  if (Array.isArray(body?.names)) {
    names = body.names.map((n) => String(n || '').trim()).filter(Boolean);
  } else if (typeof body?.list === 'string') {
    const { items, errors } = parseList(body.list);
    if (errors.length) {
      return {
        status: 400,
        body: { error: `${errors.length} line(s) could not be read`, errors },
      };
    }
    names = items.map((i) => i.name);
  } else {
    return { status: 400, body: { error: 'send a `list` string or a `names` array' } };
  }

  if (!names.length) return { status: 400, body: { error: 'nothing to check' } };
  if (names.length > MAX_NAMES) {
    return { status: 400, body: { error: `${names.length} names is over the ${MAX_NAMES} limit` } };
  }

  // One entry per distinct name, in the order first written.
  const byKey = new Map();
  for (const name of names) {
    const key = normalize(name);
    if (!byKey.has(key)) byKey.set(key, { name, name_norm: key, ok: false, source: null, suggestion: null });
  }

  const local = await knownLocally(db, [...byKey.keys()]);
  for (const [key, entry] of byKey) {
    if (local.has(key)) { entry.ok = true; entry.source = 'collection'; }
  }

  const unknown = [...byKey.values()].filter((e) => !e.ok);
  if (unknown.length) {
    const scry = makeClient(fetchImpl);
    try {
      const res = await scry.collection(unknown.map((e) => ({ name: e.name })));
      const hit = new Set((res.data || []).flatMap(
        (c) => [c.name, ...(c.card_faces || []).map((f) => f.name)].map(normalize),
      ));
      for (const e of unknown) {
        if (hit.has(e.name_norm)) { e.ok = true; e.source = 'scryfall'; }
      }
      // A near miss is worth naming. This is the whole point — "did you mean
      // Bitterblossom" beats "no card named Biterblosom".
      const missing = unknown.filter((e) => !e.ok).slice(0, MAX_SUGGESTIONS);
      for (const e of missing) {
        try {
          e.suggestion = (await scry.suggest(e.name)) || null;
        } catch {
          // A suggestion is a nicety; failing to get one is not a failure.
        }
      }
    } catch (e) {
      return {
        status: 502,
        body: { error: `could not reach Scryfall to check these names: ${String(e.message || e)}` },
      };
    }
  }

  const cards = [...byKey.values()];
  const bad = cards.filter((c) => !c.ok);
  return {
    status: 200,
    body: {
      ok: bad.length === 0,
      checked: cards.length,
      unknown: bad.length,
      cards,
    },
  };
}
