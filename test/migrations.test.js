import { describe, it, expect } from 'vitest';
import { env } from 'cloudflare:test';
import schemaSql from '../schema.sql?raw';
import hashesJson from './fixtures/migration-hashes.json?raw';
import baselineJson from './fixtures/schema-baseline.json?raw';

// Imported at build time, because this runs inside workerd and there
// is no filesystem in there to read them from.
const MIGRATION_FILES = import.meta.glob('../migrations/*.sql', {
  query: '?raw',
  import: 'default',
  eager: true,
});

/**
 * The migrations and `schema.sql` describe the same database.
 *
 * They are two halves of one thing and only ever one of them gets
 * edited. `schema.sql` is what a database built from scratch gets —
 * and what this suite builds, which is why every test here can pass
 * against a schema the live database has never been given.
 * `migrations/` is what the live one gets.
 *
 * Both of the ways that can go wrong have now gone wrong in
 * production, a day apart:
 *
 *   - Three tables added to `schema.sql` and no migration written, so
 *     the deploy shipped code querying `users` to a database that had
 *     never heard of it.
 *   - A column added to a migration that had already been applied.
 *     Wrangler records a migration as done by name, so the edit was a
 *     no-op on the live database and would have run twice on a fresh
 *     one. Every sign-in failed on the INSERT.
 *
 * Neither could be caught by any test that builds from `schema.sql`,
 * because both are about the file it does not read. This reads both
 * and holds them to each other.
 */
describe('migrations and schema.sql agree', () => {
  /** Every `CREATE TABLE` name in a .sql file, however it is spelled. */
  const tablesIn = (sql) => new Set(
    [...sql.matchAll(/CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?["'`]?(\w+)/gi)]
      .map((m) => m[1].toLowerCase()),
  );

  /** Every column a migration adds after the fact. */
  const addedColumns = (sql) => new Set(
    [...sql.matchAll(/ALTER\s+TABLE\s+["'`]?(\w+)["'`]?\s+ADD\s+COLUMN\s+["'`]?(\w+)/gi)]
      .map((m) => `${m[1].toLowerCase()}.${m[2].toLowerCase()}`),
  );

  const migrations = Object.entries(MIGRATION_FILES)
    .map(([path, sql]) => ({ name: path.split('/').pop(), sql }))
    .sort((a, b) => a.name.localeCompare(b.name));

  const allMigrations = migrations.map((m) => m.sql).join('\n');

  it('there are migrations to check', () => {
    expect(migrations.length).toBeGreaterThan(0);
  });

  it('every table added since migrations existed has one', () => {
    // The first failure: a table added to one file and not the other.
    //
    // The baseline is the tables that predate `migrations/` — the
    // database was built by applying `schema.sql` by hand, so those
    // have no migration and never will. Everything after that list
    // needs one, and a new table landing in `schema.sql` alone is
    // exactly what this catches.
    const baseline = new Set(JSON.parse(baselineJson).tables);
    const fromMigrations = tablesIn(allMigrations);
    const missing = [...tablesIn(schemaSql)]
      .filter((t) => !baseline.has(t) && !fromMigrations.has(t));
    expect(missing, `no migration creates: ${missing.join(', ')}`).toEqual([]);
  });

  it('every column a migration adds is in schema.sql too', () => {
    // The second: a column that exists only in the live database, or
    // only in a fresh one.
    const missing = [...addedColumns(allMigrations)].filter((qualified) => {
      const column = qualified.split('.')[1];
      return !new RegExp(`^\\s*${column}\\s`, 'mi').test(schemaSql);
    });
    expect(missing, `schema.sql is missing: ${missing.join(', ')}`).toEqual([]);
  });

  it('an applied migration is never edited again', async () => {
    // What made the second failure invisible. A migration is
    // identified by its name, so once one has run anywhere, changing
    // it changes nothing — the fix is always another file.
    //
    // Checked by content: every migration this checkout has, hashed,
    // against the hashes committed the last time this passed. A
    // changed hash on an existing name is the mistake; a new name is
    // ordinary work.
    const seen = JSON.parse(hashesJson);
    const digest = async (text) => {
      const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
      return [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, '0')).join('');
    };
    const edited = [];
    for (const m of migrations) {
      const now = await digest(m.sql);
      if (seen[m.name] && seen[m.name] !== now) edited.push(m.name);
    }
    expect(
      edited,
      `${edited.join(', ')} has already been applied somewhere; add a new migration instead`,
    ).toEqual([]);
  });

  it('the schema this suite runs on has the accounts tables', async () => {
    // Belt and braces: the thing the production failure actually
    // looked like, asked of whatever database this test is given.
    const { results } = await env.DB.prepare(
      `SELECT name FROM sqlite_master WHERE type = 'table'
         AND name IN ('users', 'identities', 'sessions') ORDER BY name`,
    ).all();
    expect(results.map((r) => r.name)).toEqual(['identities', 'sessions', 'users']);
  });

  it('and users has the key its code writes', async () => {
    const { results } = await env.DB.prepare("SELECT name FROM pragma_table_info('users')").all();
    expect(results.map((r) => r.name)).toContain('key');
  });
});
