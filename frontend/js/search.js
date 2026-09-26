// Search. The filter model and SQL live in filters.js; this is the UI.
//
// The filter surface is full width, collapsed by default, and every group
// inside it collapses on its own. Nothing here scrolls inside anything
// else — the page has one scrollbar and collapsing is what keeps the
// panel out of the way.

import * as api from './api.js';
import {
  h, $, fill, num, debounce, imageUrl, manaCost, COLORS,
  RARITY_ORDER, download, toast, store, loading, errorBox, empty,
} from './util.js';
import { openCard } from './card.js';
import {
  DEFAULTS, PAGE_SIZE, SORTS, COLOR_MODES, FLAGS, buildQuery, toHash, fromHash, activeCount,
} from './filters.js';
import { cheatsheet } from './cheatsheet.js';
import { money, exact, priceOrReason, priceReason } from './prices.js';


let state = { ...DEFAULTS };
let lastRun = null;
let facets = null;
let advError = null;
let panelOpen = store.get('filtersOpen', false);
let openGroups = new Set(store.get('openGroups', []));
let panelEl;
let resultsEl;

// -------------------------------------------------------- state plumbing

/**
 * Re-rendering the whole panel on every keystroke would yank focus out of
 * whatever you were typing in. Every control carries a stable key, so
 * focus and cursor position can be put back afterwards.
 */
function captureFocus() {
  const el = document.activeElement;
  if (!el || !el.dataset || !el.dataset.fk) return null;
  return {
    fk: el.dataset.fk,
    start: el.selectionStart ?? null,
    end: el.selectionEnd ?? null,
  };
}

function restoreFocus(snap) {
  if (!snap) return;
  const el = panelEl?.querySelector(`[data-fk="${CSS.escape(snap.fk)}"]`);
  if (!el) return;
  el.focus();
  if (snap.start !== null && el.setSelectionRange) {
    try { el.setSelectionRange(snap.start, snap.end); } catch { /* not a text input */ }
  }
}

function push(patch, { resetPage = true } = {}) {
  state = { ...state, ...patch };
  if (resetPage && !('page' in patch)) state.page = 1;
  const hash = toHash(state);
  if (hash !== location.hash) history.replaceState(null, '', hash);
  run();
}

const toggleIn = (key, value) => push({
  [key]: state[key].includes(value) ? state[key].filter((x) => x !== value) : [...state[key], value],
});

// ------------------------------------------------------------- controls

const lazy = (k) => debounce((e) => push({ [k]: e.target.value }), 400);
const setter = (k) => (e) => push({ [k]: e.target.value });

function label(text) {
  return text ? h('label', text) : null;
}

/** A checkbox list. This is the workhorse of the panel. */
function checks(values, key, { cols = 2 } = {}) {
  return h('div.checks', { style: { '--cols': cols } }, values.map((v) => {
    const [val, text] = Array.isArray(v) ? v : [v, v];
    return h('label.check',
      h('input', {
        type: 'checkbox',
        checked: state[key].includes(val),
        dataset: { fk: `${key}:${val}` },
        onchange: () => toggleIn(key, val),
      }),
      h('span', text));
  }));
}

/** Mutually exclusive buttons. */
function seg(key, options, { onPick } = {}) {
  return h('div.seg', options.map(([v, text, title]) => h('button', {
    class: state[key] === v ? 'on' : '',
    title: title || '',
    onclick: () => (onPick ? onPick(v) : push({ [key]: v })),
  }, text)));
}

function text(key, placeholder, list) {
  return h('input', {
    type: 'search', value: state[key], placeholder, list,
    dataset: { fk: key },
    oninput: lazy(key),
  });
}

function range(minKey, maxKey, { min = '0', step = '1' } = {}) {
  return h('div.row',
    h('input', { type: 'number', min, step, placeholder: 'min', value: state[minKey], dataset: { fk: minKey }, oninput: lazy(minKey) }),
    h('input', { type: 'number', min, step, placeholder: 'max', value: state[maxKey], dataset: { fk: maxKey }, oninput: lazy(maxKey) }));
}

function stat(opKey, valKey) {
  return h('div.row',
    h('select', { style: { flex: '0 0 68px' }, value: state[opKey], dataset: { fk: opKey }, onchange: setter(opKey) },
      ['>=', '<=', '=', '>', '<'].map((o) => h('option', { value: o, selected: state[opKey] === o }, o))),
    h('input', { type: 'number', placeholder: 'any', value: state[valKey], dataset: { fk: valKey }, oninput: lazy(valKey) }));
}

