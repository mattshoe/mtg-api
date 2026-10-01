import { describe, it, expect } from 'vitest';
import { post, postAnon, get, snapshot, stubScryfall } from './helpers.js';

// A typo that reaches the database becomes a card nobody owns and a deck slot
// nothing can fill, so the wizard checks before it writes. The collection is
// asked first because most of what anyone types is already owned, and that
// costs no network.

describe('POST /cards/validate', () => {
  const byName = (body, n) => body.cards.find((c) => c.name === n);

  it('needs no token, and writes nothing', async () => {
    const before = await snapshot();
    const r = await postAnon('/cards/validate', { names: ['Sol Ring'] }, stubScryfall());
    expect(r.status).toBe(200);
    expect(await snapshot()).toEqual(before);
  });

  it('rejects GET', async () => {
    expect((await get('/cards/validate')).status).toBe(405);
  });

  it('wants a list or some names', async () => {
    expect((await post('/cards/validate', {}, stubScryfall())).body.error).toMatch(/list.*names/);
    expect((await post('/cards/validate', { names: [] }, stubScryfall())).body.error).toMatch(/nothing to check/);
  });

  it('confirms a card the collection already holds without asking Scryfall', async () => {
    const stub = stubScryfall();
    const r = await post('/cards/validate', { names: ['Arcane Signet'] }, stub);
    expect(r.body.ok).toBe(true);
    expect(byName(r.body, 'Arcane Signet').source).toBe('collection');
    expect(stub.calls.collection).toBe(0);
  });

  it('confirms a real card nobody owns, via Scryfall', async () => {
    const stub = stubScryfall();
    const r = await post('/cards/validate', { names: ['Lightning Bolt'] }, stub);
    expect(r.body.ok).toBe(true);
    expect(byName(r.body, 'Lightning Bolt').source).toBe('scryfall');
    expect(stub.calls.collection).toBe(1);
  });

  it('flags a name that is not a card', async () => {
    const r = await post('/cards/validate', { names: ['Definitely Not A Card'] }, stubScryfall());
    expect(r.status).toBe(200);
    expect(r.body.ok).toBe(false);
    expect(r.body.unknown).toBe(1);
    expect(byName(r.body, 'Definitely Not A Card').ok).toBe(false);
  });

  it('suggests the real card behind a typo', async () => {
    const r = await post('/cards/validate', { names: ['Lightnin Bolt'] }, stubScryfall());
    expect(r.body.ok).toBe(false);
    expect(byName(r.body, 'Lightnin Bolt').suggestion).toBe('Lightning Bolt');
  });

  it('sorts a mixed list into good and bad', async () => {
    const r = await post('/cards/validate', {
      names: ['Arcane Signet', 'Lightning Bolt', 'Xyzzy Nonesuch'],
    }, stubScryfall());
    expect(r.body.checked).toBe(3);
    expect(r.body.unknown).toBe(1);
    expect(r.body.cards.filter((c) => c.ok).map((c) => c.name))
      .toEqual(['Arcane Signet', 'Lightning Bolt']);
  });

  it('takes a decklist and checks the names in it', async () => {
    const r = await post('/cards/validate', {
      list: '1 Arcane Signet\n4 Lightning Bolt\n2 Xyzzy Nonesuch',
    }, stubScryfall());
    expect(r.body.checked).toBe(3);
    expect(byName(r.body, 'Xyzzy Nonesuch').ok).toBe(false);
  });

  it('counts a repeated name once', async () => {
    const r = await post('/cards/validate', { names: ['Sol Ring', 'Sol Ring', 'sol ring'] }, stubScryfall());
    expect(r.body.checked).toBe(1);
  });

  it('reports an unreadable line rather than checking nonsense', async () => {
    const r = await post('/cards/validate', { list: '0 Sol Ring' }, stubScryfall());
    expect(r.status).toBe(400);
    expect(r.body.errors[0]).toMatch(/at least 1/);
  });

  it('says so when Scryfall cannot be reached, rather than calling the name bad', async () => {
    const r = await post('/cards/validate', { names: ['Lightning Bolt'] }, stubScryfall({ fail: true }));
    expect(r.status).toBe(502);
    expect(r.body.error).toMatch(/could not reach Scryfall/);
  });
});

describe('POST /cards/validate — a list the size of a real deck', () => {
  /**
   * Fifty-one names was the breaking point.
   *
   * The names went into the statement twice, once for `cards` and
   * once for `aliases`, so each one cost two of D1's hundred bound
   * parameters. Every deck in the collection is bigger than fifty
   * cards, so checking any of them answered "too many SQL variables
   * at offset 333" and the new-deck wizard could not get past its
   * own check-the-names step.
   */
  it('checks a hundred names without tripping the parameter limit', async () => {
    const names = Array.from({ length: 100 }, (_, i) => `Made Up Card ${i}`);
    const r = await post('/cards/validate', { names }, stubScryfall());
    expect(r.body.error).toBeUndefined();
    expect(r.status).toBe(200);
    expect(r.body.cards.length).toBe(100);
  }, 20000);

  it('and still finds the ones the collection has, past the first chunk', async () => {
    const filler = Array.from({ length: 95 }, (_, i) => `Made Up Card ${i}`);
    const r = await post('/cards/validate', { names: [...filler, 'Arcane Signet'] }, stubScryfall());
    expect(r.body.error).toBeUndefined();
    const signet = r.body.cards.find((c) => c.name === 'Arcane Signet');
    expect(signet.ok).toBe(true);
  }, 20000);
});
