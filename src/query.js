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

/**
 * Does this statement even look like a read? Cheap and synchronous, and on
 * its own it already refuses INSERT, UPDATE, DELETE, DROP, ALTER, ATTACH,
 * PRAGMA and the rest, because none of them begin with one of these words.
 *
 * What it cannot see is a write wearing a read's hat:
 * `WITH x AS (...) INSERT INTO ...` begins with WITH. That is what
 * `readOnlyCheck` is for.
 */
export function isReadOnlyShape(sql) {
  const head = stripLiterals(sql).replace(/^[\s;(]*/, '').slice(0, 8).toUpperCase();
  return head.startsWith('SELECT') || head.startsWith('WITH')
    || head.startsWith('VALUES') || head.startsWith('EXPLAIN');
}

/**
 * Opcodes a read cannot produce.
 *
 * Deliberately narrow. Every statement that modifies a table opens a cursor
 * for writing, so `OpenWrite` is the signal; the rest are the schema and
 * connection-level operations that bypass a table cursor.
 *
 * `Insert`, `IdxInsert` and `Delete` are NOT here on purpose: SQLite uses
 * them to fill the ephemeral tables behind GROUP BY, DISTINCT and IN(...),
 * so listing them would refuse ordinary reads.
 */
const WRITE_OPCODES = new Set([
  'OpenWrite', 'Destroy', 'Clear', 'DropTable', 'DropIndex', 'DropTrigger',
  'CreateBtree', 'ParseSchema', 'Attach', 'Detach', 'JournalMode', 'Vacuum',
  'SetCookie', 'VUpdate', 'VCreate', 'VDestroy', 'VRename',
]);

/**
 * Ask SQLite whether the statement writes, instead of guessing from words.
 *
 * `EXPLAIN` compiles the statement and hands back its program without ever
 * running it, which is exactly what `sqlite3_stmt_readonly` inspects. A word
 * list cannot do this job: `replace()` is a string function, so
 * `SELECT replace(name,'a','b')` is a perfectly good read that the old
 * blocklist refused, and `WITH x AS (...) DELETE ...` is a write it could
 * only catch by accident.
 *
 * Returns `{ ok }`, or `{ ok: false, reason, syntax }` when the statement
 * does not compile — in which case the message has offsets into the caller's
 * own SQL, which the wrapped execution below cannot give.
 */
export async function readOnlyCheck(db, sql, params = []) {
  if (!isReadOnlyShape(sql)) {
    return {
      ok: false,
      reason: 'only SELECT, WITH, VALUES and EXPLAIN statements can be read over GET',
    };
  }
  // EXPLAIN and EXPLAIN QUERY PLAN list a program, they never run it, and
  // EXPLAIN cannot be applied to itself.
  if (/^\s*explain\b/i.test(stripLiterals(sql))) return { ok: true };

  let rows;
  try {
    rows = (await db.prepare(`EXPLAIN ${sql}`).bind(...params).all()).results || [];
  } catch (e) {
    return { ok: false, syntax: true, reason: withTruncationHint(cleanError(e), sql) };
  }

  const writes = [...new Set(rows.map((r) => r.opcode).filter((o) => WRITE_OPCODES.has(o)))];
  if (writes.length) {
    return {
      ok: false,
      reason: `that statement writes to the database (${writes.join(', ')}); use POST`,
    };
  }
  return { ok: true };
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

const cleanError = (e) => String(e.cause?.message || e.message || e)
  .replace(/^D1_(ERROR|EXEC_ERROR):\s*/, '');

/** D1 under load, rather than anything wrong with the statement. */
/**
 * Worth trying again, as opposed to wrong.
 *
 * `too many` on its own was too broad: SQLite says "too many SQL
 * variables" for a statement that binds past its parameter ceiling,
 * which is a permanent fault in the query. Calling it retryable told
 * the client to try again, so one broken deck list became six
 * identical 503s in the log and the person reading them was told the
 * database was busy when the database was fine.
 *
 * Anything SQLite names as an error is the statement's fault and is
 * never retryable, whatever words follow.
 */
export const isOverloaded = (message) => {
  const said = String(message || '');
  if (/SQLITE_[A-Z]+|SQL variables|no such (table|column)|syntax error/i.test(said)) return false;
  return /overload|queued for too long|too many (requests|connections|sub-?requests)|network connection lost|reset because of/i
    .test(said);
};

/**
 * A statement that is one bare word almost never means what it says.
 *
 * It means the sql parameter was not URL-encoded: an unencoded space ends
 * the request target, so the server is handed `SELECT` and the rest of the
 * query never arrives. Saying "incomplete input" and stopping there sends
 * people looking for a fault in SQL they did write.
 */
const withTruncationHint = (msg, sql) => (/^\s*\w+\s*$/.test(sql)
  ? `${msg} — received only "${sql.trim()}", which looks truncated; URL-encode the sql parameter`
  : msg);

/**
 * An error message that points at the SQL the caller actually sent.
 *
 * A row-returning statement is executed wrapped in `SELECT * FROM (...)` to
 * cap it, so SQLite's offsets and quoted tokens refer to text the caller
 * never wrote. `sql=SELECT` came back as `near ")": syntax error at offset
 * 23`, which describes the wrapper. Re-compiling the original with EXPLAIN
 * — which never executes it — gets the real complaint.
 */
async function truthfulError(db, e, sql, wrapped, params = []) {
  const raw = cleanError(e);
  if (!wrapped) return raw;
  try {
    await db.prepare(`EXPLAIN ${sql}`).bind(...params).all();
  } catch (inner) {
    return withTruncationHint(cleanError(inner), sql);
  }
  return raw;
}

/**
 * Does this statement need an admin token on POST?
 *
 * Conservative by construction: anything that is not shaped like a read
 * needs one, no questions. The one shape that can hide a write behind a
 * read's opening word is `WITH ... INSERT|UPDATE|DELETE`, and only that
 * shape pays for the extra compile. A SELECT cannot write, so the common
 * path costs nothing.
 *
 * A statement that does not compile is not a write — let it through and
 * let the caller see the syntax error rather than a demand for a password.
 */
export async function mayWrite(db, sql) {
  if (typeof sql !== 'string' || !isReadOnlyShape(sql)) return true;
  if (!/^\s*with\b/i.test(stripLiterals(sql))) return false;
  const verdict = await readOnlyCheck(db, sql);
  return !verdict.ok && !verdict.syntax;
}

export async function runQuery(db, body, { readOnly = false } = {}) {
  const sql = typeof body.sql === 'string' ? body.sql.trim() : '';
  if (!sql) return { status: 400, body: { error: 'sql is required' } };

  const params = Array.isArray(body.params) ? body.params : [];
  const fmt = body.fmt || 'rows';
  if (!['rows', 'objects', 'tsv'].includes(fmt)) {
    return { status: 400, body: { error: `unknown fmt "${fmt}" (rows|objects|tsv)` } };
  }

  if (!isSingleStatement(sql)) {
    return { status: 400, body: { error: 'one statement per request' } };
  }

  if (readOnly) {
    const verdict = await readOnlyCheck(db, sql, params);
    if (!verdict.ok) {
      // A statement that does not compile is a bad request; one that
      // compiles and writes is the wrong method for this endpoint.
      return verdict.syntax
        ? { status: 400, body: { error: verdict.reason } }
        : { status: 405, body: { error: `GET /query is read-only: ${verdict.reason}` } };
    }
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
  const isExplain = /^\s*explain\b/i.test(stripLiterals(effective));
  if (isRowReturning(effective) && !hasOwnLimit(effective) && !isExplain) {
    effective = `SELECT * FROM (\n${effective}\n) LIMIT ${limit + 1}`;
    capped = true;
  }

  let res;
  const started = Date.now();
  try {
    res = await db.prepare(effective).bind(...params).all();
  } catch (e) {
    const said = await truthfulError(db, e, sql, capped, params);
    // D1 saying it is busy is not the statement's fault and not a
    // permanent answer. Reported as a 400 it reached the screen as
    // "Search failed"; as a 503 the client's backoff absorbs it.
    return isOverloaded(said)
      ? { status: 503, body: { error: said }, ms: Date.now() - started, failed: said }
      : { status: 400, body: { error: said }, ms: Date.now() - started, failed: said };
  }
  const ms = Date.now() - started;

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
    return { status: 200, text: toTsv(cols, rows), contentType: 'text/tab-separated-values', ms };
  }

  const out = fmt === 'objects'
    ? { rows: results, n: results.length }
    : { cols, rows, n: results.length };

  if (truncated) out.truncated = limit;
  if (!isRowReturning(sql) && res.meta) {
    out.changes = res.meta.changes ?? 0;
    if (res.meta.last_row_id) out.last_row_id = res.meta.last_row_id;
  }
  return { status: 200, body: out, ms };
}