/** Any / yes / no, for the boolean printing flags. */
function tri(key, text2) {
  return h('div.tri',
    h('span.tri-label', text2),
    h('div.seg.seg-sm', [['', 'Any'], ['yes', 'Yes'], ['no', 'No']].map(([v, l]) => h('button', {
      class: state[key] === v ? 'on' : '',
      onclick: () => push({ [key]: v }),
    }, l))));
}

/** A tag-style list you add to with enter, remove by clicking. */
function tokens(key, placeholder, list) {
  return h('div',
    h('input', {
      type: 'search', list, placeholder,
      dataset: { fk: key },
      onkeydown: (e) => {
        if (e.key !== 'Enter') return;
        e.preventDefault();
        const v = e.target.value.trim();
        if (!v) return;
        e.target.value = '';
        if (!state[key].includes(v)) push({ [key]: [...state[key], v] });
      },
    }),
    state[key].length
      ? h('div.chips', { style: { marginTop: '6px' } }, state[key].map((v) => h('span.chip.mini.on', {
        title: 'Remove', onclick: () => push({ [key]: state[key].filter((x) => x !== v) }),
      }, v, ' ×')))
      : h('div.hint', 'type and press enter'));
}

function row(text2, control) {
  return h('div.frow', label(text2), control);
}

// ---------------------------------------------------------------- panel

function colorBody() {
  const hint = (COLOR_MODES.find(([v]) => v === state.colorMode) || [])[2] || '';
  return [
    row('Match against', seg('colorTarget', [
      ['id', 'Colour identity', 'What a commander allows — the usual one'],
      ['card', 'Printed colour', 'The colours printed on the card'],
    ])),
    row('How', h('div',
      h('div.mode-grid', COLOR_MODES.map(([v, text2, title]) => h('button.chip', {
        class: state.colorMode === v ? 'on' : '', title,
        onclick: () => push({ colorMode: v }),
      }, text2))),
      h('div.hint', hint))),
    row('Colours', h('div',
      h('div.pips', COLORS.map(([c, name]) => h('button.pip', {
        dataset: { c }, class: state.colors.includes(c) ? 'on' : '', title: name,
        onclick: () => toggleIn('colors', c),
      }, c))),
      h('div.chips', { style: { marginTop: '7px' } },
        h('button.chip.mini', { onclick: () => push({ colors: [] }) }, 'clear'),
        h('button.chip.mini', { onclick: () => push({ colors: ['W', 'U', 'B', 'R', 'G'] }) }, 'all five'),
        h('button.chip.mini', { onclick: () => push({ colors: ['C'], colorMode: 'exactly' }) }, 'colourless')))),
    row('Number of colours', range('ciMin', 'ciMax')),
    row('Produces mana', h('div.pips', COLORS.map(([c, name]) => h('button.pip', {
      dataset: { c }, class: state.produces.includes(c) ? 'on' : '', title: `Taps for ${name}`,
      onclick: () => toggleIn('produces', c),
    }, c)))),
  ];
}

/**
 * The groups, as data. `keys` is what counts as "this group is in use",
 * which drives both the badge on the header and whether a link that
 * arrives with filters set opens the right groups.
 */
