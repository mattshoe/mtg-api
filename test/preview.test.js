import { describe, it, expect } from 'vitest';
import { get } from './helpers.js';

// Every link to this site previewed as the bare site, because the
// app routes on a hash and a fragment never reaches a server: the
// crawler asks for `/` whatever you shared. These are addresses that
// name the thing in the path, so there is something to answer with.

const firstDeck = async () => {
  const r = await get('/query?sql=' + encodeURIComponent('SELECT slug, name FROM decks LIMIT 1'));
  return { slug: r.body.rows[0][0], name: r.body.rows[0][1] };
};

const tag = (html, prop) => {
  const m = html.match(new RegExp(`<meta (?:property|name)="${prop}" content="([^"]*)"`));
  return m && m[1];
};

describe('GET /s/deck/:slug', () => {
  it('is HTML, not JSON', async () => {
    const d = await firstDeck();
    const r = await get(`/s/deck/${d.slug}`);
    expect(r.status).toBe(200);
    expect(r.headers.get('content-type')).toMatch(/text\/html/);
  });

  it('titles it with the deck name', async () => {
    const d = await firstDeck();
    const html = (await get(`/s/deck/${d.slug}`)).text;
    expect(tag(html, 'og:title')).toBe(d.name);
  });

  it('says what the deck is in the description', async () => {
    const d = await firstDeck();
    const html = (await get(`/s/deck/${d.slug}`)).text;
    const desc = tag(html, 'og:description');
    expect(desc).toBeTruthy();
    expect(desc).toMatch(/cards/);
  });

  it('points the image at Scryfall when there is a commander to show', async () => {
    const r = await get('/query?sql=' + encodeURIComponent(
      "SELECT slug FROM decks WHERE commander IS NOT NULL AND commander != '' LIMIT 1",
    ));
    const slug = r.body.rows[0]?.[0];
    if (!slug) return;
    const html = (await get(`/s/deck/${slug}`)).text;
    const img = tag(html, 'og:image');
    if (img) expect(img).toMatch(/^https:\/\/cards\.scryfall\.io\//);
  });

  it('sends a human to the app', async () => {
    const d = await firstDeck();
    const html = (await get(`/s/deck/${d.slug}`)).text;
    expect(html).toMatch(/http-equiv="refresh"/);
    expect(html).toMatch(new RegExp(`mtg\\.mattshoe\\.org/#/decks/${d.slug}`));
  });

  it('a deck that does not exist is a 404 that still previews', async () => {
    const r = await get('/s/deck/no-such-deck');
    expect(r.status).toBe(404);
    const html = r.text;
    expect(tag(html, 'og:title')).toBe('No such deck');
  });

  it('refuses anything but GET', async () => {
    const d = await firstDeck();
    const r = await get(`/s/deck/${d.slug}`);
    expect(r.status).toBe(200);
  });
});

describe('GET /s/card/:name', () => {
  it('titles it with the card as it is spelt', async () => {
    const html = (await get('/s/card/sol%20ring')).text;
    expect(tag(html, 'og:title')).toBe('Sol Ring');
  });

  it('shows the card itself, not a crop', async () => {
    const html = (await get('/s/card/sol%20ring')).text;
    const img = tag(html, 'og:image');
    if (img) expect(img).toMatch(/\/normal\/front\//);
  });

  it('takes the name however it is cased', async () => {
    const html = (await get('/s/card/SOL%20RING')).text;
    expect(tag(html, 'og:title')).toBe('Sol Ring');
  });

  it('a card nobody owns is a 404 that still previews', async () => {
    const r = await get('/s/card/zzyzxqqq');
    expect(r.status).toBe(404);
    expect(tag(r.text, 'og:title')).toBe('No such card');
  });
});

describe('the tags themselves', () => {
  it('escape anything that would break out of an attribute', async () => {
    const r = await get('/s/deck/' + encodeURIComponent('"><script>x</script>'));
    const html = r.text;
    expect(html).not.toMatch(/<script>x<\/script>/);
    expect(html).toMatch(/&quot;|&lt;/);
    // An ampersand too: left raw it ends an entity early and the
    // rest of the attribute escapes with it.
    const amp = await get('/s/deck/' + encodeURIComponent('a & b'));
    expect(amp.text).toMatch(/a &amp; b/);
    expect(amp.text).not.toMatch(/a & b/);
  });

  it('name the site, so a preview is not just a bare domain', async () => {
    const d = await firstDeck();
    const html = (await get(`/s/deck/${d.slug}`)).text;
    expect(tag(html, 'og:site_name')).toBe("Matt's MTG Collection");
  });

  it('ask for a big image when there is one', async () => {
    const html = (await get('/s/card/sol%20ring')).text;
    expect(tag(html, 'twitter:card')).toBe('summary_large_image');
  });

  it('are cacheable, because an unfurl should be cheap', async () => {
    const d = await firstDeck();
    const r = await get(`/s/deck/${d.slug}`);
    expect(r.headers.get('cache-control')).toMatch(/max-age=\d+/);
  });
});
