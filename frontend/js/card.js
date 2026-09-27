// Card detail drawer. Opens over whatever view you were in.

import * as api from './api.js';
import {
  h, $, fill, num, imageUrl, hasBackFace, manaCost, identity,
  loading, errorBox, toast,
} from './util.js';
import { isAdmin } from './admin.js';
import { exact, priceReason } from './prices.js';

let onClose = null;
let current = null;

/** The card the drawer is showing, or null. */
export const openCardId = () => current;

export function closeCard() {
  $('#drawer').hidden = true;
  $('#drawer-scrim').hidden = true;
  current = null;
  if (onClose) { const f = onClose; onClose = null; f(); }
}

/** Open the drawer for a cards.id. `after` runs on close, to refresh a list. */
export async function openCard(id, after) {
  onClose = after || null;
  current = id;
  const drawer = $('#drawer');
  const scrim = $('#drawer-scrim');
  drawer.hidden = false;
  scrim.hidden = false;
  drawer.focus();
  fill($('#drawer-body'), loading('Loading card'));

  try {
    const data = await fetchCard(id);
    if (current !== id) return;
    fill($('#drawer-body'), view(data));
  } catch (e) {
    fill($('#drawer-body'), h('div.drawer-head', h('h2', 'Error'), closeBtn()), h('div.drawer-body', errorBox(e)));
  }
}

const closeBtn = () => h('button.icon-btn', { onclick: closeCard, title: 'Close (esc)', 'aria-label': 'Close' },
  h('svg', { viewBox: '0 0 24 24', html: '<path d="M6 6l12 12M18 6L6 18"/>' }));

async function fetchCard(id) {
  const card = await api.one('SELECT * FROM cards WHERE id = ?', [id]);
  if (!card) throw new Error(`no card with id ${id}`);

  const [faces, printings, decks, tags, keywords, legalities, rulings, usage] = await Promise.all([
    api.rows('SELECT * FROM card_faces WHERE card_id = ? ORDER BY face_index', [id]),
    // Price comes straight from card_prices, which already picks the
    // figure matching each printing's finish.
    api.rows(`SELECT c.id, c.setcode, c.set_name, c.collector_number, c.finish,
                     c.qty, c.rarity, c.released_at, c.scryfall_id, c.layout,
                     cp.price, cp.tcg_url, cp.updated_at AS priced_at
                FROM cards c JOIN card_prices cp ON cp.card_id = c.id
               WHERE c.owner = ? AND c.name_norm = ?
               ORDER BY c.released_at DESC`, [card.owner, card.name_norm]),
    api.rows(`SELECT d.slug, d.name, d.owner, d.is_proxy, d.status, dc.qty, dc.role
                FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id
               WHERE dc.name_norm = ? ORDER BY d.owner, d.name`, [card.name_norm]),
    api.rows(`SELECT t.slug, t.label FROM card_tags ct
                LEFT JOIN tags t ON t.slug = ct.tag_slug
               WHERE ct.card_id = ? ORDER BY 1`, [id]),
    api.query('SELECT keyword FROM card_keywords WHERE card_id = ? ORDER BY 1', [id]),
    api.rows('SELECT format, status FROM legalities WHERE oracle_id = ? ORDER BY format', [card.oracle_id]),
    api.rows('SELECT published_at, source, comment FROM rulings WHERE oracle_id = ? ORDER BY published_at', [card.oracle_id]),
    api.one('SELECT owned, in_decks, free, deck_count FROM card_usage WHERE owner = ? AND name_norm = ?', [card.owner, card.name_norm]),
  ]);

  return { card, faces, printings, decks, tags, keywords: keywords.rows.flat(), legalities, rulings, usage };
}

function imageBlock(card) {
  const twoSided = hasBackFace(card.layout);
  let face = 'front';
  const img = h('img.drawer-art', {
    src: imageUrl(card.scryfall_id, 'normal', 'front'),
    alt: card.name,
    onerror: (e) => { e.target.style.display = 'none'; },
  });
  if (!twoSided) return img;

  return h('div',
    img,
    h('div', { style: { textAlign: 'center', marginBottom: '10px' } },
      h('button.btn.sm', {
        onclick: () => {
          face = face === 'front' ? 'back' : 'front';
          img.src = imageUrl(card.scryfall_id, 'normal', face);
        },
      }, '⟲ Flip')));
}

