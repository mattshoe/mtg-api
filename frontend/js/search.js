// Search. The filter model and SQL live in filters.js; this is the UI.

import * as api from './api.js';
import {
  h, $, fill, num, debounce, imageUrl, manaCost, COLORS,
  RARITY_ORDER, download, toCsv, toast, store, loading, errorBox, empty,
} from './util.js';
import { openCard } from './card.js';
import {
  DEFAULTS, SORTS, COLOR_MODES, FLAGS, buildQuery, toHash, fromHash, activeCount,
} from './filters.js';
import { cheatsheet } from './cheatsheet.js';

const PAGE_SIZES = [24, 48, 96, 200];

let state = { ...DEFAULTS };
let lastRun = null;
let facets = null;
let advError = null;
let resultsEl;

function push(patch, { resetPage = true } = {}) {
  state = { ...state, ...patch };
  if (resetPage && !('page' in patch)) state.page = 1;
  const hash = toHash(state);
  if (hash !== location.hash) history.replaceState(null, '', hash);
  run();
}

// ------------------------------------------------------------- controls

const setter = (k) => (e) => push({ [k]: e.target.value });
const lazy = (k) => debounce((e) => push({ [k]: e.target.value }), 350);

function toggleIn(key, value) {
  const cur = state[key];
  push({ [key]: cur.includes(value) ? cur.filter((x) => x !== value) : [...cur, value] });
}

function chips(values, key, { mini = true } = {}) {
  return h('div.chips', values.map((v) => {
    const [val, label] = Array.isArray(v) ? v : [v, v];
    return h(`button.chip${mini ? '.mini' : ''}`, {
      class: state[key].includes(val) ? 'on' : '',
      onclick: () => toggleIn(key, val),
    }, label);
  }));
}

function field(label, ...kids) {
  return h('div.field', label ? h('label', label) : null, ...kids);
}

function textField(label, key, placeholder, list) {
  return field(label, h('input', {
    type: 'search', value: state[key], placeholder, list,
    oninput: lazy(key),
  }));
}

function rangeField(label, minKey, maxKey, { step = '1', min = '0' } = {}) {
  return field(label, h('div.row',
    h('input', { type: 'number', step, min, placeholder: 'min', value: state[minKey], oninput: lazy(minKey) }),
    h('input', { type: 'number', step, min, placeholder: 'max', value: state[maxKey], oninput: lazy(maxKey) })));
}

/** Power / toughness / loyalty: an operator plus a number. */
function statField(label, opKey, valKey) {
  return field(label, h('div.row',
    h('select', { style: { flex: '0 0 74px' }, value: state[opKey], onchange: setter(opKey) },
      ['>=', '<=', '=', '>', '<'].map((o) => h('option', { value: o, selected: state[opKey] === o }, o))),
    h('input', { type: 'number', placeholder: 'any', value: state[valKey], oninput: lazy(valKey) })));
}

/** Any / yes / no. */
function triField(label, key) {
  return field(label, h('div.seg',
    [['', 'Any'], ['yes', 'Yes'], ['no', 'No']].map(([v, l]) => h('button', {
      class: state[key] === v ? 'on' : '',
      onclick: () => push({ [key]: v }),
    }, l))));
}

function section(title, open, ...kids) {
  return h('details.adv', { open }, h('summary', title), h('div.stack', ...kids));
}

// ---------------------------------------------------------------- colour

