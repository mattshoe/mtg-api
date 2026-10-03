// Link previews, for Discord and Slack and anything else that unfurls.
//
// The site is a single page with hash routing, so every deck lives at
// `mtg.mattshoe.org/#/decks/<slug>`. A fragment is never sent to a
// server — the crawler asks for `/` and gets the same index page
// whatever you shared, which is why every link has previewed as the
// bare site. Nothing about the page could fix that; the URL has to
// name the deck somewhere a server can read it.
//
// So these are shareable addresses the Worker answers: real HTML with
// Open Graph tags filled in from the database, and a redirect to the
// app for anybody who actually clicks. Crawlers do not follow a meta
// refresh, so they keep the tags; people never see this page.

/** Scryfall lays its images out by the first two characters of the id. */
const art = (id, size = 'art_crop') => {
  if (!id || id.length < 2) return null;
  return `https://cards.scryfall.io/${size}/front/${id[0]}/${id[1]}/${id}.jpg`;
};

/** Everything that goes into an attribute or a tag has to be escaped. */
const esc = (s) => String(s ?? '')
  .replaceAll('&', '&amp;')
  .replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;')
  .replaceAll("'", '&#39;');

/** The commander as people say it, without the set annotation. */
const plainCommander = (c) => (c || '').split(' (')[0].trim();

const SITE = 'https://mtg.mattshoe.org';

/**
 * One page of tags, and a door out of it.
 *
 * `og:` for most of them and `twitter:` for the ones that only read
 * those, because a card with no image is a grey box with a URL in it.
 */
function page({ title, description, image, url }) {
  const big = image ? 'summary_large_image' : 'summary';
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>${esc(title)}</title>
<meta name="description" content="${esc(description)}">
<meta property="og:site_name" content="Matt's MTG Collection">
<meta property="og:type" content="website">
<meta property="og:title" content="${esc(title)}">
<meta property="og:description" content="${esc(description)}">
<meta property="og:url" content="${esc(url)}">
${image ? `<meta property="og:image" content="${esc(image)}">` : ''}
<meta name="twitter:card" content="${big}">
<meta name="twitter:title" content="${esc(title)}">
<meta name="twitter:description" content="${esc(description)}">
${image ? `<meta name="twitter:image" content="${esc(image)}">` : ''}
<meta name="theme-color" content="#0e1116">
<link rel="canonical" href="${esc(url)}">
<meta http-equiv="refresh" content="0; url=${esc(url)}">
</head>
<body style="background:#0e1116;color:#e6e9ef;font:15px system-ui;padding:40px">
<p><a href="${esc(url)}" style="color:#d6a84f">${esc(title)}</a></p>
<script>location.replace(${JSON.stringify(url)})</script>
</body>
</html>`;
}

const html = (body, status = 200) => new Response(body, {
  status,
  headers: {
    'content-type': 'text/html; charset=utf-8',
    'access-control-allow-origin': '*',
    // Long enough that an unfurl is cheap, short enough that a rename
    // shows up the same day.
    'cache-control': 'public, max-age=600',
  },
});

/** A deck, as a link somebody pasted into a chat. */
export async function deckPreview(db, slug) {
  const row = await db.prepare(
    `SELECT d.name, d.owner, d.commander, d.format,
            (SELECT COALESCE(SUM(dc.qty), 0) FROM deck_cards dc WHERE dc.deck_id = d.id) AS cards,
            c.scryfall_id AS art_id
       FROM decks d
       LEFT JOIN (SELECT name_norm, MIN(id) AS id, scryfall_id
                    FROM cards GROUP BY name_norm) c
         ON c.name_norm = lower(trim(CASE
              WHEN instr(d.commander, ' (') > 0
              THEN substr(d.commander, 1, instr(d.commander, ' (') - 1)
              ELSE d.commander END))
      WHERE d.slug = ?`,
  ).bind(slug).first();

  const url = `${SITE}/#/decks/${encodeURIComponent(slug)}`;
  if (!row) {
    return html(page({
      title: 'No such deck',
      description: `Nothing here is called ${slug}.`,
      image: null,
      url: `${SITE}/#/decks`,
    }), 404);
  }

  const commander = plainCommander(row.commander);
  const bits = [];
  if (commander) bits.push(commander);
  if (row.format) bits.push(row.format);
  if (row.cards) bits.push(`${row.cards} cards`);
  if (row.owner) bits.push(`${row.owner}'s deck`);

  return html(page({
    title: row.name || slug,
    description: bits.join(' · '),
    image: art(row.art_id),
    url,
  }));
}

/** One card, by its normalised name. */
export async function cardPreview(db, nameNorm) {
  const row = await db.prepare(
    `SELECT MIN(c.name) AS name,
            SUM(c.qty) AS owned,
            MIN(c.scryfall_id) AS id,
            MIN(c.type_line) AS type_line
       FROM cards c
      WHERE c.name_norm = ?`,
  ).bind(nameNorm).first();

  const url = `${SITE}/#/card/${encodeURIComponent(nameNorm)}`;
  if (!row || !row.name) {
    return html(page({
      title: 'No such card',
      description: `Nothing in the collection is called ${nameNorm}.`,
      image: null,
      url: `${SITE}/#/search`,
    }), 404);
  }

  const bits = [];
  if (row.type_line) bits.push(row.type_line);
  bits.push(`${row.owned} in the collection`);

  return html(page({
    title: row.name,
    description: bits.join(' · '),
    // The whole card, not the art crop: a card is worth reading.
    image: art(row.id, 'normal'),
    url,
  }));
}

export const PREVIEW_HELPERS = { art, esc, plainCommander };
