import { describe, it, expect } from 'vitest';
import { env } from 'cloudflare:test';
import { call } from './helpers.js';
import { newSession } from '../src/accounts.js';

// Matt: "making a fetch list on web does not show on android and vice
// versa". The saved lists lived in localStorage and SharedPreferences,
// one copy per device. They are the account's now, kept here, and both
// apps read and write the same row.

const MATT = 1;
const KAYLA = 3;

const list = (id, title, ticked = []) => ({
  id, title, at: '2026-10-17T12:00:00Z', list: '1 Counterspell', result: { sections: [], missing: [] }, ticked,
});

const get = (token) => call('/fetch-lists', { token });
const put = (token, lists) => call('/fetch-lists', { method: 'POST', token, body: { lists } });

describe('saved Fetch lists, kept against the account', () => {
  it('a list saved from one device is there when another asks', async () => {
    const web = await newSession(env.DB, MATT);
    const phone = await newSession(env.DB, MATT);
    expect((await put(web, [list('a', 'Alela')])).status).toBe(200);
    const r = await get(phone);
    expect(r.status).toBe(200);
    expect(r.body.lists.map((l) => l.title), 'the phone did not see the list the website saved').toEqual(['Alela']);
  });

  it('keeps every field the app sends, ticks included, in the order sent', async () => {
    const t = await newSession(env.DB, MATT);
    await put(t, [list('b', 'Second', ['Counterspell']), list('a', 'First')]);
    const r = await get(t);
    expect(r.body.lists).toEqual([list('b', 'Second', ['Counterspell']), list('a', 'First')]);
  });

  it('a save replaces what was kept, so a list deleted on one device is gone on the other', async () => {
    const t = await newSession(env.DB, MATT);
    await put(t, [list('a', 'Alela'), list('b', 'Bulk')]);
    await put(t, [list('b', 'Bulk')]);
    expect((await get(t)).body.lists.map((l) => l.id)).toEqual(['b']);
  });

  it('is nothing at all for an account that has never saved one', async () => {
    const t = await newSession(env.DB, KAYLA);
    const r = await get(t);
    expect(r.status).toBe(200);
    expect(r.body.lists).toEqual([]);
  });

  it('is yours alone: somebody else signed in never sees them', async () => {
    await put(await newSession(env.DB, MATT), [list('a', 'Alela')]);
    const r = await get(await newSession(env.DB, KAYLA));
    expect(r.body.lists, "Kayla was shown Matt's lists").toEqual([]);
  });

  it('needs somebody signed in to read or write', async () => {
    expect((await call('/fetch-lists')).status).toBe(401);
    expect((await call('/fetch-lists', { method: 'POST', body: { lists: [] } })).status).toBe(401);
  });

  it('refuses a body with no lists in it, rather than wiping what was kept', async () => {
    const t = await newSession(env.DB, MATT);
    await put(t, [list('a', 'Alela')]);
    const r = await call('/fetch-lists', { method: 'POST', token: t, body: { nope: 1 } });
    expect(r.status).toBe(400);
    expect(r.body.error).toMatch(/lists/);
    expect((await get(t)).body.lists.map((l) => l.id), 'a bad body wiped the saved lists').toEqual(['a']);
  });

  it('refuses a list with no id or no title', async () => {
    const t = await newSession(env.DB, MATT);
    const r = await put(t, [{ title: 'No id' }]);
    expect(r.status).toBe(400);
  });
});
