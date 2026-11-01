import { describe, it, expect } from 'vitest';
import { env } from 'cloudflare:test';
import cases from './fixtures/core-sql.json';

// The filter panel's deck list, as the Kotlin core sends it (see
// CoreSqlDump.kt), against the seed. Each row has to say whose it is by
// public key, because that is what a collection is named by and what the
// panel narrows the list with. It must never say so by private id.

describe("the filter panel's deck list", () => {
  it("names each deck's owner by public key and never by id", async () => {
    const c = cases.find((x) => x.sql.startsWith('SELECT key, name,') && x.sql.includes('FROM decks'));
    expect(c, 'core-sql.json has no deck facet').toBeTruthy();
    const { results } = await env.DB.prepare(c.sql).bind(...c.params).all();
    const keys = new Set(results.map((r) => r.owner_key));
    expect(keys).toEqual(new Set(['m4tt0001', 'k4yy0003']));
    for (const r of results) expect(r).not.toHaveProperty('owner_id');
  });
});
