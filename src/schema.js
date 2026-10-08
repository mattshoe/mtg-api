// /schema — what an agent needs to write a correct query, and nothing else.
//
// Deliberately terse. An agent pays for every token of this before it has
// asked its real question, so columns are a compact type-suffixed list rather
// than an array of objects, and the prose is limited to the things that are
// not guessable from the names.

const NOTES = [
  'One row in `cards` per (owner_id, printing, finish). `qty` is how many of that stack are owned.',
  'Join keys: cards.name_norm = lower(trim(name)); deck_cards.name_norm and totals.name_norm match it. cards.id <- card_*.card_id. cards.oracle_id <- legalities/rulings/oracle-level joins.',
  'owner_id is the owning account; collections are never merged - filter on it. A collection is named publicly by users.key: owner_id = (SELECT id FROM users WHERE key = ?). The owner and slug columns are retired and read by nothing.',
  'totals, card_usage, bulk_cards are views over cards; bulk_cards is the unassigned pool (free > 0).',
  'card_tags.card_id joins cards.id; tag is a Scryfall Tagger tag name. A new printing of a card already here copies its tags; a card new to the database waits for the nightly scripts/tags.mjs.',
  'card_search is FTS5 over (name, type_line, oracle_text, flavor_text, keywords, tags), porter-stemmed. Its rowid is cards.id.',
  'legalities only stores statuses other than not_legal; an absent row means not legal.',
];

// Internal bookkeeping nobody querying the collection needs to see.
const HIDDEN = /^(sqlite_|_cf_|d1_|card_search_(data|idx|content|docsize|config)$)/;

let cached = null;

export async function getSchema(db) {
  if (cached) return cached;

  const objects = await db
    .prepare(
      `SELECT name, type, sql FROM sqlite_master
        WHERE type IN ('table','view') ORDER BY type, name`,
    )
    .all();

  const tables = [];
  const views = [];

  for (const o of objects.results || []) {
    if (HIDDEN.test(o.name)) continue;

    const info = await db.prepare(`PRAGMA table_info(${o.name})`).all();
    const cols = (info.results || [])
      .map((c) => (c.type ? `${c.name}:${c.type.toLowerCase()}` : c.name))
      .join(' ');

    let rowCount = null;
    try {
      const c = await db.prepare(`SELECT COUNT(*) AS n FROM ${o.name}`).first();
      rowCount = c ? c.n : null;
    } catch {
      rowCount = null; // a view can fail to execute; say nothing rather than 500
    }

    if (o.type === 'view') {
      views.push({ name: o.name, columns: cols, rows: rowCount });
    } else {
      tables.push({ name: o.name, columns: cols, rows: rowCount });
    }
  }

  const idx = await db
    .prepare(
      `SELECT tbl_name, name FROM sqlite_master
        WHERE type = 'index' AND sql IS NOT NULL ORDER BY tbl_name, name`,
    )
    .all();
  const indexes = {};
  for (const r of idx.results || []) {
    if (HIDDEN.test(r.tbl_name)) continue;
    (indexes[r.tbl_name] ||= []).push(r.name);
  }

  cached = {
    database: 'mtg',
    tables,
    views,
    indexes,
    notes: NOTES,
    query_endpoint: 'POST /query {"sql":"...","params":[],"fmt":"rows|objects|tsv"}',
  };
  return cached;
}

/** Tests mutate the database between cases; the cache must not outlive that. */
export function clearSchemaCache() {
  cached = null;
}
