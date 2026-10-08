import { describe, it, expect } from 'vitest';
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
});