/** +1 / −1 straight from the drawer, using the exact printing. */
async function adjust(card, delta) {
  const line = `1 ${card.name.split(' // ')[0]} (${card.setcode.toUpperCase()}) ${card.collector_number}`
    + (card.finish === 'foil' ? ' *F*' : card.finish === 'etched' ? ' *E*' : '');
  try {
    const r = delta > 0
      ? await api.addCards({ owner: card.owner, list: line })
      : await api.removeCards({ owner: card.owner, list: line });
    if (r.errors?.length) { toast(r.errors[0], 'bad'); return; }
    const ch = r.changes?.[0];
    toast(ch ? `${ch[0]}: ${ch[4]} → ${ch[5]}` : 'Done', 'ok');
    openCard(card.id, onClose);
  } catch (e) {
    toast(String(e.message), 'bad');
  }
}

/** What every copy of this card is worth, across printings and finishes. */
function stackValue(printings) {
  let sum = 0;
  let any = false;
  for (const p of printings) {
    if (p.price !== null && p.price !== undefined) { sum += p.price * p.qty; any = true; }
  }
  const when = printings.find((p) => p.priced_at)?.priced_at;
  return h('span', { title: when ? `Priced ${when.slice(0, 10)}` : '' },
    any ? exact(sum) : priceReason(printings[0]));
}

