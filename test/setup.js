import { beforeEach } from 'vitest';
import { env, applyD1Migrations } from 'cloudflare:test';
import schemaSql from '../schema.sql?raw';
import seedSql from './fixtures/seed.sql?raw';

/**
 * Split a .sql file into statements. Naive splitting on ';' breaks on the
 * apostrophes and semicolons that live inside oracle text, so track quotes
 * and comments.
 */
function statements(sql) {
  const out = [];
  let buf = '';
  let i = 0;
  while (i < sql.length) {
    const c = sql[i];
    if (c === "'") {
      buf += c;
      i += 1;
      while (i < sql.length) {
        buf += sql[i];
        if (sql[i] === "'") {
          if (sql[i + 1] === "'") { buf += sql[i + 1]; i += 2; continue; }
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
    if (c === ';') {
      if (buf.trim()) out.push(buf.trim());
      buf = '';
      i += 1;
      continue;
    }
    buf += c;
    i += 1;
  }
  if (buf.trim()) out.push(buf.trim());
  return out;
}

/**
 * D1 refuses a statement with more than a hundred bound parameters.
 *
 * The local database under the test runner does not, so a statement
 * that binds a list twice — a UNION with the same `IN` on both
 * sides, say — passed every test here and then answered "too many
 * SQL variables" against the real thing the moment a deck had more
 * than fifty cards in it. Counting them here is what makes that a
 * test failure instead of a bug report.
 */
const D1_PARAM_LIMIT = 100;

function countingPrepare(db) {
  const real = db.prepare.bind(db);
  return (sql) => {
    const stmt = real(sql);
    const bind = stmt.bind.bind(stmt);
    stmt.bind = (...values) => {
      if (values.length > D1_PARAM_LIMIT) {
        throw new Error(
          `${values.length} bound parameters is over D1's limit of ${D1_PARAM_LIMIT}: `
          + sql.replace(/\s+/g, ' ').slice(0, 160),
        );
      }
      return bind(...values);
    };
    return stmt;
  };
}

// Isolated storage rolls each test back, but the rollback point is whatever
// the database held when the test started — so the schema and fixture have to
// be laid down inside every test's own transaction.
beforeEach(async () => {
  const all = [...statements(schemaSql), ...statements(seedSql)];
  await env.DB.batch(all.map((s) => env.DB.prepare(s)));
  env.DB.prepare = countingPrepare(env.DB);
});

export { applyD1Migrations };
