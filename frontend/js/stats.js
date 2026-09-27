// Stats — what the collection actually looks like, in one screen.

import * as api from './api.js';
import { h, $, fill, num, barList, loading, errorBox } from './util.js';
import { openCard } from './card.js';

const COLOR_CSS = { W: 'var(--w)', U: 'var(--u)', B: 'var(--b)', R: 'var(--r)', G: 'var(--g)', C: 'var(--c)' };
const COLOR_NAME = { W: 'White', U: 'Blue', B: 'Black', R: 'Red', G: 'Green', C: 'Colorless' };

const tile = (n, label, title) => h('div.tile', { title }, h('div.n', num(n)), h('div.l', label));

const money = (v) => (v ? `$${num(v)}` : '—');

/**
 * The two collections side by side, whatever the page is scoped to.
 *
 * The headline tiles below answer "how much is there"; this answers "whose
 * is it", which was the one thing the page could not say when every number
 * on it was quietly Matt's.
 */
function perOwnerPanel(rows, current) {
  if (rows.length < 2) return null;
  const totalOf = (k) => rows.reduce((a, r) => a + (r[k] || 0), 0);
  const cell = (r, k, fmt = num) => h('td.num', fmt(r[k] || 0));

  return h('div.panel', { style: { marginBottom: '18px' } },
    h('div.panel-head', h('h2', 'By collection')),
    h('div.table-wrap', h('table',
      h('thead', h('tr',
        h('th', 'Whose'), h('th.num', 'Physical'), h('th.num', 'Unique'),
        h('th.num', 'Printings'), h('th.num', 'Unassigned'), h('th.num', 'Decks'),
        h('th.num', 'Value'))),
      h('tbody',
        rows.map((r) => h(`tr${r.owner === current ? '.on' : ''}`,
          h('td', h('a', { href: `#/stats/${r.owner}` }, label(r.owner))),
          cell(r, 'physical'), cell(r, 'uniques'), cell(r, 'printings'),
          cell(r, 'free'), cell(r, 'decks'), cell(r, 'value', money))),
        h('tr.total',
          h('td', h('strong', 'Both')),
          h('td.num', num(totalOf('physical'))), h('td.num', num(totalOf('uniques'))),
          h('td.num', num(totalOf('printings'))), h('td.num', num(totalOf('free'))),
          h('td.num', num(totalOf('decks'))), h('td.num', money(totalOf('value'))))))));
}

function panel(title, body, extra) {
  return h('div.panel',
    h('div.panel-head', h('h2', title), extra ? [h('span.spacer'), extra] : null),
    h('div.panel-body', body));
}

const OWNERS = ['matt', 'kayla'];
const label = (o) => (o === 'both' ? 'Both collections' : o[0].toUpperCase() + o.slice(1));

/**
 * Stats used to be Matt's, silently. The scope is part of the URL now, so
 * #/stats/kayla is a link and the page says whose numbers these are.
 */