function view({ card, faces, printings, decks, tags, keywords, legalities, rulings, usage }) {
  const totalQty = printings.reduce((a, p) => a + p.qty, 0);

  const head = h('div.drawer-head',
    h('div', { style: { flex: '1' } },
      h('h2', card.name),
      h('div.small.muted', card.type_line || '')),
    manaCost(card.mana_cost),
    closeBtn());

  const body = h('div.drawer-body',
    imageBlock(card),

    // Nothing here while locked, not even an invitation to unlock. The
    // header lock is the one way in.
    isAdmin()
      ? h('div.flex-wrap', { style: { justifyContent: 'center', marginBottom: '14px' } },
        h('button.btn.sm', { onclick: () => adjust(card, +1), title: 'Add one of this printing' }, '＋ Add one'),
        h('button.btn.sm.danger', { onclick: () => adjust(card, -1), title: 'Remove one of this printing' }, '− Remove one'))
      : null,

    h('dl.kv',
      h('dt', 'Owner'), h('dd', card.owner),
      h('dt', 'Owned'), h('dd', `${num(totalQty)} across ${printings.length} printing${printings.length === 1 ? '' : 's'}`),
      usage ? [h('dt', 'Free'), h('dd', usage.free > 0
        ? h('span.tag.ok', `${usage.free} unassigned`)
        : h('span.tag.warn', `all ${usage.owned} in decks`))] : null,
      h('dt', 'Stack value'), h('dd', stackValue(printings)),
      h('dt', 'Identity'), h('dd', identity(card.color_identity)),
      card.cmc !== null ? [h('dt', 'Mana value'), h('dd', card.cmc)] : null,
      card.power !== null ? [h('dt', 'P/T'), h('dd', `${card.power}/${card.toughness}`)] : null,
      card.loyalty ? [h('dt', 'Loyalty'), h('dd', card.loyalty)] : null,
      card.edhrec_rank ? [h('dt', 'EDHREC'), h('dd', `#${num(card.edhrec_rank)}`)] : null,
      card.reserved ? [h('dt', 'Reserved'), h('dd', h('span.tag.warn', 'reserved list'))] : null,
      card.game_changer ? [h('dt', 'Bracket'), h('dd', h('span.tag.bad', 'Game Changer'))] : null),

    faces.length
      ? h('div.sec', h('h3', 'Faces'), faces.map((f) => h('div', { style: { marginBottom: '12px' } },
        h('div.flex', h('strong', f.name), manaCost(f.mana_cost)),
        h('div.small.muted', f.type_line || ''),
        f.oracle_text ? h('div.oracle', { style: { marginTop: '4px' } }, f.oracle_text) : null,
        f.power !== null ? h('div.small.muted', `${f.power}/${f.toughness}`) : null)))
      : (card.oracle_text ? h('div.sec', h('h3', 'Oracle text'), h('div.oracle', card.oracle_text)) : null),

    card.flavor_text ? h('div.flavor', card.flavor_text) : null,

    keywords.length ? h('div.sec', h('h3', 'Keywords'),
      h('div.chips', keywords.map((k) => h('span.chip.mini', {
        onclick: () => { location.hash = `#/search?keyword=${encodeURIComponent(k)}`; closeCard(); },
      }, k)))) : null,

    h('div.sec', h('h3', `Printings you own (${printings.length})`),
      h('div.table-wrap', h('table',
        h('thead', h('tr', h('th', 'Set'), h('th', '#'), h('th', 'Finish'), h('th.num', 'Qty'), h('th', 'Rarity'), h('th.num', 'Price'))),
        h('tbody', printings.map((p) => h(`tr${p.id === card.id ? '' : '.clickable'}`, {
          onclick: p.id === card.id ? null : () => openCard(p.id, onClose),
          style: p.id === card.id ? { background: 'var(--accent-dim)' } : null,
        },
        h('td', h('span.mono', p.setcode.toUpperCase()), ' ', h('span.small.muted', p.set_name || '')),
        h('td.mono', p.collector_number),
        h('td.small', p.finish),
        h('td.num', p.qty),
        h('td', h('span.tag', p.rarity || '—')),
        h('td.num', p.price === null
          ? h('span.muted.small', priceReason(p))
          : exact(p.price))))))),

    decks.length ? h('div.sec', h('h3', `In ${decks.length} deck${decks.length === 1 ? '' : 's'}`),
      h('div.stack', decks.map((d) => h('div.flex', { style: { gap: '8px' } },
        h('a', {
          href: `#/decks/${encodeURIComponent(d.slug)}`,
          onclick: () => closeCard(),
        }, d.name),
        h('span.tag.mini', `${d.qty}× ${d.role || 'card'}`),
        d.is_proxy ? h('span.tag.warn', 'proxy') : null,
        h('span.muted.small', d.owner))))) : null,

    tags.length ? h('div.sec', h('h3', `Tags (${tags.length})`),
      h('div.chips', tags.map((t) => h('span.chip.mini', {
        title: t.label || t.slug,
        onclick: () => { location.hash = `#/search?tag=${encodeURIComponent(t.slug)}`; closeCard(); },
      }, t.slug)))) : null,

    legalities.length ? h('div.sec', h('h3', 'Legality'),
      h('div.chips', legalities.map((l) => h('span', {
        class: `tag ${l.status === 'legal' ? 'ok' : l.status === 'banned' ? 'bad' : 'warn'}`,
      }, `${l.format}: ${l.status}`)))) : null,

    rulings.length ? h('div.sec', h('h3', `Rulings (${rulings.length})`),
      h('div.stack', rulings.map((r) => h('div',
        h('div.small.muted', r.published_at),
        h('div.small', r.comment))))) : null,

    h('div.sec', h('h3', 'Elsewhere'),
      h('div.flex-wrap',
        h('a.btn.sm', {
          href: `https://scryfall.com/card/${card.setcode}/${card.collector_number}`,
          target: '_blank', rel: 'noopener',
        }, 'Scryfall ↗'),
        h('a.btn.sm', {
          href: `https://edhrec.com/cards/${card.name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')}`,
          target: '_blank', rel: 'noopener',
        }, 'EDHREC ↗'),
        (printings.find((p) => p.tcg_url) || {}).tcg_url
          ? h('a.btn.sm', {
            href: printings.find((p) => p.tcg_url).tcg_url,
            target: '_blank', rel: 'noopener',
          }, 'TCGplayer ↗')
          : null))));

  return h('div', head, body);
}