const GROUPS = [
  {
    id: 'collection',
    title: 'Collection',
    keys: ['pool', 'qtyMin', 'qtyMax', 'freeMin', 'deck', 'edhrecMin', 'edhrecMax',
      'priceMin', 'priceMax'],
    body: () => [
      row('Whose', seg('owner', [['matt', 'Matt'], ['kayla', 'Kayla'], ['both', 'Both']])),
      row('Pool', seg('pool', [
        ['all', 'All'],
        ['free', 'Unassigned', 'Copies not committed to a built deck'],
        ['committed', 'In decks'],
      ])),
      row('Copies owned', range('qtyMin', 'qtyMax')),
      row('Free copies, at least', h('input', {
        type: 'number', min: '0', placeholder: 'any', value: state.freeMin,
        dataset: { fk: 'freeMin' }, oninput: lazy('freeMin'),
      })),
      row('In a deck', h('select', { value: state.deck, dataset: { fk: 'deck' }, onchange: setter('deck') },
        h('option', { value: '' }, 'any'),
        h('option', { value: '_any', selected: state.deck === '_any' }, '— in any deck —'),
        h('option', { value: '_none', selected: state.deck === '_none' }, '— in no deck —'),
        (facets?.decks || []).map((d) => h('option', { value: d.slug, selected: state.deck === d.slug }, `${d.name} (${d.owner})`)))),
      row('EDHREC rank', range('edhrecMin', 'edhrecMax')),
      row('Price, USD', range('priceMin', 'priceMax', { step: '0.01' })),
    ],
  },
  {
    id: 'colour',
    title: 'Colour',
    keys: ['colors', 'produces', 'ciMin', 'ciMax'],
    body: colorBody,
  },
  {
    id: 'type',
    title: 'Card type',
    keys: ['types', 'typesNot', 'supertypes', 'subtypes', 'typeLine'],
    body: () => [
      row(null, checks(facets?.types || [], 'types')),
      row('Supertype', checks(['Legendary', 'Basic', 'Snow', 'World'], 'supertypes')),
      row('Subtypes', tokens('subtypes', 'Elf, Equipment…', 'dl-subtypes')),
      row('Exclude type', checks(facets?.types || [], 'typesNot')),
      row('Type line contains', text('typeLine', 'Artifact Creature')),
    ],
  },
  {
    id: 'mana',
    title: 'Mana & stats',
    keys: ['cmcMin', 'cmcMax', 'manaCost', 'pow', 'tou', 'loy'],
    body: () => [
      row('Mana value', range('cmcMin', 'cmcMax')),
      row('Mana cost contains', text('manaCost', '{G}{G}')),
      row('Power', stat('powOp', 'pow')),
      row('Toughness', stat('touOp', 'tou')),
      row('Loyalty', stat('loyOp', 'loy')),
    ],
  },
  {
    id: 'text',
    title: 'Text',
    keys: ['q', 'text', 'textLike', 'flavor', 'artist', 'watermark'],
    body: () => [
      row('Name contains', text('q', 'sol ring')),
      row('Rules text', h('div', text('text', 'draw a card'), h('div.hint', 'full-text, stemmed'))),
      row('Exact text', h('div', text('textLike', 'enters tapped'), h('div.hint', 'literal substring'))),
      row('Flavour text', text('flavor', '')),
      row('Artist', text('artist', 'Rebecca Guay', 'dl-artists')),
      row('Watermark', text('watermark', '', 'dl-watermarks')),
    ],
  },
  {
    id: 'tags',
    title: 'Keywords & tags',
    keys: ['keywords', 'tags'],
    body: () => [
      row('Keywords', tokens('keywords', 'Flying, Ward…', 'dl-keywords')),
      row('Scryfall tags', tokens('tags', 'mana-rock, spot-removal…', 'dl-tags')),
      h('div.hint', 'every one listed must match'),
    ],
  },
  {
    id: 'printing',
    title: 'Rarity & printing',
    keys: ['rarities', 'finish', 'sets', 'setTypes', 'yearMin', 'yearMax', 'collnum'],
    body: () => [
      row('Rarity', checks(RARITY_ORDER, 'rarities')),
      row('Finish', seg('finish', [['', 'Any'], ['nonfoil', 'Nonfoil'], ['foil', 'Foil'], ['etched', 'Etched']])),
      row('Sets', tokens('sets', 'MH3', 'dl-sets')),
      row('Set type', checks(facets?.setTypes || [], 'setTypes')),
      row('Release year', range('yearMin', 'yearMax', { min: '1993' })),
      row('Collector number', text('collnum', '117')),
    ],
  },
  {
    id: 'physical',
    title: 'Physical & digital',
    keys: ['layouts', 'frames', 'borders', 'games'],
    body: () => [
      row('Layout', checks(facets?.layouts || [], 'layouts')),
      row('Frame', checks(facets?.frames || [], 'frames', { cols: 3 })),
      row('Border', checks(facets?.borders || [], 'borders')),
      row('Available in', checks(['paper', 'arena', 'mtgo'], 'games', { cols: 3 })),
    ],
  },
  {
    id: 'flags',
    title: 'Flags',
    keys: FLAGS.map(([k]) => k),
    body: () => [h('div.tri-list', FLAGS.map(([key, text2]) => tri(key, text2)))],
  },
  {
    id: 'legality',
    title: 'Legality',
    keys: ['format', 'hasRulings'],
    body: () => [
      row('Format', h('select', { value: state.format, dataset: { fk: 'format' }, onchange: setter('format') },
        h('option', { value: '' }, 'any format'),
        (facets?.formats || []).map((f) => h('option', { value: f, selected: state.format === f }, f)))),
      row('Status', h('select', { value: state.legality, dataset: { fk: 'legality' }, onchange: setter('legality'), disabled: !state.format },
        ['legal', 'banned', 'restricted', 'not_legal'].map((v) => h('option', { value: v, selected: state.legality === v }, v)))),
      h('div.tri-list', tri('hasRulings', 'Has rulings')),
    ],
  },
  {
    id: 'query',
    title: 'Query language',
    keys: ['adv'],
    body: () => [
      h('input.qbox', {
        type: 'search', value: state.adv, spellcheck: false,
        dataset: { fk: 'adv' },
        placeholder: 'optional — id<=wub t:creature mv<=3 -is:reprint',
        oninput: lazy('adv'),
      }),
      advError ? h('div.err', { style: { marginTop: '8px' } }, advError) : null,
      h('div.flex', { style: { marginTop: '8px' } },
        h('span.hint', { style: { flex: '1', marginTop: '0' } }, 'Optional. ANDed with everything above.'),
        h('button.btn.sm.ghost', { onclick: () => cheatsheet() }, 'cheatsheet')),
    ],
  },
];

