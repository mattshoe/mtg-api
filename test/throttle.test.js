import { describe, it, expect } from 'vitest';
import { makeThrottle } from '../src/throttle.js';

// Workers freeze Date.now() between I/O as a timing-attack mitigation, so
// a gap computed from the clock collapses to zero and the loop runs flat
// out. That is exactly what earned a 429 on batch 21 of the first real
// price refresh. These guard the fix.

describe('makeThrottle', () => {
  it('never lets two calls overlap', async () => {
    const throttle = makeThrottle({ gapMs: 1 });
    let inFlight = 0;
    let maxInFlight = 0;
    const impl = async () => {
      inFlight += 1;
      maxInFlight = Math.max(maxInFlight, inFlight);
      try {
        await new Promise((r) => setTimeout(r, 5));
        return new Response('{}', { status: 200 });
      } finally { inFlight -= 1; }
    };
    for (let i = 0; i < 5; i += 1) await throttle(impl, 'https://x.test', {});
    expect(maxInFlight).toBe(1);
  });

  it('sleeps between calls without consulting the clock', async () => {
    const throttle = makeThrottle({ gapMs: 20 });
    const sleeps = [];
    const realTimeout = globalThis.setTimeout;
    globalThis.setTimeout = (fn, ms) => { sleeps.push(ms); return realTimeout(fn, 0); };
    try {
      const impl = async () => new Response('{}', { status: 200 });
      for (let i = 0; i < 4; i += 1) await throttle(impl, 'https://x.test', {});
    } finally {
      globalThis.setTimeout = realTimeout;
    }
    // First call goes straight out; the other three each wait.
    expect(sleeps.filter((m) => m === 20)).toHaveLength(3);
  });

  it('retries a 429 and then succeeds', async () => {
    const throttle = makeThrottle({ gapMs: 1 });
    let n = 0;
    const impl = async () => {
      n += 1;
      return n === 1
        ? new Response('{}', { status: 429, headers: { 'retry-after': '0' } })
        : new Response('{"ok":true}', { status: 200 });
    };
    const res = await throttle(impl, 'https://x.test', {});
    expect(res.status).toBe(200);
    expect(n).toBe(2);
  });

  it('honours Retry-After rather than guessing', async () => {
    const throttle = makeThrottle({ gapMs: 1 });
    const sleeps = [];
    const realTimeout = globalThis.setTimeout;
    globalThis.setTimeout = (fn, ms) => { sleeps.push(ms); return realTimeout(fn, 0); };
    let n = 0;
    try {
      const impl = async () => {
        n += 1;
        return n === 1
          ? new Response('{}', { status: 429, headers: { 'retry-after': '2' } })
          : new Response('{}', { status: 200 });
      };
      await throttle(impl, 'https://x.test', {});
    } finally {
      globalThis.setTimeout = realTimeout;
    }
    expect(sleeps).toContain(2000);
  });

  it('gives up after a few tries instead of hammering forever', async () => {
    const throttle = makeThrottle({ gapMs: 1 });
    let n = 0;
    const impl = async () => {
      n += 1;
      return new Response('{}', { status: 429, headers: { 'retry-after': '0' } });
    };
    const res = await throttle(impl, 'https://x.test', {});
    expect(res.status).toBe(429);
    expect(n).toBe(5); // the first go plus four retries
  });

  it('passes a non-429 error straight through without retrying', async () => {
    const throttle = makeThrottle({ gapMs: 1 });
    let n = 0;
    const impl = async () => { n += 1; return new Response('{}', { status: 503 }); };
    const res = await throttle(impl, 'https://x.test', {});
    expect(res.status).toBe(503);
    expect(n).toBe(1);
  });
});

// The gap climbs on pushback and stays climbed.
//
// It was a flat 600ms on every call, forever — written for the nightly
// price refresh, where "being slow costs nothing" was true. A 4,000 card
// import is 54 chunked calls, and that gap alone was 32 of the 35 seconds;
// the database work was 2.9. Matt: "Just do a fucking exponential backoff
// dumbass."
describe('makeThrottle — backing off only when told to', () => {
  /** Sleeps the throttle performs, in order, without real waiting. */
  function record() {
    const slept = [];
    const realSetTimeout = globalThis.setTimeout;
    globalThis.setTimeout = (fn, ms) => { slept.push(ms); return realSetTimeout(fn, 0); };
    return { slept, done: () => { globalThis.setTimeout = realSetTimeout; } };
  }

  const ok = () => new Response('{}', { status: 200 });
  const tooMany = (headers = {}) => new Response('{}', { status: 429, headers });

  it('does not sleep before the first call', async () => {
    const r = record();
    try {
      await makeThrottle()(async () => ok(), 'u', {});
      expect(r.slept).toEqual([]);
    } finally { r.done(); }
  });

  it('waits the floor between calls while nothing pushes back', async () => {
    const r = record();
    try {
      const throttle = makeThrottle();
      for (let i = 0; i < 4; i += 1) await throttle(async () => ok(), 'u', {});
      // Three gaps for four calls, every one of them the floor. This is the
      // assertion the old flat 600 fails.
      expect(r.slept).toEqual([100, 100, 100]);
    } finally { r.done(); }
  });

  it('doubles the gap on a 429 and keeps it for later calls', async () => {
    const r = record();
    try {
      const throttle = makeThrottle();
      let n = 0;
      // First call 429s once then succeeds; the next two are fine.
      await throttle(async () => (n++ === 0 ? tooMany() : ok()), 'u', {});
      await throttle(async () => ok(), 'u', {});
      await throttle(async () => ok(), 'u', {});
      // No gap before the first call, then the retry waits the doubled 200,
      // and the two calls after it wait 200 as well rather than dropping
      // back to the floor.
      expect(r.slept).toEqual([200, 200, 200]);
    } finally { r.done(); }
  });

  it('keeps doubling while they keep pushing back', async () => {
    const r = record();
    try {
      const throttle = makeThrottle();
      await throttle(async () => tooMany(), 'u', {});
      // 100 -> 200 -> 400 -> 800 -> 1600, four retries after the first try.
      expect(r.slept).toEqual([200, 400, 800, 1600]);
    } finally { r.done(); }
  });

  it('stops doubling at the ceiling', async () => {
    const r = record();
    try {
      const throttle = makeThrottle({ gapMs: 1000, maxGapMs: 1600 });
      await throttle(async () => tooMany(), 'u', {});
      expect(r.slept).toEqual([1600, 1600, 1600, 1600]);
    } finally { r.done(); }
  });

  it('prefers their Retry-After to our own guess', async () => {
    const r = record();
    try {
      const throttle = makeThrottle();
      let n = 0;
      await throttle(async () => (n++ === 0 ? tooMany({ 'retry-after': '2' }) : ok()), 'u', {});
      expect(r.slept[0]).toBe(2000);
    } finally { r.done(); }
  });

  it('hands back the 429 rather than retrying forever', async () => {
    const r = record();
    try {
      const res = await makeThrottle()(async () => tooMany(), 'u', {});
      expect(res.status).toBe(429);
      expect(r.slept.length).toBe(4);
    } finally { r.done(); }
  });
});
