// Decks — the list, and one deck in full.

import * as api from './api.js';
import {
  h, $, fill, num, identity, manaCost, imageUrl, download, toast,
  loading, errorBox, empty,
} from './util.js';
import { openCard } from './card.js';
import { isAdmin } from './admin.js';

const COLOR_CSS = { W: 'var(--w)', U: 'var(--u)', B: 'var(--b)', R: 'var(--r)', G: 'var(--g)' };

function colorBar(ci) {
  const cs = (ci || '').split('').filter((c) => COLOR_CSS[c]);
  if (!cs.length) return h('div.pillbar', h('i', { style: { width: '100%', background: 'var(--c)' } }));
  return h('div.pillbar', cs.map((c) => h('i', { style: { width: `${100 / cs.length}%`, background: COLOR_CSS[c] } })));
}

// The deck rows were written by hand, so every field carries prose the tile
// has no room for. These pull the one fact out of each.

const WORD = { white: 'W', blue: 'U', black: 'B', red: 'R', green: 'G' };

// Alphabetical, the order the database stores identity in.
const dedupe = (cs) => [...new Set(cs)].sort().join('');

/**
 * A deck's colour identity as WUBRG letters. The commander's own
 * `color_identity` is the truth when we have the card; the `colors` column
 * is free text ('Simic (Green/Blue)', '{W}{U}{B}{R} (Breya's identity)',
 * 'Five-color (WUBRG)') and only gets read when we do not.
 */
export function ciOf(deck) {
  if (deck.ci) return deck.ci;
  const raw = deck.colors || '';
  const syms = [...raw.matchAll(/\{([WUBRG])\}/gi)].map((m) => m[1].toUpperCase());
  if (syms.length) return dedupe(syms);
  const words = [...raw.matchAll(/\b(white|blue|black|red|green)\b/gi)].map((m) => WORD[m[1].toLowerCase()]);
  if (words.length) return dedupe(words);
  if (/wubrg|five.?colou?r/i.test(raw)) return 'BGRUW';
  return '';
}

/** 'Ashling, the Limitless (featured alt commander: ...)' -> the name. */
export const commanderName = (s) => String(s || '').replace(/\s*\([^)]*\)\s*$/, '').trim();

/** 'Explorers of the Deep \u2014 ... Precon' -> 'Explorers of the Deep'. */
export const deckTitle = (s) => String(s || '').split(/\s+\u2014\s+/)[0].trim() || String(s || '');

/** '2 (0 Game Changers, though ...)' -> '2'. */
export const bracketOf = (s) => (String(s || '').match(/\d+/) || [null])[0];

/**
 * Commander art for the banner. Cards we own come straight off Scryfall's
 * image CDN from the id we already store. For a commander that is not in
 * the collection — proxy decks, precons nobody has pulled apart — Scryfall
 * will redirect a name lookup to the same image, so the <img> resolves it.
 */
function bannerUrl(deck, cmdr) {
  if (deck.art_id) return imageUrl(deck.art_id, 'art_crop');
  if (!cmdr) return '';
  return `https://api.scryfall.com/cards/named?exact=${encodeURIComponent(cmdr)}&format=image&version=art_crop`;
}

function deckTile(d) {
  const cmdr = commanderName(d.commander);
  const bracket = bracketOf(d.bracket);
  const art = bannerUrl(d, cmdr);
  return h('div.deck-card', {
    title: d.name,
    onclick: () => { location.hash = `#/decks/${encodeURIComponent(d.slug)}`; },
  },
  h('div.deck-banner', art
    ? h('img', {
      src: art,
      alt: '',
      loading: 'lazy',
      // A name Scryfall does not know 404s. Drop the banner rather than
      // leaving a broken-image glyph in the tile.
      onerror: (e) => e.target.parentElement.classList.add('none'),
    })
    : null),
  h('div.deck-body',
    h('div.deck-top',
      h('strong.deck-name', deckTitle(d.name)),
      bracket ? h('span.tag.info', `bracket ${bracket}`) : null),
    h('div.deck-meta',
      identity(ciOf(d)),
      h('span.cmdr', cmdr || '—'))));
}

