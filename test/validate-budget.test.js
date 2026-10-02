import { describe, it, expect } from 'vitest';
import { postAnon, stubScryfall } from './helpers.js';
import { SUGGEST_BUDGET_MS } from '../src/validate.js';

// Twenty lines of nonsense used to mean one "did you mean" request per
// line, in turn, each behind a deliberate gap — minutes of spinner to
// be told what the person already knew. The names are all checked
// before any of this runs; suggestions are a courtesy, and a courtesy
// must not be the reason anybody waits.

/** The house stub, made slow, so the budget has something to cut off. */
function slowly(ms) {
  const inner = stubScryfall();
  const impl = async (url, init) => {
    if (String(url).includes('/cards/named')) {
      await new Promise((r) => setTimeout(r, ms));
    }
    return inner(url, init);
  };
  impl.calls = inner.calls;
  return impl;
}

const nonsense = Array.from({ length: 20 }, (_, i) => `Zzyzxqqq${i}`);

describe('a list of nonsense does not hang', () => {
  it('stops asking for suggestions once the budget is spent', async () => {
    const scry = slowly(500);
    const started = Date.now();
    const r = await postAnon('/cards/validate', { names: nonsense }, scry);
    const took = Date.now() - started;

    expect(r.status).toBe(200);
    expect(took).toBeLessThan(SUGGEST_BUDGET_MS + 5000);
    expect(scry.calls.named || 0).toBeLessThan(nonsense.length);
  });

  it('and still reports every one of them as not found', async () => {
    const r = await postAnon('/cards/validate', { names: nonsense }, slowly(500));
    expect(r.body.ok).toBe(false);
    expect(r.body.unknown).toBe(nonsense.length);
    expect(r.body.cards.filter((c) => !c.ok).length).toBe(nonsense.length);
  });

  it('a single bad name still gets asked about', async () => {
    const scry = slowly(1);
    const r = await postAnon('/cards/validate', { names: ['Zzyzxqqq'] }, scry);
    expect(r.status).toBe(200);
    expect(scry.calls.named || 0).toBe(1);
  });

  it('a list that is entirely fine asks for no suggestions at all', async () => {
    const scry = slowly(500);
    const started = Date.now();
    const r = await postAnon('/cards/validate', { names: ['Sol Ring'] }, scry);
    expect(r.body.ok).toBe(true);
    expect(scry.calls.named || 0).toBe(0);
    expect(Date.now() - started).toBeLessThan(2000);
  });
});
