// What a shared link looks like when somebody pastes it.
//
// Discord, Slack and the rest fetch a pasted URL and read its Open Graph
// tags. The site routes on a hash, and a fragment never reaches a server,
// so for every address the app ever handed out the crawler asked GitHub
// Pages for `/` and showed the bare site. No tag on index.html could fix
// that and Pages cannot render a page per deck.
//
// So a shared link is `/s/<route>` on this Worker: the same route the
// hash carries, moved into the path where a server can read it. The
// answer is a small page with the tags for that deck, card or collection,
// and a refresh that sends a person on to the hash address. Crawlers do
// not follow a meta refresh, so they keep the tags; people never see it.

export const SITE = 'https://mtg.mattshoe.org/';

const SITE_TITLE = 'MTG Collection';
const SITE_DESCRIPTION = "Matt's Magic: The Gathering collection";

/** Text into an HTML attribute or element, inert. */
export function esc(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

/** `FilterUrl.decode`'s reading: `+` is a space, then percent escapes. */
function decode(s) {
  try {
    return decodeURIComponent(String(s).replace(/\+/g, ' '));
  } catch {
    return String(s);
  }
}

/** Scryfall's image CDN, addressed straight off a printing's id. */
function scryfallImage(id, version) {
  return `https://cards.scryfall.io/${version}/front/${id[0]}/${id[1]}/${id}.jpg`;
}

/**
 * `Route.parse`, as much of it as a preview needs: whose collection, which
 * view, and what after it.
 */
function parseRoute(path) {
  const all = path.split('/').filter(Boolean);
  const collection = all[0] === 'c' ? all[1] || '' : '';
  const parts = collection ? all.slice(2) : all;
  return { collection, view: parts[0] || '', rest: parts.slice(1).join('/') };
}

/** The deck's commander, without the note a deck file puts after it. */
function commanderName(commander) {
  const c = String(commander || '');
  const cut = c.indexOf(' (');
  return (cut > 0 ? c.slice(0, cut) : c).trim();
}

async function deckPreview(db, key) {
  const d = await db.prepare(
    `SELECT d.name, d.commander, d.format, d.card_count, u.display_name
       FROM decks d LEFT JOIN users u ON u.id = d.owner_id
      WHERE d.key = ?1`,
  ).bind(key).first();
  if (!d) return null;

  const commander = commanderName(d.commander);
  const art = commander
    ? await db.prepare('SELECT scryfall_id FROM cards WHERE name_norm = ?1 AND scryfall_id IS NOT NULL ORDER BY id LIMIT 1')
      .bind(commander.toLowerCase()).first()
    : null;

  const said = [
    commander && `Commander: ${commander}`,
    d.format,
    d.card_count != null && `${d.card_count} cards`,
    d.display_name && `${d.display_name}'s deck`,
  ].filter(Boolean);

  let image = null;
  if (art?.scryfall_id) image = scryfallImage(art.scryfall_id, 'art_crop');
  else if (commander) {
    image = `https://api.scryfall.com/cards/named?exact=${encodeURIComponent(commander)}&format=image&version=art_crop`;
  }
  return { title: d.name || SITE_TITLE, description: said.join(' · '), image };
}

async function cardPreview(db, rest) {
  const norm = decode(rest).trim().toLowerCase();
  if (!norm) return null;
  const c = await db.prepare(
    `SELECT name, type_line, scryfall_id,
            (SELECT SUM(qty) FROM cards WHERE name_norm = ?1) AS n
       FROM cards WHERE name_norm = ?1 ORDER BY id LIMIT 1`,
  ).bind(norm).first();
  if (!c) return null;
  const said = [c.type_line, `${c.n ?? 0} owned`].filter(Boolean);
  return {
    title: c.name,
    description: said.join(' · '),
    image: c.scryfall_id ? scryfallImage(c.scryfall_id, 'normal') : null,
  };
}

async function collectionPreview(db, key) {
  const u = await db.prepare('SELECT display_name FROM users WHERE key = ?1').bind(key).first();
  if (!u?.display_name) return null;
  return { title: `${u.display_name}'s collection`, description: SITE_DESCRIPTION, image: null };
}

/** What the route names, or null for anything that is just the site. */
async function describe(db, route) {
  if (route.view === 'decks' && route.rest) {
    const deck = await deckPreview(db, route.rest.split('/')[0]);
    if (deck) return deck;
  }
  if (route.view === 'card' && route.rest) {
    const card = await cardPreview(db, route.rest);
    if (card) return card;
  }
  if (route.collection) return collectionPreview(db, route.collection);
  return null;
}

/**
 * `GET /s/<route>`. `path` is everything after `/s/`, raw, and `search`
 * the query string with its `?`. Always a page: a stale or mistyped link
 * still previews as the site and still takes its reader somewhere.
 */
export async function preview(db, path, search) {
  const route = parseRoute(path);
  const what = await describe(db, route) ?? { title: SITE_TITLE, description: SITE_DESCRIPTION, image: null };
  // The reader goes to the site and only the site: whatever the path
  // says lands after the `#`, where it is a route and nothing else.
  const target = `${SITE}#/${path.replace(/^\/+/, '')}${search || ''}`;

  const tags = [
    ['og:site_name', SITE_TITLE],
    ['og:type', 'website'],
    ['og:title', what.title],
    ['og:description', what.description],
    ['og:url', target],
    what.image && ['og:image', what.image],
  ].filter(Boolean);

  const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>${esc(what.title)}</title>
<meta name="description" content="${esc(what.description)}">
${tags.map(([p, v]) => `<meta property="${p}" content="${esc(v)}">`).join('\n')}
<meta name="twitter:card" content="${what.image ? 'summary_large_image' : 'summary'}">
<meta name="theme-color" content="#0e1116">
<meta http-equiv="refresh" content="0; url=${esc(target)}">
</head>
<body>
<p><a href="${esc(target)}">${esc(what.title)}</a></p>
</body>
</html>
`;
  return new Response(html, {
    status: 200,
    headers: {
      'content-type': 'text/html; charset=utf-8',
      // A deck renamed should not preview under its old name all day.
      'cache-control': 'public, max-age=300',
    },
  });
}
