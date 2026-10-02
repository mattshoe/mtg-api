import { describe, it, expect } from 'vitest';
import { isOverloaded } from '../src/query.js';

// A busy database is worth another go. A broken statement never is,
// and saying otherwise is not a cosmetic mistake: the client retries,
// so one unrunnable query becomes a handful of identical 503s and the
// log says the database was busy when the database was fine. That is
// exactly what "too many SQL variables at offset 333" did.
describe('what is worth retrying', () => {
  it('a database under load is', () => {
    expect(isOverloaded('Network connection lost')).toBe(true);
    expect(isOverloaded('D1_ERROR: Storage overloaded')).toBe(true);
    expect(isOverloaded('queued for too long')).toBe(true);
    expect(isOverloaded('too many requests')).toBe(true);
    expect(isOverloaded('too many connections')).toBe(true);
    // The one Cloudflare actually sends, with words in the middle.
    expect(isOverloaded('too many API requests by single worker invocation')).toBe(true);
    expect(isOverloaded('too many subrequests')).toBe(true);
    expect(isOverloaded('reset because of an internal error')).toBe(true);
  });

  it('a statement that binds past the parameter ceiling is not', () => {
    expect(isOverloaded('too many SQL variables at offset 333: SQLITE_ERROR')).toBe(false);
    expect(isOverloaded('too many SQL variables')).toBe(false);
  });

  it('nor is anything else SQLite calls an error', () => {
    expect(isOverloaded('no such table: card')).toBe(false);
    expect(isOverloaded('no such column: nmae')).toBe(false);
    expect(isOverloaded('near "SELEC": syntax error')).toBe(false);
    expect(isOverloaded('SQLITE_CONSTRAINT: UNIQUE constraint failed')).toBe(false);
  });

  it('and nothing at all is not', () => {
    expect(isOverloaded('')).toBe(false);
    expect(isOverloaded(null)).toBe(false);
    expect(isOverloaded(undefined)).toBe(false);
  });

  it('the words that matter are not matched loosely', () => {
    // "too many" alone used to be enough, which is how the parameter
    // ceiling got mistaken for load in the first place.
    expect(isOverloaded('too many cooks')).toBe(false);
  });
});
