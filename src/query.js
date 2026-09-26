// /query — arbitrary SQL in, compact JSON out.
//
// The response shape is chosen for an agent reading it, not a human: column
// names appear once, rows are plain arrays. On a 500-row result that is about
// a third the bytes of the usual array-of-objects.

const DEFAULT_LIMIT = 5000;
const MAX_LIMIT = 50000;

/**
 * The statement with string literals and comments blanked out, so keyword
 * scanning cannot be fooled by a card named "Delete the Evidence".
 */
export function stripLiterals(sql) {
  let out = '';
  let i = 0;
  while (i < sql.length) {
    const c = sql[i];
    if (c === "'" || c === '"' || c === '`') {
      const quote = c;
      out += ' ';
      i += 1;
      while (i < sql.length) {
        if (sql[i] === quote) {
          if (sql[i + 1] === quote) { i += 2; continue; }
          i += 1;
          break;
        }
        i += 1;
      }
      continue;
    }
    if (c === '-' && sql[i + 1] === '-') {
      while (i < sql.length && sql[i] !== '\n') i += 1;
      continue;
    }
    if (c === '/' && sql[i + 1] === '*') {
      i += 2;
      while (i < sql.length && !(sql[i] === '*' && sql[i + 1] === '/')) i += 1;
      i += 2;
      out += ' ';
      continue;
    }
    out += c;
    i += 1;
  }
  return out;
}

// Anything that could change the database. GET is read-only, so these are
// refused there — a URL that deletes rows is one link-preview or prefetch
// away from doing it by accident. POST has no such restriction.
const MUTATING = /\b(insert|update|delete|drop|create|alter|replace|attach|detach|reindex|vacuum|begin|commit|rollback)\b/i;

/** Is this statement incapable of changing anything? */
export function isReadOnly(sql) {
  const bare = stripLiterals(sql);
  if (MUTATING.test(bare)) return false;
  const head = bare.replace(/^[\s;]*/, '').slice(0, 8).toUpperCase();
  return head.startsWith('SELECT') || head.startsWith('WITH')
    || head.startsWith('VALUES') || head.startsWith('EXPLAIN');
}

/**
 * Is this one statement? A stray semicolon inside a string literal is fine;
 * a second statement after one is not. D1 rejects multi-statement prepares
 * anyway, but its error is opaque and this one says what is wrong.
 */
export function isSingleStatement(sql) {
  let i = 0;
  let sawTerminator = false;
  while (i < sql.length) {
    const c = sql[i];
    if (c === "'" || c === '"' || c === '`') {
      const quote = c;
      i += 1;
      while (i < sql.length) {
        if (sql[i] === quote) {
          if (sql[i + 1] === quote) i += 2; // '' is an escaped quote
          else { i += 1; break; }
        } else i += 1;
      }
      continue;
    }
    if (c === '-' && sql[i + 1] === '-') {
      while (i < sql.length && sql[i] !== '\n') i += 1;
      continue;
    }
    if (c === '/' && sql[i + 1] === '*') {
      i += 2;
      while (i < sql.length && !(sql[i] === '*' && sql[i + 1] === '/')) i += 1;
      i += 2;
      continue;
    }
    if (c === ';') {
      sawTerminator = true;
    } else if (sawTerminator && !/\s/.test(c)) {
      return false; // something after the semicolon
    }
    i += 1;
  }
  return true;
}

/** Statements that return rows and therefore deserve a row cap. */
function isRowReturning(sql) {
  const head = sql.replace(/^\s*(?:--[^\n]*\n|\/\*[\s\S]*?\*\/|\s)*/, '').slice(0, 8).toUpperCase();
  return head.startsWith('SELECT') || head.startsWith('WITH') || head.startsWith('VALUES')
    || head.startsWith('PRAGMA') || head.startsWith('EXPLAIN');
}

/** Does the statement already end in its own LIMIT? */
function hasOwnLimit(sql) {
  return /\blimit\s+(\?|\d|:|\$|@)/i.test(sql.replace(/;\s*$/, '').slice(-200));
}

function toTsv(cols, rows) {
  const cell = (v) => {
    if (v === null || v === undefined) return '';
    return String(v).replace(/\\/g, '\\\\').replace(/\t/g, '\\t').replace(/\r?\n/g, '\\n');
  };
  return [cols.join('\t'), ...rows.map((r) => r.map(cell).join('\t'))].join('\n');
}

export async function runQuery(db, body, { readOnly = false } = {}) {
  const sql = typeof body.sql === 'string' ? body.sql.trim() : '';
  if (!sql) return { status: 400, body: { error: 'sql is required' } };

  if (readOnly && !isReadOnly(sql)) {
    return {
      status: 405,
      body: { error: 'GET /query is read-only; use POST for anything that writes' },
    };
  }

  const params = Array.isArray(body.params) ? body.params : [];
  const fmt = body.fmt || 'rows';
  if (!['rows', 'objects', 'tsv'].includes(fmt)) {
    return { status: 400, body: { error: `unknown fmt "${fmt}" (rows|objects|tsv)` } };
  }

  if (!isSingleStatement(sql)) {
    return { status: 400, body: { error: 'one statement per request' } };
  }

  let limit = body.limit === undefined ? DEFAULT_LIMIT : Number(body.limit);
  if (!Number.isInteger(limit) || limit < 1) {
    return { status: 400, body: { error: 'limit must be a positive integer' } };
  }
  if (limit > MAX_LIMIT) limit = MAX_LIMIT;

  // Wrapping rather than appending: a LIMIT tacked onto the end of a compound
  // SELECT or a statement with a trailing comment lands in the wrong place.
  let effective = sql.replace(/;\s*$/, '');
  let capped = false;
  if (isRowReturning(effective) && !hasOwnLimit(effective)) {
    effective = `SELECT * FROM (\n${effective}\n) LIMIT ${limit + 1}`;
    capped = true;
  }

  let res;
  try {
    res = await db.prepare(effective).bind(...params).all();
  } catch (e) {
    const msg = String(e.cause?.message || e.message || e).replace(/^D1_(ERROR|EXEC_ERROR):\s*/, '');
    return { status: 400, body: { error: msg } };
  }

  let results = res.results || [];
  let truncated = false;
  if (capped && results.length > limit) {
    results = results.slice(0, limit);
    truncated = true;
  }

  // D1 gives no column list for an empty result, so fall back to whatever the
  // first row has. An empty result then has an empty cols array, not null.
  const cols = results.length ? Object.keys(results[0]) : [];
  const rows = results.map((r) => cols.map((c) => r[c] ?? null));

  if (fmt === 'tsv') {
    return { status: 200, text: toTsv(cols, rows), contentType: 'text/tab-separated-values' };
  }

  const out = fmt === 'objects'
    ? { rows: results, n: results.length }
    : { cols, rows, n: results.length };

  if (truncated) out.truncated = limit;
  if (!isRowReturning(sql) && res.meta) {
    out.changes = res.meta.changes ?? 0;
    if (res.meta.last_row_id) out.last_row_id = res.meta.last_row_id;
  }
  return { status: 200, body: out };
}
