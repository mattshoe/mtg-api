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

// Isolated storage rolls each test back, but the rollback point is whatever
// the database held when the test started — so the schema and fixture have to
// be laid down inside every test's own transaction.
beforeEach(async () => {
  const all = [...statements(schemaSql), ...statements(seedSql)];
  await env.DB.batch(all.map((s) => env.DB.prepare(s)));
});

export { applyD1Migrations };
