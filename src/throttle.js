// Talking to Scryfall politely.
//
// Workers freeze Date.now() between I/O operations as a timing-attack
// mitigation, so "has 110ms elapsed since the last call?" is not a question
// the runtime will answer honestly — the gap computes as zero and the loop
// runs flat out. Scryfall answered that with a 429 on the 21st batch.
//
// So: sleep unconditionally between calls rather than measuring, and back
// off when they tell us to.

// Scryfall documents 10 requests a second, but a Worker egresses from
// Cloudflare's shared IPs, which they rate-limit far more tightly — 150ms
// still got a hard 429 on the 21st batch that four retries could not clear.
// The daily job runs in the background, so being slow costs nothing.
const MIN_GAP_MS = 600;
const MAX_RETRIES = 4;

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * A rate-limited caller. One per job, so successive calls space themselves
 * out without consulting the clock.
 */
export function makeThrottle({ gapMs = MIN_GAP_MS } = {}) {
  let first = true;
  return async function call(fetchImpl, url, init) {
    for (let attempt = 0; attempt <= MAX_RETRIES; attempt += 1) {
      if (!first) await sleep(gapMs);
      first = false;

      const res = await fetchImpl(url, init);
      if (res.status !== 429) return res;

      // Scryfall's own guidance is to wait and try again; their Retry-After
      // is in seconds when they send one.
      const after = Number(res.headers?.get?.('retry-after')) || 0;
      const backoff = after ? after * 1000 : gapMs * 4 * (attempt + 1);
      if (attempt === MAX_RETRIES) return res;
      await sleep(Math.min(backoff, 5000));
    }
    throw new Error('unreachable');
  };
}