async function listView() {
  const root = $('#view');
  fill(root, h('div.wrap', loading('Loading decks')));
  try {
    // The commander's card gives us both the banner art and a colour
    // identity that is actual letters rather than the prose in decks.colors.
    // Grouped by name_norm so a commander owned twice resolves once.
    const decks = await api.rows(`
      SELECT d.slug, d.name, d.owner, d.commander, d.colors, d.bracket,
             c.scryfall_id AS art_id, c.color_identity AS ci
        FROM decks d
        LEFT JOIN (SELECT name_norm, MIN(id) AS id, scryfall_id, color_identity
                     FROM cards GROUP BY name_norm) c
          ON c.name_norm = lower(trim(CASE
               WHEN instr(d.commander, ' (') > 0
               THEN substr(d.commander, 1, instr(d.commander, ' (') - 1)
               ELSE d.commander END))
       ORDER BY d.owner, d.name`);

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

/**
 * Disassembling deletes the deck and hands its cards back to bulk. The
 * cards themselves never move — they are already in `cards`; what goes is
 * the deck's claim on them, which is what `free` counts.
 *
 * Irreversible and there is no undo, so it asks first, and what it shows
 * is the server's own dry run rather than a number worked out here.
 */
async function confirmDisassemble(deck) {
  let plan;
  try {
    plan = await api.disassembleDeck({ slug: deck.slug, dry_run: true });
  } catch (e) {
    toast(String(e.message), 'bad');
    return;
  }

  const go = h('button.btn.danger', `Disassemble · free ${num(plan.freed)}`);
  const close = () => scrim.remove();

  go.addEventListener('click', async () => {
    go.disabled = true;
    go.textContent = 'Working…';
    try {
      const r = await api.disassembleDeck({ slug: deck.slug });
      close();
      toast(`Disassembled ${r.deck.name} — ${num(r.freed)} card${r.freed === 1 ? '' : 's'} back in bulk`, 'ok');
      location.hash = '#/decks';
    } catch (e) {
      toast(String(e.message), 'bad');
      go.disabled = false;
      go.textContent = `Disassemble · free ${num(plan.freed)}`;
    }
  });

  const scrim = h('div.palette-scrim', {
    onclick: (e) => { if (e.target === scrim) close(); },
  }, h('div.palette', { style: { padding: '18px' } },
    h('h2', { style: { marginBottom: '4px' } }, 'Disassemble this deck?'),
    h('div.muted.small', { style: { marginBottom: '12px' } },
      `${deck.name} is deleted, along with its list and notes. `
      + `The ${num(plan.freed)} card${plan.freed === 1 ? '' : 's'} it is holding go back to `
      + `${deck.owner}'s bulk — nothing leaves the collection. This cannot be undone.`),
    plan.cards?.length
      ? h('div.table-wrap', { style: { maxHeight: '240px', overflowY: 'auto' } },
        h('table',
          h('thead', h('tr', h('th', 'Card'), h('th.num', 'Freed'))),
          h('tbody', plan.cards.map((c) => h('tr',
            h('td.t-name', c.name), h('td.num', c.qty))))))
      : h('div.small.muted', 'It is not holding anything you own.'),
    h('div.flex', { style: { marginTop: '14px' } },
      go,
      h('button.btn.ghost', { onclick: close }, 'Cancel'))));

  document.body.append(scrim);
}

/**
 * Edit the whole list as text. A decklist is what this data already is,
 * and it is what every other tool in the app speaks, so the editor is a
 * textarea rather than a row of per-card widgets.
 *
 * Replace, not merge: what is in the box is what the deck becomes. The
 * server does the diff and nothing is written until it is on screen.
 */
function editList(deck, cards) {
  // The commander gets its own field, so the list below is the 99 and
  // nothing else — no headings, no marker on one line, just cards.
  const cmdrBox = h('input', { type: 'text', placeholder: 'e.g. Alela, Cunning Conqueror' });
  cmdrBox.value = cards.filter((c) => c.role === 'commander').map((c) => c.name).join(' // ')
    || commanderName(deck.commander);

  const box = h('textarea', { rows: 18, spellcheck: false });
  box.value = cards
    .filter((c) => c.role !== 'commander')
    .map((c) => `${c.qty} ${c.name}`)
    .join('\n');

  const count = h('span.muted.small');
  const out = h('div');
  const save = h('button.btn.primary', 'Review changes');
  const close = () => scrim.remove();

  const tally = () => {
    const n = box.value.split('\n')
      .filter((l) => l.trim() && !l.trim().startsWith('#') && !l.trim().startsWith('//')).length;
    count.textContent = `${n} line${n === 1 ? '' : 's'}`;
  };
  const dirty = () => { tally(); fill(out); reviewed = null; save.textContent = 'Review changes'; };
  box.addEventListener('input', dirty);
  cmdrBox.addEventListener('input', dirty);
  tally();

  let reviewed = null;

  const pair = (label, list, render) => (list.length
    ? h('div', { style: { marginTop: '10px' } },
      h('div.small.muted', `${label} (${list.length})`),
      h('div.chips', list.slice(0, 40).map(render)),
      list.length > 40 ? h('div.small.muted', `…and ${list.length - 40} more`) : null)
    : null);

  save.addEventListener('click', async () => {
    save.disabled = true;
    try {
      if (!reviewed) {
        const plan = await api.editDeckList({
          slug: deck.slug, commander: cmdrBox.value, list: box.value, dry_run: true,
        });
        reviewed = box.value;
        fill(out,
          h('div.flex-wrap', { style: { marginTop: '12px' } },
            h('span.tag.info', 'preview — nothing saved yet'),
            h('span.muted.small',
              `${num(plan.rows)} rows · ${num(plan.card_count)} cards · ${num(plan.owned_count)} owned`)),
          pair('Added to the deck', plan.added, ([n, q]) => h('span.chip.mini.ok', `+${q} ${n}`)),
          pair('Removed from the deck', plan.removed, ([n, q]) => h('span.chip.mini.bad', `−${q} ${n}`)),
          pair('Quantity changed', plan.changed, ([n, a, b]) => h('span.chip.mini', `${n} ${a}→${b}`)),
          // The two that move real cards, not just the list.
          pair('Back to bulk', plan.returned, ([n, q]) => h('span.chip.mini.ok', `${q}× ${n}`)),
          plan.acquired?.length
            ? h('div', { style: { marginTop: '10px' } },
              h('div.small',
                h('span.tag.warn', 'added to the collection'),
                ' bulk has no spare copy of these, so saving records them as acquired'),
              h('div.chips', plan.acquired.map(([n, q]) => h('span.chip.mini.bad', `+${q} ${n}`))))
            : null,
          pair('No longer owned', plan.newly_missing, (n) => h('span.chip.mini.bad', n)),
          plan.commander_changed
            ? h('div.small', { style: { marginTop: '10px' } },
              h('span.tag.warn', 'commander'), ` → ${plan.commander || 'none'}`)
            : null,
          (!plan.added.length && !plan.removed.length && !plan.changed.length)
            ? h('div.small.muted', { style: { marginTop: '10px' } }, 'No changes.')
            : null);
        save.textContent = 'Save list';
      } else {
        const r = await api.editDeckList({
          slug: deck.slug, commander: cmdrBox.value, list: box.value,
        });
        close();
        const bought = (r.acquired || []).reduce((a, [, q]) => a + q, 0);
        toast(`Saved — ${num(r.card_count)} cards`
          + (bought ? `, ${num(bought)} added to the collection` : ''), 'ok');
        detailView(deck.slug);
      }
    } catch (e) {
      // A parse failure comes back with the offending lines; show them all.
      fill(out, h('div.err', { style: { marginTop: '12px' } },
        [e.message, ...(e.errors || [])].join('\n')));
      reviewed = null;
      save.textContent = 'Review changes';
    } finally {
      save.disabled = false;
    }
  });

  const scrim = h('div.palette-scrim', {
    onclick: (e) => { if (e.target === scrim) close(); },
  }, h('div.palette.wide', { style: { padding: '18px' } },
    h('div.flex', h('h2', 'Edit list'), h('span.spacer'), count),
    h('div.muted.small', { style: { margin: '4px 0 12px' } },
      `Replaces ${deck.name}'s list entirely — what is in the box is what the deck becomes.`),
    h('div.field', h('label', 'Commander'), cmdrBox),
    h('div.field', h('label', 'The 99'), box),
    out,
    h('div.flex', { style: { marginTop: '12px' } },
      save,
      h('button.btn.ghost', { onclick: close }, 'Cancel'))));

  document.body.append(scrim);
  box.focus();
}

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
        }, 'Search collection'),
        isAdmin()
          ? h('button.btn.sm', { onclick: () => editList(deck, cards) }, 'Edit list')
          : null,
        isAdmin()
          ? h('button.btn.sm.danger', { onclick: () => confirmDisassemble(deck) }, 'Disassemble')
          : null),

      h('div.split',
        h('div.sticky-side.stack',
          h('div.panel',
            h('div.panel-head', h('h2', 'Deck')),
            h('div.panel-body',
              colorBar(ciOf(deck)),
              h('dl.kv', { style: { marginTop: '12px' } },
                h('dt', 'Owner'), h('dd', deck.owner),
                h('dt', 'Identity'), h('dd', identity(ciOf(deck))),
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
  // A plain decklist. Commander first because that is where every tool
  // expects it, and no headings, because they are not cards.
  const order = (c) => ROLE_ORDER.indexOf(c.role || 'spell');
  const lines = [...cards]
    .sort((a, b) => order(a) - order(b) || a.name.localeCompare(b.name))
    .map((c) => `${c.qty} ${c.name}`);
  download(`${deck.slug}.txt`, lines.join('\n'));
  toast('Decklist downloaded', 'ok');
}

export function show(slug) {
  if (!slug) return listView();
  if (slug === '_gaps') return gapsView();
  return detailView(decodeURIComponent(slug));
}
