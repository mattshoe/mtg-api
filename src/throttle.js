// Talking to Scryfall politely.
//
// Workers freeze Date.now() between I/O operations as a timing-attack
// mitigation, so "has 110ms elapsed since the last call?" is not a question
// the runtime will answer honestly — the gap computes as zero and the loop
// runs flat out. Scryfall answered that with a 429 on the 21st batch.
//
// So: sleep unconditionally between calls rather than measuring, and back
// off when they tell us to.

// Start at Scryfall's documented floor and only pay more when they
// actually push back.
//
// This was a flat 600ms before, on every call, forever. The reasoning
// written here was "the daily job runs in the background, so being slow
// costs nothing" — true of the nightly price refresh it was written for,
// and wrong the moment a person waited on the same gate. Importing 4,000
// cards is 54 chunked calls, and the gap alone was 32 of the 35 seconds it
// took; the database work was 2.9.
//
// Matt: "Just do a fucking exponential backoff dumbass."
//
// So the gap starts at 100ms and DOUBLES on every 429, and the widened gap
// is kept for the rest of the job rather than only for the retry — one
// pushback means the next call waits longer too, which is what stops a
// hundred more earning a hundred more 429s. 150ms once got a hard 429 on
// batch 21 that four retries could not clear, which is a reason to climb
// quickly, not a reason to start high.
const MIN_GAP_MS = 100;
// Past this, waiting longer is not politeness, it is a stall. Four doublings
// from 100ms.
const MAX_GAP_MS = 1600;
const MAX_RETRIES = 4;

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * A rate-limited caller. One per job, so successive calls space themselves
 * out without consulting the clock.
 */
export function makeThrottle({ gapMs = MIN_GAP_MS, maxGapMs = MAX_GAP_MS } = {}) {
  // Carried across calls: the whole point is that a 429 on one call slows
  // the ones after it, not just its own retry.
  let gap = gapMs;
  let first = true;
  return async function call(fetchImpl, url, init) {
    // The politeness gap belongs to the CALL, once. Putting it inside the
    // retry loop made every retry wait twice — the backoff and then the gap
    // again on the next turn of the loop — which is how a single 429 turned
    // into 200, 200, 400, 400, 800, 800. The first version of this change
    // had that bug and so did the fixed-gap code it replaced.
    if (!first) await sleep(gap);
    first = false;

    for (let attempt = 0; ; attempt += 1) {
      const res = await fetchImpl(url, init);
      if (res.status !== 429) return res;

      // They pushed back, so everything after this waits longer too — not
      // just this retry. One pushback slowing only its own retry is how a
      // hundred more calls earn a hundred more 429s.
      gap = Math.min(gap * 2, maxGapMs);
      if (attempt === MAX_RETRIES) return res;

      // Scryfall's own guidance is to wait and try again; their Retry-After
      // is in seconds when they send one, and it beats our guess.
      const after = Number(res.headers?.get?.('retry-after')) || 0;
      await sleep(Math.min(after ? after * 1000 : gap, 5000));
    }
  };
}
