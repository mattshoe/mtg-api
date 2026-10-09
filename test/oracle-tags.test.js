import { describe, it, expect, beforeEach } from 'vitest';
import { env } from 'cloudflare:test';
import cases from './fixtures/core-sql.json';
import { sql } from './helpers.js';

// `otag:` the way Scryfall does it, run as the SQL the Kotlin core really
// sends (see CoreSqlDump.kt) against the seed. `otag:blink` worked on
// Scryfall and found nothing here, because `blink` is an alias of
// `flicker` and most of what `flicker` means is carried by its children.
// The seed has the same shape with `removal`, which tags no card directly,
// and `acceleration`, an alias of `ramp`.

async function names(caseName) {
  const c = cases.find((x) => x.name === caseName);
  expect(c, `core-sql.json has no case "${caseName}"`).toBeTruthy();
  const r = await env.DB.prepare(c.sql).bind(...c.params).all();
  return r.results.map((row) => row.name);
}

const named = async (...ids) =>
  (await sql(`SELECT name FROM cards WHERE id IN (${ids.join(',')})`)).map((r) => r.name);

describe('otag:', () => {
  it('finds a card tagged only with a child of the tag searched for', async () => {
    // 97 carries removal-destroy, 168 removal-bounce, 274 removal-creature.
    const found = await names('otag: a tag only its children carry');
    expect(found, 'otag:removal found nothing, so children are not being searched').not.toEqual([]);
    for (const n of await named(97, 168, 274)) expect(found).toContain(n);
  });

  it('finds what a tag finds when it is searched by an alias', async () => {
    // 1297 carries ramp itself, 14 carries land-ramp beneath it.
    const found = await names('otag: an alias');
    for (const n of await named(1297, 14)) {
      expect(found, `otag:acceleration missed ${n}`).toContain(n);
    }
  });

  it('excludes the children too', async () => {
    const found = await names('otag: excluded');
    expect(found.length, 'the exclusion matched nothing at all').toBeGreaterThan(0);
    for (const n of await named(97, 168, 274)) expect(found).not.toContain(n);
  });

  it('the filter panel tag finds the children too', async () => {
    const found = await names('tag filter: a tag only its children carry');
    for (const n of await named(97, 168, 274)) expect(found).toContain(n);
  });

  it('the filter panel tag does not care how the phone capitalised it', async () => {
    const found = await names('tag filter: typed with a capital');
    expect(found, '" Removal" found nothing, so the tag is matched as typed').not.toEqual([]);
    for (const n of await named(97, 168, 274)) expect(found).toContain(n);
  });
});

// Everything above runs on the seed, whose `tag_names` was written by
// hand into seed.sql. The live database got the table from 0008 and
// nothing else: only the nightly scripts/tags.mjs fills it, and on the
// day #56 shipped it had not run, so `tag_names` was empty and filtering
// by blink found not one card. So these start from what the live
// database had — card tags, an empty `tag_names` — and run the
// migrations a deploy runs.
const MIGRATIONS = import.meta.glob('../migrations/*.sql', {
  query: '?raw',
  import: 'default',
  eager: true,
});

/** Every migration from 0008 on, statement by statement, as wrangler runs them. */
async function migrateFrom0008() {
  const files = Object.entries(MIGRATIONS)
    .filter(([path]) => path.split('/').pop() >= '0008')
    .sort(([a], [b]) => a.localeCompare(b));
  for (const [, text] of files) {
    const body = text.split('\n').filter((l) => !l.trim().startsWith('--')).join('\n');
    for (const s of body.split(';').map((x) => x.trim()).filter(Boolean)) {
      await env.DB.prepare(s).run();
    }
  }
}

/** A core-sql.json case with the word it searches for swapped. */
async function searching(caseName, from, to) {
  const c = cases.find((x) => x.name === caseName);
  expect(c, `core-sql.json has no case "${caseName}"`).toBeTruthy();
  expect(c.params, `"${caseName}" does not search for ${from}`).toContain(from);
  const params = c.params.map((p) => (p === from ? to : p));
  const r = await env.DB.prepare(c.sql).bind(...params).all();
  return r.results.map((row) => row.name);
}

describe('otag: on a database the migrations built', () => {
  beforeEach(async () => {
    await sql('DELETE FROM tag_names');
    // 97 is tagged the way Tagger tags a blink creature: with a child
    // of flicker, never flicker itself and never blink.
    await sql("INSERT INTO card_tags (card_id, tag, kind) VALUES (97, 'flicker-creature', 'oracle')");
    await migrateFrom0008();
  });

  it('filtering by blink finds a card tagged with a child of flicker', async () => {
    const found = await searching('tag filter: a tag only its children carry', 'removal', 'blink');
    expect(
      found,
      'blink found nothing: tag_names is empty unless the nightly has run, so the alias means nothing',
    ).toEqual(await named(97));
  });

  it('otag:blink in the query box finds it too', async () => {
    const found = await searching('otag: an alias', 'acceleration', 'blink');
    expect(found, 'otag:blink found nothing on a freshly migrated database').toEqual(await named(97));
  });

  it('otag:removal finds the cards only its children tag', async () => {
    const found = await names('otag: a tag only its children carry');
    expect(found, 'otag:removal found nothing on a freshly migrated database').not.toEqual([]);
    for (const n of await named(97, 168, 274)) expect(found).toContain(n);
  });
});
