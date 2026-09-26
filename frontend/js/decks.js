// Decks — the list, and one deck in full.

import * as api from './api.js';
import {
  h, $, fill, num, identity, manaCost, imageUrl, download, toast,
  loading, errorBox, empty,
} from './util.js';
import { openCard } from './card.js';

const COLOR_CSS = { W: 'var(--w)', U: 'var(--u)', B: 'var(--b)', R: 'var(--r)', G: 'var(--g)' };

function colorBar(ci) {
  const cs = (ci || '').split('').filter((c) => COLOR_CSS[c]);
  if (!cs.length) return h('div.pillbar', h('i', { style: { width: '100%', background: 'var(--c)' } }));
  return h('div.pillbar', cs.map((c) => h('i', { style: { width: `${100 / cs.length}%`, background: COLOR_CSS[c] } })));
}

function deckTile(d) {
  const complete = d.card_count ? Math.round((d.owned_count / d.card_count) * 100) : 0;
  return h('div.deck-card', { onclick: () => { location.hash = `#/decks/${encodeURIComponent(d.slug)}`; } },
    colorBar(d.colors),
    h('div',
      h('strong', d.name),
      h('div.cmdr', d.commander || '—')),
    h('div.flex-wrap',
      h('span.tag', d.owner),
      d.is_proxy ? h('span.tag.warn', 'proxy') : null,
      d.bracket ? h('span.tag.info', `bracket ${d.bracket}`) : null,
      (d.status || '').startsWith('PROPOSED') ? h('span.tag.warn', 'proposed') : null),
    h('div.flex.small.muted',
      h('span', `${num(d.card_count)} cards`),
      h('span.spacer'),
      h('span', { class: complete === 100 ? '' : 'tag warn' }, `${complete}% owned`)));
}

async function listView() {
  const root = $('#view');
  fill(root, h('div.wrap', loading('Loading decks')));
  try {
    const decks = await api.rows(`
      SELECT slug, name, owner, commander, colors, bracket, theme, status,
             is_proxy, card_count, owned_count
        FROM decks ORDER BY owner, name`);

    const byOwner = {};
    for (const d of decks) (byOwner[d.owner] ||= []).push(d);

    fill(root, h('div.wrap',
      h('div.page-head',
        h('h1', 'Decks'),
        h('span.sub', `${decks.length} total`),
        h('span.spacer'),
        h('a.btn.sm', { href: '#/decks/_gaps' }, 'Gaps & conflicts')),
      Object.entries(byOwner).map(([owner, list]) => h('div', { style: { marginBottom: '26px' } },
        h('h2', { style: { marginBottom: '10px', textTransform: 'capitalize' } }, owner, h('span.muted.small', ` · ${list.length}`)),
        h('div.deck-grid', list.map(deckTile))))));
  } catch (e) {
    fill(root, h('div.wrap', errorBox(e)));
  }
}

