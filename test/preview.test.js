import { describe, it, expect } from 'vitest';
import { env, createExecutionContext, waitOnExecutionContext } from 'cloudflare:test';
import worker from '../src/index.js';
import { sql } from './helpers.js';
import siteAssetLinks from '../frontend/.well-known/assetlinks.json';

// A link pasted into Discord or Slack is fetched by a crawler that reads
// the page's Open Graph tags and nothing else. The site routes on a hash,
// and a fragment never reaches a server, so the crawler asked for `/` and
// got the bare site whatever was shared. `/s/<route>` puts the route in
// the path, where a server can see it: these are the pages it answers
// with, read the way a crawler reads them.

async function get(path) {
  const ctx = createExecutionContext();
  const res = await worker.fetch(new Request(`https://mtg-api.test${path}`), env, ctx);
  await waitOnExecutionContext(ctx);
  return { status: res.status, type: res.headers.get('content-type') || '', html: await res.text() };
}

/** What a crawler reads off `<meta property="og:…">`, entities and all. */
function og(html, prop) {
  const m = html.match(new RegExp(`<meta property="og:${prop}" content="([^"]*)"`));
  return m ? m[1] : null;
}

/** Where the page sends a person, read off the refresh tag. */
function goesTo(html) {
  const m = html.match(/<meta http-equiv="refresh" content="0; url=([^"]*)"/);
  return m ? m[1].replace(/&amp;/g, '&') : null;
}

describe('GET /s/… — a shared link previews as the thing it points at', () => {
  it('a deck previews as the deck: its name, its commander and whose it is', async () => {
    const r = await get('/s/decks/d0000001');
    expect(r.status).toBe(200);
    expect(r.type).toMatch(/text\/html/);
    expect(og(r.html, 'title')).toBe('Chaos Incarnate — Starter Commander Deck Precon');
    const said = og(r.html, 'description');
    expect(said, 'the description does not name the commander').toContain('Kardur, Doomscourge');
    expect(said, 'the description does not say how many cards').toContain('100 cards');
    expect(said, 'the description does not say whose deck it is').toContain('Matt Shoemaker');
    expect(og(r.html, 'image'), 'a deck with a commander has no picture').toMatch(/^https:\/\/.*scryfall/);
  });

  it('and whoever clicks it lands on that deck in the app', async () => {
    const r = await get('/s/decks/d0000001');
    expect(goesTo(r.html)).toBe('https://mtg.mattshoe.org/#/decks/d0000001');
  });

  it('a deck addressed inside a collection is the same deck, and keeps the collection', async () => {
    const r = await get('/s/c/k4yy0003/decks/d0000020');
    expect(og(r.html, 'title')).toMatch(/^League of Legends Proxy Deck/);
    expect(og(r.html, 'description')).toContain('Kayla M');
    expect(goesTo(r.html)).toBe('https://mtg.mattshoe.org/#/c/k4yy0003/decks/d0000020');
  });

  it("the commander's own art when the collection holds a copy of it", async () => {
    await sql(`UPDATE decks SET commander = 'Sol Ring' WHERE id = 1`);
    const r = await get('/s/decks/d0000001');
    expect(og(r.html, 'image')).toBe(
      'https://cards.scryfall.io/art_crop/front/2/d/2d47121d-8b90-4d28-9ffa-0a640b9dd611.jpg',
    );
  });

  it('a card previews as the card: its name, its type line and how many there are', async () => {
    const r = await get('/s/card/sol+ring');
    expect(og(r.html, 'title')).toBe('Sol Ring');
    expect(og(r.html, 'description')).toContain('Artifact');
    expect(og(r.html, 'description')).toMatch(/\d+ owned/);
    expect(og(r.html, 'image')).toBe(
      'https://cards.scryfall.io/normal/front/2/d/2d47121d-8b90-4d28-9ffa-0a640b9dd611.jpg',
    );
    expect(goesTo(r.html)).toBe('https://mtg.mattshoe.org/#/card/sol+ring');
  });

  it("a collection previews as whose it is", async () => {
    const r = await get('/s/c/k4yy0003/search');
    expect(og(r.html, 'title')).toBe("Kayla M's collection");
    expect(goesTo(r.html)).toBe('https://mtg.mattshoe.org/#/c/k4yy0003/search');
  });

  it('a search keeps its filters on the way through', async () => {
    const r = await get('/s/search?q=bolt&color=R');
    expect(goesTo(r.html)).toBe('https://mtg.mattshoe.org/#/search?q=bolt&color=R');
  });

  it('a deck nobody has still previews as the site, and still lands somewhere', async () => {
    const r = await get('/s/decks/nosuchdk');
    expect(r.status).toBe(200);
    expect(og(r.html, 'title')).toBe('MTG Collection');
    expect(goesTo(r.html)).toBe('https://mtg.mattshoe.org/#/decks/nosuchdk');
  });

  it("a deck's name is text, never markup", async () => {
    await sql(`UPDATE decks SET name = 'Fish & "Chips" <script>alert(1)</script>' WHERE id = 1`);
    const r = await get('/s/decks/d0000001');
    expect(r.html).not.toContain('<script>alert');
    expect(og(r.html, 'title')).toBe('Fish &amp; &quot;Chips&quot; &lt;script&gt;alert(1)&lt;/script&gt;');
  });

  it('an address cannot send anybody off the site', async () => {
    const r = await get('/s/"><script>x</script>');
    expect(r.html).not.toContain('<script>x');
    expect(goesTo(r.html)).toMatch(/^https:\/\/mtg\.mattshoe\.org\/#\//);
  });
});

describe('GET /.well-known/assetlinks.json — the phone may open a shared link', () => {
  it('names the same app and signing key the site does, so Android opens the app rather than a browser', async () => {
    const ctx = createExecutionContext();
    const res = await worker.fetch(new Request('https://mtg-api.test/.well-known/assetlinks.json'), env, ctx);
    await waitOnExecutionContext(ctx);
    expect(res.status, 'Android cannot verify the share host without this file').toBe(200);
    expect(res.headers.get('content-type') || '').toMatch(/application\/json/);
    expect(await res.json()).toEqual(siteAssetLinks);
  });
});