/** How many filters in this group are set. */
function groupCount(g) {
  return g.keys.filter((k) => {
    const v = state[k]; const d = DEFAULTS[k];
    return Array.isArray(v) ? v.length > 0 : (v !== d && v !== '');
  }).length;
}

function filterPanel() {
  return h('div.fgrid', GROUPS.map((g) => {
    const n = groupCount(g);
    return h('details.fgroup', {
      class: g.id === 'colour' ? 'fgroup-color' : '',
      open: openGroups.has(g.id),
      ontoggle: (e) => {
        if (e.target.open) openGroups.add(g.id);
        else openGroups.delete(g.id);
        store.set('openGroups', [...openGroups]);
      },
    },
    h('summary',
      h('span.fg-title', g.title),
      n ? h('span.count-pill', n) : null),
    h('div.fgroup-body', ...g.body()));
  }));
}

// ------------------------------------------------- active filter summary

const LABELS = {
  // Owner used to live in the toolbar where it was always on screen. It is
  // a filter like any other now, so it has to earn a chip when it is not
  // the default, or you cannot tell whose collection you are looking at.
  owner: 'whose',
  q: 'name', text: 'rules text', textLike: 'exact text', flavor: 'flavour',
  artist: 'artist', watermark: 'watermark', typeLine: 'type line',
  manaCost: 'mana cost', collnum: 'number', adv: 'query',
  cmcMin: 'mv ≥', cmcMax: 'mv ≤', ciMin: 'colours ≥', ciMax: 'colours ≤',
  qtyMin: 'owned ≥', qtyMax: 'owned ≤', freeMin: 'free ≥',
  edhrecMin: 'edhrec ≥', edhrecMax: 'edhrec ≤',
  yearMin: 'year ≥', yearMax: 'year ≤',
  types: 'type', typesNot: 'not type', supertypes: 'supertype', subtypes: 'subtype',
  rarities: 'rarity', sets: 'set', setTypes: 'set type', layouts: 'layout',
  frames: 'frame', borders: 'border', games: 'in', keywords: 'keyword',
  tags: 'tag', produces: 'produces', colors: 'colour',
  finish: 'finish', pool: 'pool', deck: 'deck', format: 'format',
  hasRulings: 'rulings',
  priceMin: 'price ≥', priceMax: 'price ≤',
};

function activeChips() {
  const out = [];
  const drop = (patch) => () => push(patch);

  for (const [key, text2] of Object.entries(LABELS)) {
    const v = state[key];
    const d = DEFAULTS[key];
    if (Array.isArray(v)) {
      if (!v.length) continue;
      const shown = key === 'colors'
        ? `${(COLOR_MODES.find(([m]) => m === state.colorMode) || [])[1]?.toLowerCase()} ${v.join('')}`
        : v.join(', ');
      out.push([`${text2}: ${shown}`, drop({ [key]: [] })]);
    } else if (v !== d && v !== '') {
      out.push([`${text2}: ${v}`, drop({ [key]: d })]);
    }
  }
  for (const [key, text2] of FLAGS) {
    if (state[key]) out.push([`${text2}: ${state[key]}`, drop({ [key]: '' })]);
  }

  if (!out.length) return null;
  return h('div.active-bar',
    h('span.small.muted', 'Active:'),
    h('div.chips', out.map(([text2, onDrop]) => h('button.chip.on.mini', {
      title: 'Remove this filter', onclick: onDrop,
    }, text2, ' ×'))),
    h('button.btn.sm.ghost', {
      onclick: () => push({ ...DEFAULTS }),
    }, 'Clear all'));
}