export async function show(rest) {
  const owner = OWNERS.includes(rest) ? rest : 'both';
  const root = $('#view');
  fill(root, h('div.wrap', loading('Crunching numbers')));

  // Everything below filters on one owner or neither. Two forms because
  // half the queries alias `cards` and half do not.
  const only = owner !== 'both';
  const w = only ? 'owner = ?' : '1=1';
  const wc = only ? 'c.owner = ?' : '1=1';
  const p1 = only ? [owner] : [];

  try {
    const [totals, perOwner, curve, rarity, types, sets, colors, topFree, dupes, decks, tags,
      recent, priciest] = await Promise.all([
      api.one(`SELECT
          (SELECT COUNT(*) FROM cards WHERE ${w})            AS printings,
          (SELECT COUNT(*) FROM totals WHERE ${w})           AS uniques,
          (SELECT SUM(qty) FROM cards WHERE ${w})            AS physical,
          (SELECT COUNT(*) FROM decks WHERE ${w})            AS decks,
          (SELECT SUM(free) FROM bulk_cards WHERE ${w})      AS free,
          (SELECT COUNT(DISTINCT setcode) FROM cards WHERE ${w}) AS sets,
          (SELECT COUNT(*) FROM cards
            WHERE finish!='nonfoil' AND ${w})                AS foils,
          (SELECT ROUND(SUM(qty * price)) FROM card_prices
             WHERE price IS NOT NULL AND ${w})               AS value,
          (SELECT MAX(updated_at) FROM prices)               AS priced_at,
          (SELECT COUNT(*) FROM cards c LEFT JOIN prices p
             ON p.scryfall_id = c.scryfall_id
           WHERE p.usd IS NULL AND ${wc})                    AS unpriced,
          (SELECT COUNT(*) FROM cards
            WHERE released_at > date('now') AND ${w})        AS unreleased`,
      [...p1, ...p1, ...p1, ...p1, ...p1, ...p1, ...p1, ...p1, ...p1, ...p1]),

      // The side-by-side, always both, whatever the page is scoped to.
      api.rows(`SELECT c.owner,
                       SUM(c.qty)                                  AS physical,
                       COUNT(*)                                    AS printings,
                       (SELECT COUNT(*) FROM totals t
                         WHERE t.owner = c.owner)                  AS uniques,
                       (SELECT COALESCE(SUM(free),0) FROM bulk_cards b
                         WHERE b.owner = c.owner)                  AS free,
                       (SELECT COUNT(*) FROM decks d
                         WHERE d.owner = c.owner)                  AS decks,
                       (SELECT ROUND(SUM(qty * price)) FROM card_prices cp
                         WHERE cp.price IS NOT NULL
                           AND cp.owner = c.owner)                 AS value
                  FROM cards c GROUP BY c.owner ORDER BY c.owner`),

      api.rows(`SELECT CASE WHEN cmc >= 7 THEN 7 ELSE CAST(cmc AS INTEGER) END AS mv,
                       SUM(qty) AS n
                  FROM cards WHERE ${w} AND type_line NOT LIKE '%Land%'
                 GROUP BY mv ORDER BY mv`, p1),

      api.rows(`SELECT rarity, COUNT(*) AS n FROM cards WHERE ${w} GROUP BY 1 ORDER BY n DESC`, p1),

      api.rows(`SELECT t.type, COUNT(DISTINCT c.id) AS n
                  FROM card_types t JOIN cards c ON c.id = t.card_id
                 WHERE t.kind = 'type' AND ${wc}
                 GROUP BY 1 ORDER BY n DESC LIMIT 12`, p1),

      api.rows(`SELECT upper(setcode) AS s, set_name, COUNT(*) AS n
                  FROM cards WHERE ${w} GROUP BY setcode ORDER BY n DESC LIMIT 12`, p1),

      api.rows(`SELECT COALESCE(NULLIF(color_identity,''),'C') AS ci, COUNT(*) AS n
                  FROM cards WHERE ${w} GROUP BY 1 ORDER BY n DESC LIMIT 12`, p1),

      api.rows(`SELECT name, name_norm, free, owned FROM bulk_cards
                 WHERE ${w} AND name_norm NOT IN
                   ('plains','island','swamp','mountain','forest','wastes')
                 ORDER BY free DESC LIMIT 12`, p1),

      api.rows(`SELECT name, total_qty AS n, num_printings FROM totals
                 WHERE ${w} AND name_norm NOT IN
                   ('plains','island','swamp','mountain','forest','wastes')
                 ORDER BY total_qty DESC LIMIT 12`, p1),

      api.rows(`SELECT name, slug, card_count, owned_count, is_proxy
                  FROM decks WHERE ${w} ORDER BY card_count DESC LIMIT 10`, p1),

      api.rows(`SELECT ct.tag_slug, COUNT(DISTINCT c.id) AS n
                  FROM card_tags ct JOIN cards c ON c.id = ct.card_id
                 WHERE ${wc} GROUP BY 1 ORDER BY n DESC LIMIT 14`, p1),

      api.rows(`SELECT name, setcode, released_at, MIN(id) AS id
                  FROM cards WHERE ${w} AND released_at IS NOT NULL
                 GROUP BY name_norm ORDER BY released_at DESC LIMIT 10`, p1),

      api.rows(`SELECT c.name, c.setcode, c.qty, cp.price,
                       ROUND(c.qty * cp.price, 2) AS value, c.id
                  FROM cards c JOIN card_prices cp ON cp.card_id = c.id
                 WHERE ${wc} AND cp.price IS NOT NULL
                 ORDER BY value DESC LIMIT 12`, p1),
    ]);

    const curveMax = Math.max(1, ...curve.map((c) => c.n));

    fill(root, h('div.wrap',
      h('div.page-head',
        h('h1', 'Collection'),
        h('span.sub', label(owner)),
        h('span.spacer'),
        // Links, not buttons: the scope is in the URL, so a view of
        // Kayla's collection is a thing you can send someone.
        h('div.seg', ['both', ...OWNERS].map((o) => h('a', {
          class: owner === o ? 'on' : '',
          href: o === 'both' ? '#/stats' : `#/stats/${o}`,
        }, o === 'both' ? 'Both' : label(o))))),

      perOwnerPanel(perOwner, owner),

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
        h('div.l', 'market value'),
        totals.unpriced
          ? h('div.small.muted', { style: { marginTop: '4px', fontSize: '10.5px' } },
            `${num(totals.unpriced)} unpriced`,
            totals.unreleased ? ` · ${num(totals.unreleased)} unreleased` : '')
          : null)),

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