function colorPanel() {
  return h('div.panel',
    h('div.panel-head', h('h2', 'Colour')),
    h('div.panel-body',
      field('Match against', h('div.seg',
        [['id', 'Colour identity'], ['card', 'Printed colour']].map(([v, l]) => h('button', {
          class: state.colorTarget === v ? 'on' : '',
          title: v === 'id' ? 'What a commander allows — the usual one' : 'The colours printed on the card itself',
          onclick: () => push({ colorTarget: v }),
        }, l)))),

      field('How to match', h('div.mode-grid',
        COLOR_MODES.map(([v, label, hint]) => h('button.chip', {
          class: state.colorMode === v ? 'on' : '',
          title: hint,
          onclick: () => push({ colorMode: v }),
        }, label)))),

      h('div.mode-hint.small.muted',
        (COLOR_MODES.find(([v]) => v === state.colorMode) || [])[2] || ''),

      field('Colours', h('div.pips',
        COLORS.map(([c, name]) => h('button.pip', {
          dataset: { c },
          class: state.colors.includes(c) ? 'on' : '',
          title: name,
          onclick: () => toggleIn('colors', c),
        }, c)))),

      h('div.flex-wrap', { style: { marginTop: '-4px' } },
        h('button.chip.mini', { onclick: () => push({ colors: [] }) }, 'Clear'),
        h('button.chip.mini', { onclick: () => push({ colors: ['W', 'U', 'B', 'R', 'G'] }) }, 'All five'),
        h('button.chip.mini', { onclick: () => push({ colors: ['C'], colorMode: 'exactly' }) }, 'Colourless only')),

      rangeField('Number of colours', 'ciMin', 'ciMax'),

      field('Produces mana', h('div.pips',
        COLORS.map(([c, name]) => h('button.pip', {
          dataset: { c },
          class: state.produces.includes(c) ? 'on' : '',
          title: `Taps for ${name}`,
          onclick: () => toggleIn('produces', c),
        }, c))))));
}

// ---------------------------------------------------------------- panel