// --------------------------------------------------------------- results

function cardTile(c) {
  const free = c.free ?? 0;
  return h('div.card', {
    onclick: () => openCard(c.id),
    title: `${c.name} · ${(c.setcode || '').toUpperCase()} ${c.collector_number || ''}`,
  },
  // The badges are positioned against the art, not the tile, so "bottom"
  // means the bottom of the card image rather than under the caption.
  h('div.card-art',
    h('img.card-img', {
      src: imageUrl(c.scryfall_id, 'normal'),
      alt: c.name, loading: 'lazy', decoding: 'async',
      onerror: (e) => { e.target.removeAttribute('src'); },
    }),
    h(`div.free-badge${free > 0 ? '' : '.none'}`, free > 0 ? `${free} free` : 'in decks'),
    h('div.price-badge', { title: c.price === null ? priceReason(c) : '' },
      money(c.price, { dash: '' }))),
  h('div.card-meta',
    h('span.nm', c.name),
    h('span.sb',
      manaCost(c.mana_cost),
      h('span.qty', { title: `${c.qty} owned` }, `×${c.qty}`),
      h('span.set', (c.setcode || '').toUpperCase()))));
}

function pager(total) {
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  if (pages <= 1 && state.page === 1) return null;
  const go = (n) => push({ page: Math.min(pages, Math.max(1, n)) }, { resetPage: false });
  return h('div.pager',
    h('button.btn.sm', { onclick: () => go(1), disabled: state.page <= 1 }, '« First'),
    h('button.btn.sm', { onclick: () => go(state.page - 1), disabled: state.page <= 1 }, '‹ Prev'),
    h('span.info', `Page ${num(state.page)} of ${num(pages)}`),
    h('button.btn.sm', { onclick: () => go(state.page + 1), disabled: state.page >= pages }, 'Next ›'),
    h('button.btn.sm', { onclick: () => go(pages), disabled: state.page >= pages }, 'Last »'));
}

function resultsHead(total) {
  return h('div.panel-head.results-head',
    h('h2', `${num(total)} ${total === 1 ? 'card' : 'cards'}`),
    h('span.spacer'),
    h('select', { style: { width: 'auto' }, value: state.sort, onchange: setter('sort') },
      Object.entries(SORTS).map(([k, [text2]]) => h('option', { value: k, selected: state.sort === k }, text2))),
    h('button.btn.sm', {
      title: 'Reverse order',
      onclick: () => push({ dir: state.dir === 'asc' ? 'desc' : 'asc' }, { resetPage: false }),
    }, state.dir === 'asc' ? '↑' : '↓'),
    exportMenu(total));
}

// ---------------------------------------------------------------- export

const EXPORT_CAP = 5000;

/**
 * The whole filtered set as a decklist, not just the page you can see.
 *
 * No set code or collector number: a row here is a card, summed over every
 * printing of it you own, so pinning it to one printing would be a lie.
 * "3 Sol Ring" is what every deckbuilder reads anyway.
 */
async function decklist() {
  const { sql, params } = buildQuery({ ...state, page: 1, size: EXPORT_CAP });
  const res = await api.query(sql, params, { fmt: 'objects', limit: EXPORT_CAP });
  const lines = res.rows.map((r) => `${r.qty} ${r.name}`);
  return { text: lines.join('\n'), n: lines.length };
}

async function exportFile() {
  try {
    const { text, n } = await decklist();
    download(`mtg-decklist-${new Date().toISOString().slice(0, 10)}.txt`, text);
    toast(`Saved ${num(n)} card${n === 1 ? '' : 's'}`, 'ok');
  } catch (e) {
    toast(String(e.message), 'bad');
  }
}

