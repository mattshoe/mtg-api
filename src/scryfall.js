// Scryfall client.
//
// The old system resolved cards against a 331 MB local index of the Scryfall
// bulk dump. A Worker has no such thing, so it asks Scryfall directly — but
// via /cards/collection, which takes 75 identifiers per call. A 50-card add is
// one round trip, not 50.

const API = 'https://api.scryfall.com';
const UA = 'MattMTGCollectionAPI/1.0';
const CHUNK = 75; // Scryfall's documented cap for /cards/collection

// Scryfall asks for 50-100ms between requests and threatens a network block
// for ignoring it. Firing rulings lookups in parallel gets you rate-limited
// on the second card, so every call goes through this gate.
const MIN_GAP_MS = 110;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Injectable so tests never touch the network. */
export function makeClient(fetchImpl = fetch, { minGapMs = MIN_GAP_MS } = {}) {
  let lastCall = 0;

  async function throttle() {
    const wait = lastCall + minGapMs - Date.now();
    if (wait > 0) await sleep(wait);
    lastCall = Date.now();
  }

  async function call(path, init = {}) {
    await throttle();
    const res = await fetchImpl(API + path, {
      ...init,
      headers: {
        'User-Agent': UA,
        Accept: 'application/json',
        ...(init.body ? { 'Content-Type': 'application/json' } : {}),
        ...init.headers,
      },
    });
    if (!res.ok) {
      let detail = `${res.status} ${res.statusText}`;
      try {
        const body = await res.json();
        if (body && body.details) detail = body.details;
      } catch {
        /* a non-JSON error body tells us nothing more than the status */
      }
      const err = new Error(`scryfall: ${detail}`);
      err.status = res.status;
      throw err;
    }
    return res.json();
  }

  return {
    /**
     * Resolve many identifiers at once.
     * @param {Array<object>} identifiers Scryfall identifier objects
     * @returns {Promise<{data: object[], not_found: object[]}>}
     */
    async collection(identifiers) {
      const data = [];
      const notFound = [];
      for (let i = 0; i < identifiers.length; i += CHUNK) {
        const slice = identifiers.slice(i, i + CHUNK);
        const body = await call('/cards/collection', {
          method: 'POST',
          body: JSON.stringify({ identifiers: slice }),
        });
        data.push(...(body.data || []));
        notFound.push(...(body.not_found || []));
      }
      return { data, not_found: notFound };
    },

    /** Rulings for one oracle card. Empty array rather than a throw on 404. */
    async rulings(scryfallId) {
      try {
        const body = await call(`/cards/${scryfallId}/rulings`);
        return body.data || [];
      } catch (e) {
        if (e.status === 404) return [];
        throw e;
      }
    },
  };
}

/**
 * A parsed line -> the Scryfall identifier that best pins it down.
 *
 * Note the `//` handling: /cards/collection will not resolve a full
 * double-faced name like "Delver of Secrets // Insectile Aberration" even
 * though that is exactly how the old database stores it. The front face
 * alone resolves fine and returns the same card, so send that.
 */
export function identifierFor(item) {
  if (item.set && item.num) {
    return { set: item.set.toLowerCase(), collector_number: String(item.num) };
  }
  const name = item.name.includes('//') ? item.name.split('//')[0].trim() : item.name;
  if (item.set) return { name, set: item.set.toLowerCase() };
  return { name };
}