function filterPanel() {
  const n = activeCount(state);

  return h('div.stack.filters-panel', { id: 'filters' },
    h('div.panel',
      h('div.panel-head',
        h('h2', 'Filters'),
        n ? h('span.tag.info', `${n} active`) : null,
        h('span.spacer'),
        h('button.btn.sm.ghost', {
          onclick: () => push({ ...DEFAULTS, owner: state.owner, view: state.view, size: state.size }),
        }, 'Reset')),
      h('div.panel-body',
        field('Whose collection', h('div.seg',
          ['matt', 'kayla', 'both'].map((o) => h('button', {
            class: state.owner === o ? 'on' : '',
            onclick: () => push({ owner: o }),
          }, o === 'both' ? 'Both' : o[0].toUpperCase() + o.slice(1))))),

        textField('Name contains', 'q', 'sol ring'),
        textField('Rules text', 'text', 'draw a card', undefined),
        h('div.small.muted', { style: { marginTop: '-8px' } }, 'Full-text, stemmed. Use “Exact text” below for a literal substring.'),

        field('Pool', h('div.seg',
          [['all', 'All'], ['free', 'Unassigned'], ['committed', 'In decks']].map(([v, l]) => h('button', {
            class: state.pool === v ? 'on' : '',
            title: v === 'free' ? 'Copies not committed to a built deck' : '',
            onclick: () => push({ pool: v }),
          }, l)))),

        field('Card type', chips(facets?.types || [], 'types')),
        field('Rarity', chips(RARITY_ORDER, 'rarities')))),

    colorPanel(),

    h('div.panel',
      h('div.panel-head', h('h2', 'Everything else')),
      h('div.panel-body.stack',
        section('Mana & stats', hasAny(['cmcMin', 'cmcMax', 'pow', 'tou', 'loy', 'manaCost']),
          rangeField('Mana value', 'cmcMin', 'cmcMax'),
          textField('Mana cost contains', 'manaCost', '{G}{G}'),
          statField('Power', 'powOp', 'pow'),
          statField('Toughness', 'touOp', 'tou'),
          statField('Loyalty', 'loyOp', 'loy')),

        section('Types', hasAny(['supertypes', 'subtypes', 'typesNot', 'typeLine']),
          field('Supertype', chips(['Legendary', 'Basic', 'Snow', 'World'], 'supertypes')),
          field('Subtypes (all must match)', multiText('subtypes', 'Elf, Equipment…', 'dl-subtypes')),
          field('Exclude type', chips(facets?.types || [], 'typesNot')),
          textField('Type line contains', 'typeLine', 'Artifact Creature')),

        section('Text & credits', hasAny(['textLike', 'flavor', 'artist', 'watermark']),
          textField('Exact text (substring)', 'textLike', 'enters tapped'),
          textField('Flavour text', 'flavor', ''),
          textField('Artist', 'artist', 'Rebecca Guay', 'dl-artists'),
          textField('Watermark', 'watermark', '', 'dl-watermarks')),

        section('Keywords & tags', hasAny(['keywords', 'tags']),
          field('Keywords (all must match)', multiText('keywords', 'Flying, Ward…', 'dl-keywords')),
          field('Scryfall tags (all must match)', multiText('tags', 'mana-rock, spot-removal…', 'dl-tags'))),

        section('Printing', hasAny(['sets', 'setTypes', 'layouts', 'frames', 'borders', 'games', 'yearMin', 'yearMax', 'collnum', 'finish']),
          field('Sets', multiText('sets', 'MH3', 'dl-sets')),
          field('Set type', chips(facets?.setTypes || [], 'setTypes')),
          field('Layout', chips(facets?.layouts || [], 'layouts')),
          field('Finish', h('div.seg',
            [['', 'Any'], ['nonfoil', 'Nonfoil'], ['foil', 'Foil'], ['etched', 'Etched']].map(([v, l]) => h('button', {
              class: state.finish === v ? 'on' : '',
              onclick: () => push({ finish: v }),
            }, l)))),
          rangeField('Release year', 'yearMin', 'yearMax', { min: '1993' }),
          field('Frame', chips(facets?.frames || [], 'frames')),
          field('Border', chips(facets?.borders || [], 'borders')),
          field('Available in', chips(['paper', 'arena', 'mtgo'], 'games')),
          textField('Collector number', 'collnum', '117')),

        section('Flags', FLAGS.some(([k]) => state[k] !== ''),
          ...FLAGS.map(([key, label]) => triField(label, key))),

        section('Legality', Boolean(state.format),
          field('Format', h('div.row',
            h('select', { value: state.format, onchange: setter('format') },
              h('option', { value: '' }, 'any format'),
              (facets?.formats || []).map((f) => h('option', { value: f, selected: state.format === f }, f))),
            h('select', { value: state.legality, onchange: setter('legality'), disabled: !state.format },
              ['legal', 'banned', 'restricted', 'not_legal'].map((v) => h('option', { value: v, selected: state.legality === v }, v))))),
          triField('Has rulings', 'hasRulings')),

        section('Collection & decks', hasAny(['qtyMin', 'qtyMax', 'freeMin', 'deck', 'edhrecMin', 'edhrecMax']),
          rangeField('Copies owned', 'qtyMin', 'qtyMax'),
          field('At least this many free', h('input', {
            type: 'number', min: '0', placeholder: 'any', value: state.freeMin, oninput: lazy('freeMin'),
          })),
          field('In a deck', h('select', { value: state.deck, onchange: setter('deck') },
            h('option', { value: '' }, 'any'),
            h('option', { value: '_any', selected: state.deck === '_any' }, 'in any deck'),
            h('option', { value: '_none', selected: state.deck === '_none' }, 'in no deck'),
            (facets?.decks || []).map((d) => h('option', { value: d.slug, selected: state.deck === d.slug }, d.name)))),
          rangeField('EDHREC rank', 'edhrecMin', 'edhrecMax')))),

    h('div.panel',
      h('div.panel-head', h('h2', 'Saved searches')),
      h('div.panel-body', savedSearches())));
}

const hasAny = (keys) => keys.some((k) => {
  const v = state[k];
  return Array.isArray(v) ? v.length > 0 : v !== DEFAULTS[k];
});

/** A list of values built with a datalist input plus removable chips. */
function multiText(key, placeholder, list) {
  const input = h('input', {
    type: 'search', list, placeholder,
    onkeydown: (e) => {
      if (e.key !== 'Enter') return;
      e.preventDefault();
      const v = e.target.value.trim();
      if (!v) return;
      e.target.value = '';
      if (!state[key].includes(v)) push({ [key]: [...state[key], v] });
    },
  });
  return h('div',
    input,
    state[key].length
      ? h('div.chips', { style: { marginTop: '6px' } }, state[key].map((v) => h('span.chip.mini.on', {
        title: 'Remove',
        onclick: () => push({ [key]: state[key].filter((x) => x !== v) }),
      }, v, ' ×')))
      : h('div.small.muted', { style: { marginTop: '4px' } }, 'type and press enter'));
}