async function gapsView() {
  const root = $('#view');
  fill(root, h('div.wrap', loading('Checking decks')));
  try {
    const [gaps, conflicts, notBuilt] = await Promise.all([
      api.rows('SELECT slug, deck, owner, name, qty, role FROM deck_gaps ORDER BY owner, deck, name'),
      api.rows('SELECT owner, name, owned, in_decks, deck_count, decks FROM deck_conflicts ORDER BY in_decks - owned DESC'),
      api.rows('SELECT slug, name, owner, is_proxy, status, card_count, owned_count FROM decks_not_built ORDER BY owner, name'),
    ]);

    const table = (rows, cols, render) => (rows.length
      ? h('div.table-wrap', h('table',
        h('thead', h('tr', cols.map((c) => h('th', c)))),
        h('tbody', rows.map(render))))
      : h('div.panel-body', h('div.muted', 'Nothing here — good.')));

    fill(root, h('div.wrap',
      h('div.page-head', h('h1', 'Gaps & conflicts'), h('span.spacer'), h('a.btn.sm', { href: '#/decks' }, '← All decks')),
      h('div.stack',
        h('div.panel',
          h('div.panel-head', h('h2', 'Conflicts'), h('span.tag.bad', `${conflicts.length}`),
            h('span.muted.small', 'slotted into more decks than there are copies')),
          table(conflicts, ['Card', 'Owner', 'Owned', 'In decks', 'Decks'], (c) => h('tr',
            h('td.t-name', c.name), h('td', c.owner),
            h('td.num', c.owned), h('td.num', h('span.tag.bad', c.in_decks)),
            h('td.small.muted', c.decks || '')))),

        h('div.panel',
          h('div.panel-head', h('h2', 'Gaps'), h('span.tag.warn', `${gaps.length}`),
            h('span.muted.small', 'deck slots not backed by a card in the collection')),
          table(gaps, ['Deck', 'Owner', 'Card', 'Qty', 'Role'], (g) => h('tr.clickable',
            { onclick: () => { location.hash = `#/decks/${encodeURIComponent(g.slug)}`; } },
            h('td', g.deck), h('td', g.owner), h('td.t-name', g.name),
            h('td.num', g.qty), h('td.small', g.role || '')))),

        h('div.panel',
          h('div.panel-head', h('h2', 'Not built'), h('span.tag', `${notBuilt.length}`),
            h('span.muted.small', 'proxy or proposed')),
          table(notBuilt, ['Deck', 'Owner', 'Why', 'Cards', 'Owned'], (d) => h('tr.clickable',
            { onclick: () => { location.hash = `#/decks/${encodeURIComponent(d.slug)}`; } },
            h('td', d.name), h('td', d.owner),
            h('td', d.is_proxy ? h('span.tag.warn', 'proxy') : h('span.tag', 'proposed')),
            h('td.num', d.card_count), h('td.num', d.owned_count)))))));
  } catch (e) {
    fill(root, h('div.wrap', errorBox(e)));
  }
}

const ROLE_ORDER = ['commander', 'spell', 'land'];

