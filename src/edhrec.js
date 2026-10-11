// A commander's rank, from EDHREC.
//
// The rank stored on every card is Scryfall's `edhrec_rank`, which places
// a card among every card there is. EDHREC's commander page places a
// commander among commanders, and that is a different number: Teysa
// Karlov is 1,330th of all cards and 58th of commanders. Matt read the
// second off EDHREC and the app only had the first.
//
// EDHREC's commander page is a JSON file whose `card` says the rank and
// how many decks it leads. The Worker asks rather than the app, the same
// as the release notes, so neither shell talks to a third party itself.
// EDHREC's CDN keeps it for half an hour, and Cloudflare is told to keep
// it a day: the rank moves monthly.

const BASE = 'https://json.edhrec.com/pages/commanders/';

/**
 * EDHREC's address for a card: the front face, accents and apostrophes
 * dropped, everything else that is not a letter or digit a hyphen.
 * "Y'shtola, Night's Blessed" is `yshtola-nights-blessed`.
 */
export function edhrecSlug(name) {
  return String(name).split(' // ')[0]
    .normalize('NFKD').replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/['’]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');
}

/** `{ name, slug, rank, decks }`, rank null for no commander page, or `{ error }`. */
export async function commanderRank(name, fetchImpl) {
  const slug = edhrecSlug(name);
  let res;
  try {
    res = await fetchImpl(`${BASE}${slug}.json`, {
      headers: { accept: 'application/json', 'user-agent': 'mtg-api (mattshoe/mtg-api)' },
      cf: { cacheTtl: 86_400, cacheEverything: true },
    });
  } catch (e) {
    return { error: `EDHREC did not answer: ${e.message}` };
  }
  // No page is a 403 from its bucket, not a 404: the card is not a
  // commander anybody has built, which is an answer, not a failure.
  if (res.status === 403 || res.status === 404) return { name, slug, rank: null, decks: null };
  if (!res.ok) return { error: `EDHREC answered ${res.status}` };
  let card;
  try {
    card = (await res.json())?.card;
  } catch {
    return { error: 'EDHREC answered with something that is not JSON' };
  }
  const n = (v) => (Number.isFinite(v) ? v : null);
  return { name, slug, rank: n(card?.rank), decks: n(card?.num_decks) };
}