function savedSearches() {
  const saved = store.get('saved', []);
  return h('div.stack',
    h('button.btn.sm', {
      onclick: () => {
        const name = prompt('Name this search');
        if (!name) return;
        store.set('saved', [...saved.filter((s) => s.name !== name), { name, hash: toHash(state) }]);
        toast(`Saved "${name}"`, 'ok');
        renderSide();
      },
    }, '＋ Save current'),
    saved.length
      ? h('div.chips', saved.map((s) => h('span.chip', { onclick: () => { location.hash = s.hash; } },
        s.name,
        h('button.btn.ghost.sm', {
          style: { padding: '0 0 0 6px' },
          onclick: (e) => {
            e.stopPropagation();
            store.set('saved', saved.filter((x) => x.name !== s.name));
            renderSide();
          },
        }, '×'))))
      : h('div.muted.small', 'None yet.'));
}

// --------------------------------------------------------------- results

function cardTile(c) {
  const free = c.free ?? 0;
  return h('div.card', {
    onclick: () => openCard(c.id),
    title: `${c.name} · ${(c.setcode || '').toUpperCase()} ${c.collector_number || ''}`,
  },
  h('img.card-img', {
    src: imageUrl(c.scryfall_id, 'normal'),
    alt: c.name, loading: 'lazy', decoding: 'async',
    onerror: (e) => { e.target.removeAttribute('src'); },
  }),
  h('div.qty-badge', `${c.qty}`),
  h(`div.free-badge${free > 0 ? '' : '.none'}`, free > 0 ? `${free} free` : 'in decks'),
  h('div.card-meta',
    h('span.nm', c.name),
    h('span.sb', manaCost(c.mana_cost),
      h('span', { style: { marginLeft: 'auto' } }, (c.setcode || '').toUpperCase()))));
}

function resultsTable(list) {
  const th = (key, label, cls = '') => h(`th.sortable${cls}`, {
    onclick: () => push({ sort: key, dir: state.sort === key && state.dir === 'asc' ? 'desc' : 'asc' }),
  }, label, state.sort === key ? h('span.arrow', state.dir === 'asc' ? ' ↑' : ' ↓') : null);

  return h('div.table-wrap', h('table',
    h('thead', h('tr',
      th('name', 'Name'), h('th', 'Cost'), th('cmc', 'MV', '.num'),
      h('th', 'Type'), th('power', 'P/T', '.num'), th('rarity', 'Rarity'),
      th('set', 'Set'), th('artist', 'Artist'),
      th('qty', 'Qty', '.num'), th('free', 'Free', '.num'), th('edhrec', 'EDHREC', '.num'))),
    h('tbody', list.map((c) => h('tr.clickable', { onclick: () => openCard(c.id) },
      h('td.t-name', c.name, c.printings > 1 ? h('span.muted.small', ` ×${c.printings}`) : null),
      h('td', manaCost(c.mana_cost)),
      h('td.num', c.cmc ?? '—'),
      h('td.small', c.type_line || '—'),
      h('td.num.small', c.power !== null ? `${c.power}/${c.toughness}` : '—'),
      h('td', h('span.tag', c.rarity || '—')),
      h('td.mono', (c.setcode || '').toUpperCase(), ' ', h('span.muted', c.collector_number || '')),
      h('td.small.muted', c.artist || '—'),
      h('td.num', c.qty),
      h('td.num', c.free ?? 0),
      h('td.num.muted', c.edhrec_rank ? num(c.edhrec_rank) : '—'))))));
}

function pager(total) {
  const pages = Math.max(1, Math.ceil(total / state.size));
  if (pages <= 1 && state.page === 1) return null;
  const go = (n) => push({ page: Math.min(pages, Math.max(1, n)) }, { resetPage: false });
  return h('div.pager',
    h('button.btn.sm', { onclick: () => go(1), disabled: state.page <= 1 }, '« First'),
    h('button.btn.sm', { onclick: () => go(state.page - 1), disabled: state.page <= 1 }, '‹ Prev'),
    h('span.info', `Page ${num(state.page)} of ${num(pages)}`),
    h('button.btn.sm', { onclick: () => go(state.page + 1), disabled: state.page >= pages }, 'Next ›'),
    h('button.btn.sm', { onclick: () => go(pages), disabled: state.page >= pages }, 'Last »'));
}