async function detailView(slug) {
  const root = $('#view');
  fill(root, h('div.wrap', loading('Loading deck')));
  try {
    const deck = await api.one('SELECT * FROM decks WHERE slug = ?', [slug]);
    if (!deck) { fill(root, h('div.wrap', empty('No such deck', slug))); return; }

    const [cards, notes] = await Promise.all([
      // Joins rather than a correlated subquery per column: the old shape
      // rescanned `cards` five times for every row in the deck.
      // MIN(id) in a grouped select makes SQLite take the other bare columns
      // from that same row, which is the representative printing we want.
      api.rows(`
        SELECT dc.qty, dc.name, dc.name_norm, dc.raw_name, dc.role, dc.section, dc.in_collection,
               mine.id                                        AS card_id,
               COALESCE(mine.scryfall_id, alt.scryfall_id)    AS scryfall_id,
               COALESCE(mine.mana_cost,   alt.mana_cost)      AS mana_cost,
               COALESCE(mine.cmc,         alt.cmc)            AS cmc,
               COALESCE(mine.type_line,   alt.type_line)      AS type_line
          FROM deck_cards dc
          LEFT JOIN (SELECT owner, name_norm, MIN(id) id, scryfall_id, mana_cost, cmc, type_line
                       FROM cards GROUP BY owner, name_norm) mine
            ON mine.name_norm = dc.name_norm AND mine.owner = ?
          LEFT JOIN (SELECT name_norm, MIN(id) id, scryfall_id, mana_cost, cmc, type_line
                       FROM cards GROUP BY name_norm) alt
            ON alt.name_norm = dc.name_norm
         WHERE dc.deck_id = ?
         ORDER BY dc.role, dc.name`, [deck.owner, deck.id]),
      api.rows('SELECT section, body FROM deck_notes WHERE deck_id = ? ORDER BY id', [deck.id]),
    ]);

    const byRole = {};
    for (const c of cards) (byRole[c.role || 'spell'] ||= []).push(c);
    const roles = [...new Set([...ROLE_ORDER, ...Object.keys(byRole)])].filter((r) => byRole[r]);

    const missing = cards.filter((c) => !c.in_collection);
    const curve = {};
    for (const c of cards) {
      if ((c.type_line || '').includes('Land')) continue;
      const k = Math.min(7, Math.floor(c.cmc ?? 0));
      curve[k] = (curve[k] || 0) + c.qty;
    }
    const curveMax = Math.max(1, ...Object.values(curve));

    const cardRow = (c) => h('tr.clickable', {
      onclick: () => (c.card_id ? openCard(c.card_id) : toast('Not in the collection', 'bad')),
      style: c.in_collection ? null : { opacity: '.62' },
    },
    h('td.num', { style: { width: '40px' } }, c.qty),
    h('td.t-name', c.name, c.in_collection ? null : h('span.tag.warn', { style: { marginLeft: '6px' } }, 'not owned')),
    h('td', { style: { width: '90px' } }, manaCost(c.mana_cost)),
    h('td.small.muted', c.type_line || ''));

    fill(root, h('div.wrap',
      h('div.page-head',
        h('a.btn.sm', { href: '#/decks' }, '←'),
        h('h1', deck.name),
        h('span.sub', deck.commander || ''),
        h('span.spacer'),
        h('button.btn.sm', { onclick: () => exportDeck(deck, cards) }, 'Export list'),
        h('button.btn.sm', {
          onclick: () => { location.hash = `#/search?q=&owner=${deck.owner}`; },
        }, 'Search collection')),

      h('div.split',
        h('div.sticky-side.stack',
          h('div.panel',
            h('div.panel-head', h('h2', 'Deck')),
            h('div.panel-body',
              colorBar(deck.colors),
              h('dl.kv', { style: { marginTop: '12px' } },
                h('dt', 'Owner'), h('dd', deck.owner),
                h('dt', 'Identity'), h('dd', identity(deck.colors)),
                h('dt', 'Cards'), h('dd', `${num(deck.card_count)} (${num(deck.owned_count)} owned)`),
                deck.bracket ? [h('dt', 'Bracket'), h('dd', deck.bracket)] : null,
                deck.theme ? [h('dt', 'Theme'), h('dd', deck.theme)] : null,
                deck.recorded_date ? [h('dt', 'Recorded'), h('dd', deck.recorded_date)] : null,
                h('dt', 'Status'), h('dd.small', deck.status || '—')),
              missing.length
                ? h('div', { style: { marginTop: '12px' } }, h('span.tag.warn', `${missing.length} card${missing.length === 1 ? '' : 's'} not owned`))
                : h('div', { style: { marginTop: '12px' } }, h('span.tag.ok', 'fully owned')))),

          h('div.panel',
            h('div.panel-head', h('h2', 'Mana curve')),
            h('div.panel-body', Object.keys(curve).length
              ? Object.entries(curve).sort((a, b) => a[0] - b[0]).map(([k, v]) => h('div.bar-row',
                h('div.lbl', k === '7' ? '7+' : k),
                h('div.bar-track', h('div.bar-fill', { style: { width: `${(v / curveMax) * 100}%` } })),
                h('div.val', v)))
              : h('div.muted', 'No nonland cards.')))),

        h('div.stack',
          roles.map((role) => h('div.panel',
            h('div.panel-head',
              h('h2', role),
              h('span.tag', `${byRole[role].reduce((a, c) => a + c.qty, 0)}`)),
            h('div.table-wrap', h('table', h('tbody', byRole[role].map(cardRow)))))),

          notes.map((n) => h('div.panel',
            h('div.panel-head', h('h2', n.section || 'Notes')),
            h('div.panel-body', h('div.oracle', n.body)))),

          deck.source_md ? h('details.panel', { style: { padding: '0' } },
            h('summary', { style: { padding: '11px 14px', cursor: 'pointer', color: 'var(--text-2)' } }, 'Original markdown'),
            h('div.panel-body', h('pre.out', deck.source_md))) : null))));
  } catch (e) {
    fill(root, h('div.wrap', errorBox(e)));
  }
}

function exportDeck(deck, cards) {
  const lines = [`// ${deck.name}`, deck.commander ? `// Commander: ${deck.commander}` : '', ''];
  for (const role of ROLE_ORDER) {
    const list = cards.filter((c) => (c.role || 'spell') === role);
    if (!list.length) continue;
    lines.push(`// ${role}`);
    for (const c of list) lines.push(`${c.qty} ${c.name}`);
    lines.push('');
  }
  download(`${deck.slug}.txt`, lines.join('\n'));
  toast('Decklist downloaded', 'ok');
}

export function show(slug) {
  if (!slug) return listView();
  if (slug === '_gaps') return gapsView();
  return detailView(decodeURIComponent(slug));
}
