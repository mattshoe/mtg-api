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