function renderResults(list, total) {
  const head = h('div.panel-head',
    h('h2', `${num(total)} ${total === 1 ? 'card' : 'cards'}`),
    h('span.tag', state.group ? 'by name' : 'every printing'),
    h('span.spacer'),
    h('div.seg',
      h('button', { class: state.view === 'grid' ? 'on' : '', onclick: () => push({ view: 'grid' }, { resetPage: false }) }, 'Grid'),
      h('button', { class: state.view === 'table' ? 'on' : '', onclick: () => push({ view: 'table' }, { resetPage: false }) }, 'Table')),
    h('select', { style: { width: 'auto' }, value: state.sort, onchange: setter('sort') },
      Object.entries(SORTS).map(([k, [label]]) => h('option', { value: k, selected: state.sort === k }, label))),
    h('button.btn.sm', {
      title: 'Reverse order',
      onclick: () => push({ dir: state.dir === 'asc' ? 'desc' : 'asc' }, { resetPage: false }),
    }, state.dir === 'asc' ? '↑' : '↓'),
    h('button.btn.sm', { onclick: () => push({ group: !state.group }) }, state.group ? 'Ungroup' : 'Group'),
    h('select', { style: { width: 'auto' }, value: String(state.size), onchange: (e) => push({ size: Number(e.target.value) }) },
      PAGE_SIZES.map((n) => h('option', { value: n, selected: state.size === n }, `${n} / page`))),
    h('button.btn.sm', { onclick: () => exportCsv(total) }, 'CSV'));

  const body = list.length
    ? (state.view === 'grid' ? h('div.panel-body', h('div.grid', list.map(cardTile))) : resultsTable(list))
    : empty('Nothing matches', 'Loosen a filter, or hit Reset.');

  fill(resultsEl, queryBox(), h('div.panel', head, body), pager(total));
}

async function exportCsv(total) {
  const cap = 5000;
  const { sql, params } = buildQuery({ ...state, page: 1, size: Math.min(total || cap, cap) });
  try {
    const res = await api.query(sql, params, { limit: cap });
    download(`mtg-search-${Date.now()}.csv`, toCsv(res.cols, res.rows), 'text/csv');
    toast(`Exported ${num(res.n)} rows`, 'ok');
  } catch (e) {
    toast(String(e.message), 'bad');
  }
}

// -------------------------------------------------------------- query box

const EXAMPLES = [
  ['Fits a Alela deck', 'id<=wub is:creature'],
  ['Cheap unassigned removal', 'tag:spot-removal mv<=2 is:free'],
  ['Big green beaters', 'id<=g pow>=5 t:creature'],
  ['Spare mana rocks', 'tag:mana-rock is:free -t:land'],
  ['Top-ranked staples', 'edhrec<=250 is:free'],
  ['Foils I own', 'is:foil'],
  ['Not in any deck', '-is:indeck qty>=2'],
  ['Draw spells under 3', 'o:"draw a card" mv<3'],
];

function queryBox() {
  const input = h('input.qbox', {
    type: 'search',
    value: state.adv,
    placeholder: 'id<=wub  t:creature  mv<=3  pow>=4  -is:reprint  o:"draw a card"',
    spellcheck: false,
    oninput: debounce((e) => push({ adv: e.target.value }), 450),
  });

  return h('div.panel', { style: { marginBottom: '14px' } },
    h('div.panel-body', { style: { paddingBottom: advError || state.adv ? '14px' : '14px' } },
      h('div.flex', { style: { gap: '8px' } },
        input,
        h('button.btn.sm', {
          onclick: () => cheatsheet(),
          title: 'Every key the query box understands',
        }, '?')),
      advError ? h('div.err', { style: { marginTop: '8px' } }, advError) : null,
      h('div.chips', { style: { marginTop: '8px' } },
        EXAMPLES.map(([label, q]) => h('button.chip.mini', {
          title: q,
          onclick: () => push({ adv: q }),
        }, label)),
        state.adv ? h('button.chip.mini', { onclick: () => push({ adv: '' }) }, 'clear ×') : null)));
}

