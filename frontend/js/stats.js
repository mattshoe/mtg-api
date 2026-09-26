// Stats — what the collection actually looks like, in one screen.

import * as api from './api.js';
import { h, $, fill, num, barList, loading, errorBox } from './util.js';
import { openCard } from './card.js';

const COLOR_CSS = { W: 'var(--w)', U: 'var(--u)', B: 'var(--b)', R: 'var(--r)', G: 'var(--g)', C: 'var(--c)' };
const COLOR_NAME = { W: 'White', U: 'Blue', B: 'Black', R: 'Red', G: 'Green', C: 'Colorless' };

const tile = (n, label, title) => h('div.tile', { title }, h('div.n', num(n)), h('div.l', label));

function panel(title, body, extra) {
  return h('div.panel',
    h('div.panel-head', h('h2', title), extra ? [h('span.spacer'), extra] : null),
    h('div.panel-body', body));
}

export async function show() {
  const root = $('#view');
  fill(root, h('div.wrap', loading('Crunching numbers')));

  try {
    const owner = 'matt';
    const [totals, curve, rarity, types, sets, colors, topFree, dupes, decks, tags, recent,
      priciest] = await Promise.all([
      api.one(`SELECT
          (SELECT COUNT(*) FROM cards)                       AS printings,
          (SELECT COUNT(*) FROM totals)                      AS uniques,
          (SELECT SUM(qty) FROM cards)                       AS physical,
          (SELECT COUNT(*) FROM decks)                       AS decks,
          (SELECT SUM(free) FROM bulk_cards)                 AS free,
          (SELECT COUNT(DISTINCT setcode) FROM cards)        AS sets,
          (SELECT COUNT(*) FROM cards WHERE finish!='nonfoil') AS foils,
          (SELECT COUNT(*) FROM cards WHERE owner='matt')    AS matt,
          (SELECT COUNT(*) FROM cards WHERE owner='kayla')   AS kayla,
          (SELECT ROUND(SUM(qty * price)) FROM card_prices
             WHERE price IS NOT NULL)                        AS value,
          (SELECT ROUND(SUM(qty * price)) FROM card_prices
             WHERE price IS NOT NULL AND owner = 'matt')     AS matt_value,
          (SELECT MAX(updated_at) FROM prices)               AS priced_at`),

      api.rows(`SELECT CASE WHEN cmc >= 7 THEN 7 ELSE CAST(cmc AS INTEGER) END AS mv,
                       SUM(qty) AS n
                  FROM cards WHERE owner = ? AND type_line NOT LIKE '%Land%'
                 GROUP BY mv ORDER BY mv`, [owner]),

      api.rows('SELECT rarity, COUNT(*) AS n FROM cards WHERE owner = ? GROUP BY 1 ORDER BY n DESC', [owner]),

      api.rows(`SELECT t.type, COUNT(DISTINCT c.id) AS n
                  FROM card_types t JOIN cards c ON c.id = t.card_id
                 WHERE t.kind = 'type' AND c.owner = ?
                 GROUP BY 1 ORDER BY n DESC LIMIT 12`, [owner]),

      api.rows(`SELECT upper(setcode) AS s, set_name, COUNT(*) AS n
                  FROM cards WHERE owner = ? GROUP BY setcode ORDER BY n DESC LIMIT 12`, [owner]),

      api.rows(`SELECT COALESCE(NULLIF(color_identity,''),'C') AS ci, COUNT(*) AS n
                  FROM cards WHERE owner = ? GROUP BY 1 ORDER BY n DESC LIMIT 12`, [owner]),

      api.rows(`SELECT name, name_norm, free, owned FROM bulk_cards
                 WHERE owner = ? AND name_norm NOT IN
                   ('plains','island','swamp','mountain','forest','wastes')
                 ORDER BY free DESC LIMIT 12`, [owner]),

      api.rows(`SELECT name, total_qty AS n, num_printings FROM totals
                 WHERE owner = ? AND name_norm NOT IN
                   ('plains','island','swamp','mountain','forest','wastes')
                 ORDER BY total_qty DESC LIMIT 12`, [owner]),

      api.rows(`SELECT name, slug, card_count, owned_count, is_proxy
                  FROM decks WHERE owner = ? ORDER BY card_count DESC LIMIT 10`, [owner]),

      api.rows(`SELECT ct.tag_slug, COUNT(DISTINCT c.id) AS n
                  FROM card_tags ct JOIN cards c ON c.id = ct.card_id
                 WHERE c.owner = ? GROUP BY 1 ORDER BY n DESC LIMIT 14`, [owner]),

      api.rows(`SELECT name, setcode, released_at, MIN(id) AS id
                  FROM cards WHERE owner = ? AND released_at IS NOT NULL
                 GROUP BY name_norm ORDER BY released_at DESC LIMIT 10`, [owner]),

      api.rows(`SELECT c.name, c.setcode, c.qty, cp.price,
                       ROUND(c.qty * cp.price, 2) AS value, c.id
                  FROM cards c JOIN card_prices cp ON cp.card_id = c.id
                 WHERE c.owner = ? AND cp.price IS NOT NULL
                 ORDER BY value DESC LIMIT 12`, [owner]),
    ]);

    const curveMax = Math.max(1, ...curve.map((c) => c.n));

    fill(root, h('div.wrap',
      h('div.page-head',
        h('h1', 'Collection'),
        h('span.sub', 'Matt unless noted')),

      h('div.tiles', { style: { marginBottom: '18px' } },
        tile(totals.physical, 'physical cards', 'Every copy, counted'),
        tile(totals.uniques, 'unique cards'),
        tile(totals.printings, 'printings'),
        tile(totals.free, 'unassigned', 'Copies not committed to a built deck'),
        tile(totals.decks, 'decks'),
        tile(totals.sets, 'sets'),
        tile(totals.foils, 'foil / etched'),
        h('div.tile', {
          title: totals.priced_at ? `Prices from ${totals.priced_at.slice(0, 10)}` : '',
        },
        h('div.n', totals.value ? `$${num(totals.value)}` : '—'),
        h('div.l', 'market value'))),

      h('div.split', { style: { gridTemplateColumns: 'minmax(0,1fr) minmax(0,1fr)' } },
        h('div.stack',
          panel('Mana curve', curve.length
            ? h('div', curve.map((c) => h('div.bar-row',
              h('div.lbl', c.mv >= 7 ? '7+' : String(c.mv)),
              h('div.bar-track', h('div.bar-fill', { style: { width: `${(c.n / curveMax) * 100}%` } })),
              h('div.val', num(c.n)))))
            : h('div.muted', 'No data'),
          h('span.muted.small', 'nonland, by copy')),

          panel('Colour identity', barList(colors.map((c) => ({
            label: c.ci.split('').map((x) => COLOR_NAME[x] || x).join('/'),
            value: c.n,
            color: c.ci.length === 1 ? COLOR_CSS[c.ci] : 'var(--accent)',
          })))),

          panel('Card types', barList(types.map((t) => ({ label: t.type, value: t.n })))),

          panel('Rarity', barList(rarity.map((r) => ({
            label: r.rarity || 'unknown',
            value: r.n,
            color: { mythic: 'var(--r)', rare: 'var(--accent)', uncommon: 'var(--c)' }[r.rarity] || 'var(--text-3)',
          }))))),

        h('div.stack',
          panel('Most unassigned copies', h('div.table-wrap', h('table',
            h('thead', h('tr', h('th', 'Card'), h('th.num', 'Free'), h('th.num', 'Owned'))),
            h('tbody', topFree.map((c) => h('tr.clickable', {
              onclick: () => { location.hash = `#/search?q=${encodeURIComponent(c.name)}`; },
            }, h('td.t-name', c.name), h('td.num', h('span.tag.ok', c.free)), h('td.num.muted', c.owned)))))),
          h('span.muted.small', 'basics excluded')),

          panel('Most copies owned', h('div.table-wrap', h('table',
            h('thead', h('tr', h('th', 'Card'), h('th.num', 'Copies'), h('th.num', 'Printings'))),
            h('tbody', dupes.map((c) => h('tr.clickable', {
              onclick: () => { location.hash = `#/search?q=${encodeURIComponent(c.name)}`; },
            }, h('td.t-name', c.name), h('td.num', c.n), h('td.num.muted', c.num_printings))))))),

          panel('Biggest sets', barList(sets.map((s) => ({ label: s.s, value: s.n })))),

          panel('Common tags', h('div.chips', tags.map((t) => h('span.chip.mini', {
            onclick: () => { location.hash = `#/search?tag=${encodeURIComponent(t.tag_slug)}`; },
          }, t.tag_slug, ' ', h('span.muted', num(t.n)))))),

          panel('Decks by size', h('div.table-wrap', h('table',
            h('thead', h('tr', h('th', 'Deck'), h('th.num', 'Cards'), h('th.num', 'Owned'))),
            h('tbody', decks.map((d) => h('tr.clickable', {
              onclick: () => { location.hash = `#/decks/${encodeURIComponent(d.slug)}`; },
            },
            h('td', d.name, d.is_proxy ? h('span.tag.warn', { style: { marginLeft: '6px' } }, 'proxy') : null),
            h('td.num', d.card_count),
            h('td.num', d.owned_count < d.card_count ? h('span.tag.warn', d.owned_count) : d.owned_count))))))),

          panel('Most valuable', h('div.table-wrap', h('table',
            h('thead', h('tr', h('th', 'Card'), h('th.num', 'Each'), h('th.num', 'Qty'), h('th.num', 'Value'))),
            h('tbody', priciest.map((c) => h('tr.clickable', { onclick: () => openCard(c.id) },
              h('td.t-name', c.name, ' ', h('span.mono.small.muted', (c.setcode || '').toUpperCase())),
              h('td.num', `$${c.price.toFixed(2)}`),
              h('td.num.muted', c.qty),
              h('td.num', `$${c.value.toFixed(2)}`))))))),

          panel('Newest printings', h('div.table-wrap', h('table',
            h('tbody', recent.map((c) => h('tr.clickable', { onclick: () => openCard(c.id) },
              h('td.t-name', c.name),
              h('td.mono.small', (c.setcode || '').toUpperCase()),
              h('td.small.muted.right', c.released_at)))))))))));
  } catch (e) {
    fill(root, h('div.wrap', errorBox(e)));
  }
}