async function exportClipboard() {
  try {
    const { text, n } = await decklist();
    await navigator.clipboard.writeText(text);
    toast(`Copied ${num(n)} card${n === 1 ? '' : 's'}`, 'ok');
  } catch (e) {
    // Clipboard writes need a secure context and a real user gesture; say
    // so rather than failing silently.
    toast(e?.name === 'NotAllowedError' ? 'Clipboard blocked by the browser' : String(e.message), 'bad');
  }
}

function exportMenu(total) {
  const menu = h('div.menu', { hidden: true },
    h('button', { onclick: () => { menu.hidden = true; exportFile(); } }, 'Save as .txt'),
    h('button', { onclick: () => { menu.hidden = true; exportClipboard(); } }, 'Copy to clipboard'));

  const wrap = h('div.menu-wrap',
    h('button.btn.sm', {
      disabled: !total,
      title: `Export all ${num(total)} as a decklist`,
      onclick: (e) => {
        e.stopPropagation();
        menu.hidden = !menu.hidden;
        if (!menu.hidden) {
          const close = () => { menu.hidden = true; document.removeEventListener('click', close); };
          document.addEventListener('click', close);
        }
      },
    }, 'Export ▾'),
    menu);

  return wrap;
}

// ------------------------------------------------------------------ run

function renderResults(rows, total) {
  const body = rows.length
    ? h('div.panel-body', h('div.grid', rows.map(cardTile)))
    : empty('Nothing matches', 'Loosen a filter, or Clear all.');
  fill(resultsEl, h('div.panel', resultsHead(total), body), pager(total));
}

async function run() {
  renderChrome();
  advError = null;
  const opts = { advError: (m) => { advError = m; } };
  const { sql, params } = buildQuery(state, opts);
  const countQ = buildQuery(state, { ...opts, countOnly: true });

  const token = Symbol('run');
  lastRun = token;
  fill(resultsEl, loading('Searching'));

  try {
    const [res, total] = await Promise.all([
      api.query(sql, params, { fmt: 'objects', limit: PAGE_SIZE }),
      api.scalar(countQ.sql, countQ.params),
    ]);
    if (lastRun !== token) return;
    renderResults(res.rows, total ?? 0);
    if (advError) renderChrome();
  } catch (e) {
    if (lastRun !== token) return;
    fill(resultsEl, errorBox(e));
  }
}

/** Everything above the results: the toolbar, the chips, the panel. */
function renderChrome() {
  const snap = captureFocus();
  const n = activeCount(state);

  fill($('#chrome'),
    h('div.searchbar',
      h('button.btn', {
        class: panelOpen ? 'open' : '',
        onclick: () => {
          panelOpen = !panelOpen;
          store.set('filtersOpen', panelOpen);
          renderChrome();
        },
      }, panelOpen ? '▾ Filters' : '▸ Filters', n ? h('span.count-pill', n) : null),
      h('input.bigsearch', {
        type: 'search', value: state.q, placeholder: 'Search by name…',
        dataset: { fk: 'q-top' },
        oninput: debounce((e) => push({ q: e.target.value }), 400),
      })),
    activeChips(),
    panelOpen ? h('div.filters-wrap',
      h('div.fpanel-bar',
        h('span.small.muted', 'Sections'),
        h('span.spacer'),
        h('button.btn.sm.ghost', {
          onclick: () => {
            openGroups = new Set(GROUPS.map((g) => g.id));
            store.set('openGroups', [...openGroups]);
            renderChrome();
          },
        }, 'Expand all'),
        h('button.btn.sm.ghost', {
          onclick: () => {
            openGroups = new Set();
            store.set('openGroups', []);
            renderChrome();
          },
        }, 'Collapse all')),
      h('div.panel', filterPanel())) : null);

  panelEl = $('#chrome');
  restoreFocus(snap);
}

function mount() {
  const root = $('#view');
  if (root.dataset.view === 'search') return;
  root.dataset.view = 'search';
  resultsEl = h('div');
  fill(root, h('div.wrap', h('div', { id: 'chrome' }), resultsEl));
}

/** Datalists and checkbox vocabularies, fetched once. */
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
  // Arriving on a link or a saved search with filters already set: open
  // the panel and the groups responsible, so the state is visible rather
  // than hidden behind a collapsed header.
  const busy = GROUPS.filter((g) => groupCount(g) > 0).map((g) => g.id);
  if (busy.length) {
    panelOpen = true;
    for (const id of busy) openGroups.add(id);
  }
  mount();
  renderChrome();
  await loadFacets();
  renderChrome();
  run();
}