// ------------------------------------------------------------------ run

async function run() {
  renderSide();
  advError = null;
  const opts = { advError: (m) => { advError = m; } };
  const { sql, params } = buildQuery(state, opts);
  const countQ = buildQuery(state, { ...opts, countOnly: true });

  const token = Symbol('run');
  lastRun = token;
  fill(resultsEl, queryBox(), loading('Searching'));

  try {
    const [res, total] = await Promise.all([
      api.query(sql, params, { fmt: 'objects', limit: state.size }),
      api.scalar(countQ.sql, countQ.params),
    ]);
    if (lastRun !== token) return;
    renderResults(res.rows, total ?? 0);
  } catch (e) {
    if (lastRun !== token) return;
    fill(resultsEl, queryBox(), errorBox(e));
  }
}

function renderSide() {
  const side = $('#side');
  if (side) fill(side, filterPanel());
}

function mount() {
  const root = $('#view');
  if (root.dataset.view === 'search') return;
  root.dataset.view = 'search';
  resultsEl = h('div');
  fill(root, h('div.wrap',
    h('div.split',
      h('div.sticky-side', { id: 'side' }),
      resultsEl)));
}

/** Datalists and chip vocabularies, fetched once. */
async function loadFacets() {
  if (facets) return;
  try {
    const one = (sql) => api.query(sql).then((r) => r.rows.flat());
    const [types, sets, keywords, tags, subtypes, formats, artists, watermarks,
      setTypes, layouts, frames, borders, decks] = await Promise.all([
      one("SELECT DISTINCT type FROM card_types WHERE kind='type' AND type GLOB '[A-Za-z]*' ORDER BY type"),
      one('SELECT DISTINCT upper(setcode) FROM cards ORDER BY 1'),
      one('SELECT DISTINCT keyword FROM card_keywords ORDER BY 1'),
      one('SELECT tag_slug FROM card_tags GROUP BY 1 ORDER BY COUNT(*) DESC LIMIT 600'),
      one("SELECT DISTINCT type FROM card_types WHERE kind='subtype' ORDER BY 1"),
      one('SELECT DISTINCT format FROM legalities ORDER BY 1'),
      one('SELECT DISTINCT artist FROM cards WHERE artist IS NOT NULL ORDER BY 1'),
      one('SELECT DISTINCT watermark FROM cards WHERE watermark IS NOT NULL ORDER BY 1'),
      one('SELECT set_type FROM cards WHERE set_type IS NOT NULL GROUP BY 1 ORDER BY COUNT(*) DESC'),
      one('SELECT layout FROM cards GROUP BY 1 ORDER BY COUNT(*) DESC'),
      one('SELECT DISTINCT frame FROM cards WHERE frame IS NOT NULL ORDER BY 1'),
      one('SELECT DISTINCT border_color FROM cards WHERE border_color IS NOT NULL ORDER BY 1'),
      api.rows('SELECT slug, name, owner FROM decks ORDER BY owner, name'),
    ]);
    facets = { types, formats, setTypes, layouts, frames, borders, decks };

    const dl = (id, values) => h('datalist', { id }, values.map((v) => h('option', { value: v })));
    document.body.append(
      dl('dl-sets', sets), dl('dl-keywords', keywords), dl('dl-tags', tags),
      dl('dl-subtypes', subtypes), dl('dl-artists', artists), dl('dl-watermarks', watermarks),
    );
  } catch {
    facets = {
      types: ['Creature', 'Instant', 'Sorcery', 'Artifact', 'Enchantment', 'Land', 'Planeswalker', 'Battle'],
      formats: ['commander'], setTypes: [], layouts: [], frames: [], borders: [], decks: [],
    };
  }
}

export { fromHash };

export async function show(queryString) {
  state = fromHash(queryString);
  mount();
  renderSide();
  await loadFacets();
  renderSide();
  run();
}
