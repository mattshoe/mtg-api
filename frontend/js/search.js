// Search — filter panel plus results, and the SQL builder behind it.

import * as api from './api.js';
import {
  h, $, fill, num, debounce, imageUrl, manaCost, identity, COLORS,
  RARITY_ORDER, download, toCsv, toast, store, loading, errorBox, empty,
} from './util.js';
import { openCard } from './card.js';

const PAGE_SIZES = [24, 48, 96, 200];

export const DEFAULTS = {
  q: '',            // name contains
  text: '',         // oracle text, full-text
  owner: 'matt',
  colors: [],       // W U B R G C
  colorMode: 'id-lte',
  cmcMin: '', cmcMax: '',
  types: [],
  subtype: '',
  rarities: [],
  set: '',
  keyword: '',
  tag: '',
  format: '', legality: 'legal',
  finish: '',
  pool: 'all',      // all | free | committed
  pow: '', tou: '',
  group: true,
  view: 'grid',
  sort: 'name',
  dir: 'asc',
  page: 1,
  size: 48,
};

const SORTS = {
  name: ['Name', 'name_norm'],
  cmc: ['Mana value', 'cmc'],
  qty: ['Quantity', 'qty'],
  free: ['Free copies', 'free'],
  edhrec: ['EDHREC rank', 'edhrec_rank'],
  released: ['Released', 'released_at'],
  rarity: ['Rarity', "instr('common uncommon rare mythic special bonus', rarity)"],
  set: ['Set', 'setcode'],
};

// Colour filter semantics, phrased the way a deckbuilder thinks.
const COLOR_MODES = {
  'id-lte': ['Fits in identity', 'color_identity has nothing outside the chosen colours'],
  'id-gte': ['Identity includes', 'color_identity contains all chosen colours'],
  'id-eq': ['Identity is exactly', 'color_identity is exactly the chosen colours'],
  'c-gte': ['Card colour includes', 'the card itself is at least these colours'],
  'c-eq': ['Card colour is exactly', 'the card itself is exactly these colours'],
};

let state = { ...DEFAULTS };
let lastRun = null;
let facets = null;

// ------------------------------------------------------------ SQL builder

/**
 * Turn the filter state into SQL. Everything user-supplied goes in as a
 * bound parameter; only identifiers this file controls are interpolated.
 */
export function buildQuery(s, { countOnly = false } = {}) {
  const where = [];
  const p = [];

  if (s.owner && s.owner !== 'both') { where.push('c.owner = ?'); p.push(s.owner); }

  if (s.q.trim()) {
    // Match either face too, so "Kiki-Jiki" finds the card it is the back of.
    where.push('(c.name_norm LIKE ? OR lower(c.face1) LIKE ? OR lower(c.face2) LIKE ?)');
    const like = `%${s.q.trim().toLowerCase()}%`;
    p.push(like, like, like);
  }

  if (s.text.trim()) {
    where.push('c.id IN (SELECT rowid FROM card_search WHERE card_search MATCH ?)');
    p.push(s.text.trim());
  }

  if (s.colors.length) {
    const col = s.colorMode.startsWith('id') ? 'color_identity' : 'colors';
    const chosen = s.colors.filter((x) => x !== 'C');
    const wantsColorless = s.colors.includes('C');

    if (s.colorMode.endsWith('-eq')) {
      // Exact set: every letter present, none else. Compare sorted strings.
      const want = chosen.slice().sort().join('');
      where.push(`COALESCE(c.${col}, '') = ?`);
      p.push(want);
    } else if (s.colorMode.endsWith('-gte')) {
      for (const cc of chosen) { where.push(`c.${col} LIKE ?`); p.push(`%${cc}%`); }
      if (wantsColorless && !chosen.length) where.push(`COALESCE(c.${col}, '') = ''`);
    } else {
      // "fits in": nothing outside the chosen colours.
      const outside = ['W', 'U', 'B', 'R', 'G'].filter((x) => !chosen.includes(x));
      for (const cc of outside) { where.push(`COALESCE(c.${col}, '') NOT LIKE ?`); p.push(`%${cc}%`); }
    }
  }

  if (s.cmcMin !== '') { where.push('c.cmc >= ?'); p.push(Number(s.cmcMin)); }
  if (s.cmcMax !== '') { where.push('c.cmc <= ?'); p.push(Number(s.cmcMax)); }

  for (const t of s.types) {
    where.push("EXISTS (SELECT 1 FROM card_types ct WHERE ct.card_id = c.id AND ct.kind = 'type' AND ct.type = ?)");
    p.push(t);
  }
  if (s.subtype.trim()) {
    where.push("EXISTS (SELECT 1 FROM card_types ct WHERE ct.card_id = c.id AND ct.kind = 'subtype' AND lower(ct.type) = ?)");
    p.push(s.subtype.trim().toLowerCase());
  }

  if (s.rarities.length) {
    where.push(`c.rarity IN (${s.rarities.map(() => '?').join(',')})`);
    p.push(...s.rarities);
  }

  if (s.set.trim()) { where.push('lower(c.setcode) = ?'); p.push(s.set.trim().toLowerCase()); }
  if (s.finish) { where.push('c.finish = ?'); p.push(s.finish); }

  if (s.keyword.trim()) {
    where.push('EXISTS (SELECT 1 FROM card_keywords k WHERE k.card_id = c.id AND lower(k.keyword) = ?)');
    p.push(s.keyword.trim().toLowerCase());
  }
  if (s.tag.trim()) {
    where.push('EXISTS (SELECT 1 FROM card_tags ct WHERE ct.card_id = c.id AND ct.tag_slug = ?)');
    p.push(s.tag.trim());
  }

  if (s.format) {
    if (s.legality === 'not_legal') {
      where.push('NOT EXISTS (SELECT 1 FROM legalities l WHERE l.oracle_id = c.oracle_id AND l.format = ?)');
      p.push(s.format);
    } else {
      where.push('EXISTS (SELECT 1 FROM legalities l WHERE l.oracle_id = c.oracle_id AND l.format = ? AND l.status = ?)');
      p.push(s.format, s.legality);
    }
  }

  if (s.pow !== '') { where.push('CAST(c.power AS INTEGER) >= ? AND c.power GLOB \'[0-9]*\''); p.push(Number(s.pow)); }
  if (s.tou !== '') { where.push('CAST(c.toughness AS INTEGER) >= ? AND c.toughness GLOB \'[0-9]*\''); p.push(Number(s.tou)); }

  // `free` needs card_usage, which is keyed by (owner, name_norm).
  const usage = `(SELECT free FROM card_usage u WHERE u.owner = c.owner AND u.name_norm = c.name_norm)`;
  if (s.pool === 'free') where.push(`COALESCE(${usage}, 0) > 0`);
  if (s.pool === 'committed') where.push(`COALESCE(${usage}, 0) <= 0`);

  const clause = where.length ? `WHERE ${where.join('\n  AND ')}` : '';

  if (countOnly) {
    const inner = s.group
      ? `SELECT 1 FROM cards c ${clause} GROUP BY c.owner, c.name_norm`
      : `SELECT 1 FROM cards c ${clause}`;
    return { sql: `SELECT COUNT(*) FROM (${inner})`, params: p };
  }

  const [, sortCol] = SORTS[s.sort] || SORTS.name;
  const dir = s.dir === 'desc' ? 'DESC' : 'ASC';
  // NULLs last whichever way we are sorting, so "no EDHREC rank" never wins.
  const order = `ORDER BY (${sortCol}) IS NULL, (${sortCol}) ${dir}, c.name_norm ASC`;

  const offset = (s.page - 1) * s.size;

  // MIN(c.id) makes SQLite take every other bare column from that same row,
  // which is exactly the representative printing we want to show.
  const select = s.group
    ? `SELECT MIN(c.id) AS id, c.owner, c.name, c.name_norm, c.face2, c.layout,
              c.scryfall_id, c.mana_cost, c.cmc, c.type_line, c.oracle_text,
              c.color_identity, c.rarity, c.setcode, c.collector_number,
              c.edhrec_rank, c.released_at, c.finish, c.power, c.toughness,
              SUM(c.qty) AS qty, COUNT(*) AS printings,
              ${usage} AS free`
    : `SELECT c.id, c.owner, c.name, c.name_norm, c.face2, c.layout,
              c.scryfall_id, c.mana_cost, c.cmc, c.type_line, c.oracle_text,
              c.color_identity, c.rarity, c.setcode, c.collector_number,
              c.edhrec_rank, c.released_at, c.finish, c.power, c.toughness,
              c.qty AS qty, 1 AS printings,
              ${usage} AS free`;

  const group = s.group ? 'GROUP BY c.owner, c.name_norm' : '';

  return {
    sql: `${select}\nFROM cards c\n${clause}\n${group}\n${order}\nLIMIT ${s.size} OFFSET ${offset}`,
    params: p,
  };
}

// --------------------------------------------------------------- URL sync

function toHash(s) {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(s)) {
    const d = DEFAULTS[k];
    const same = Array.isArray(v) ? v.join(',') === d.join(',') : v === d;
    if (same) continue;
    q.set(k, Array.isArray(v) ? v.join(',') : String(v));
  }
  const str = q.toString();
  return `#/search${str ? `?${str}` : ''}`;
}

export function fromHash(queryString) {
  const s = { ...DEFAULTS };
  const q = new URLSearchParams(queryString || '');
  for (const [k, v] of q) {
    if (!(k in DEFAULTS)) continue;
    if (Array.isArray(DEFAULTS[k])) s[k] = v ? v.split(',').filter(Boolean) : [];
    else if (typeof DEFAULTS[k] === 'number') s[k] = Number(v) || DEFAULTS[k];
    else if (typeof DEFAULTS[k] === 'boolean') s[k] = v === 'true';
    else s[k] = v;
  }
  return s;
}

function push(patch, { resetPage = true } = {}) {
  state = { ...state, ...patch };
  if (resetPage && !('page' in patch)) state.page = 1;
  const hash = toHash(state);
  if (hash !== location.hash) history.replaceState(null, '', hash);
  run();
}

// --------------------------------------------------------------- rendering

function cardTile(c) {
  const free = c.free ?? 0;
  return h('div.card', {
    onclick: () => openCard(c.id),
    title: `${c.name} · ${c.setcode?.toUpperCase() || ''} ${c.collector_number || ''}`,
  },
  h('img.card-img', {
    src: imageUrl(c.scryfall_id, 'normal'),
    alt: c.name,
    loading: 'lazy',
    decoding: 'async',
    onerror: (e) => { e.target.removeAttribute('src'); },
  }),
  h('div.qty-badge', `${c.qty}`),
  h(`div.free-badge${free > 0 ? '' : '.none'}`, free > 0 ? `${free} free` : 'in decks'),
  h('div.card-meta',
    h('span.nm', c.name),
    h('span.sb',
      manaCost(c.mana_cost),
      h('span', { style: { marginLeft: 'auto' } }, (c.setcode || '').toUpperCase()))));
}

function resultsTable(list) {
  const th = (key, label, cls = '') => h(`th.sortable${cls}`, {
    onclick: () => push({ sort: key, dir: state.sort === key && state.dir === 'asc' ? 'desc' : 'asc' }),
  }, label, state.sort === key ? h('span.arrow', state.dir === 'asc' ? ' ↑' : ' ↓') : null);

  return h('div.table-wrap', h('table',
    h('thead', h('tr',
      th('name', 'Name'),
      h('th', 'Cost'),
      th('cmc', 'MV', '.num'),
      h('th', 'Type'),
      th('rarity', 'Rarity'),
      th('set', 'Set'),
      th('qty', 'Qty', '.num'),
      th('free', 'Free', '.num'),
      th('edhrec', 'EDHREC', '.num'))),
    h('tbody', list.map((c) => h('tr.clickable', { onclick: () => openCard(c.id) },
      h('td.t-name', c.name, c.printings > 1 ? h('span.muted.small', ` ×${c.printings} printings`) : null),
      h('td', manaCost(c.mana_cost)),
      h('td.num', c.cmc ?? '—'),
      h('td.small', c.type_line || '—'),
      h('td', h('span.tag', c.rarity || '—')),
      h('td.mono', (c.setcode || '').toUpperCase(), ' ', h('span.muted', c.collector_number || '')),
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

// ---------------------------------------------------------------- filters

function pipRow() {
  return h('div.pips', COLORS.map(([c, name]) => h('button.pip', {
    dataset: { c },
    class: state.colors.includes(c) ? 'on' : '',
    title: name,
    onclick: () => {
      const next = state.colors.includes(c)
        ? state.colors.filter((x) => x !== c)
        : [...state.colors, c];
      push({ colors: next });
    },
  }, c)));
}

function chipSet(values, selected, onToggle, { mini = false } = {}) {
  return h('div.chips', values.map((v) => {
    const [val, label] = Array.isArray(v) ? v : [v, v];
    return h(`button.chip${mini ? '.mini' : ''}`, {
      class: selected.includes(val) ? 'on' : '',
      onclick: () => onToggle(val),
    }, label);
  }));
}

function filterPanel() {
  const set = (k) => (e) => push({ [k]: e.target.value });
  const debouncedSet = (k) => debounce((e) => push({ [k]: e.target.value }), 350);

  const activeCount = Object.keys(DEFAULTS).filter((k) => {
    if (['page', 'size', 'view', 'sort', 'dir', 'group', 'owner'].includes(k)) return false;
    const v = state[k]; const d = DEFAULTS[k];
    return Array.isArray(v) ? v.length !== d.length : v !== d;
  }).length;

  return h('div.panel.filters-panel', { id: 'filters' },
    h('div.panel-head',
      h('h2', 'Filters'),
      activeCount ? h('span.tag.info', `${activeCount} active`) : null,
      h('button.btn.sm.ghost', { onclick: () => push({ ...DEFAULTS, owner: state.owner, view: state.view }) }, 'Reset')),
    h('div.panel-body',
      h('div.field',
        h('label', 'Whose collection'),
        h('div.seg', ['matt', 'kayla', 'both'].map((o) => h('button', {
          class: state.owner === o ? 'on' : '',
          onclick: () => push({ owner: o }),
        }, o === 'both' ? 'Both' : o[0].toUpperCase() + o.slice(1))))),

      h('div.field',
        h('label', 'Name contains'),
        h('input', { type: 'search', value: state.q, placeholder: 'sol ring', oninput: debouncedSet('q') })),

      h('div.field',
        h('label', 'Rules text'),
        h('input', {
          type: 'search', value: state.text, placeholder: 'draw a card',
          title: 'Full-text search over oracle text, type line, keywords and tags',
          oninput: debouncedSet('text'),
        })),

      h('div.field',
        h('label', 'Colours'),
        pipRow(),
        h('select', { style: { marginTop: '7px' }, value: state.colorMode, onchange: set('colorMode') },
          Object.entries(COLOR_MODES).map(([v, [label, hint]]) => h('option', { value: v, title: hint, selected: state.colorMode === v }, label)))),

      h('div.field',
        h('label', 'Mana value'),
        h('div.row',
          h('input', { type: 'number', min: '0', step: '1', placeholder: 'min', value: state.cmcMin, oninput: debouncedSet('cmcMin') }),
          h('input', { type: 'number', min: '0', step: '1', placeholder: 'max', value: state.cmcMax, oninput: debouncedSet('cmcMax') }))),

      h('div.field',
        h('label', 'Card type'),
        chipSet(facets?.types || [], state.types, (t) => push({
          types: state.types.includes(t) ? state.types.filter((x) => x !== t) : [...state.types, t],
        }))),

      h('div.field',
        h('label', 'Pool'),
        h('div.seg',
          [['all', 'All'], ['free', 'Unassigned'], ['committed', 'In decks']].map(([v, l]) => h('button', {
            class: state.pool === v ? 'on' : '',
            title: v === 'free' ? 'Copies not committed to a built deck' : '',
            onclick: () => push({ pool: v }),
          }, l)))),

      h('details.adv', { open: activeCount > 3 },
        h('summary', 'More filters'),
        h('div.stack',
          h('div.field',
            h('label', 'Rarity'),
            chipSet(RARITY_ORDER, state.rarities, (r) => push({
              rarities: state.rarities.includes(r) ? state.rarities.filter((x) => x !== r) : [...state.rarities, r],
            }), { mini: true })),

          h('div.field',
            h('label', 'Subtype'),
            h('input', { type: 'search', list: 'dl-subtypes', value: state.subtype, placeholder: 'Elf, Equipment…', oninput: debouncedSet('subtype') })),

          h('div.field',
            h('label', 'Keyword'),
            h('input', { type: 'search', list: 'dl-keywords', value: state.keyword, placeholder: 'Flying, Ward…', oninput: debouncedSet('keyword') })),

          h('div.field',
            h('label', 'Scryfall tag'),
            h('input', { type: 'search', list: 'dl-tags', value: state.tag, placeholder: 'mana-rock, spot-removal…', oninput: debouncedSet('tag') })),

          h('div.field',
            h('label', 'Set'),
            h('input', { type: 'search', list: 'dl-sets', value: state.set, placeholder: 'MH3', oninput: debouncedSet('set') })),

          h('div.field',
            h('label', 'Legality'),
            h('div.row',
              h('select', { value: state.format, onchange: set('format') },
                h('option', { value: '' }, 'any format'),
                (facets?.formats || []).map((f) => h('option', { value: f, selected: state.format === f }, f))),
              h('select', { value: state.legality, onchange: set('legality'), disabled: !state.format },
                ['legal', 'banned', 'restricted', 'not_legal'].map((v) => h('option', { value: v, selected: state.legality === v }, v))))),

          h('div.field',
            h('label', 'Finish'),
            h('div.seg', [['', 'Any'], ['nonfoil', 'Nonfoil'], ['foil', 'Foil'], ['etched', 'Etched']].map(([v, l]) => h('button', {
              class: state.finish === v ? 'on' : '',
              onclick: () => push({ finish: v }),
            }, l)))),

          h('div.field',
            h('label', 'Minimum power / toughness'),
            h('div.row',
              h('input', { type: 'number', placeholder: 'power', value: state.pow, oninput: debouncedSet('pow') }),
              h('input', { type: 'number', placeholder: 'toughness', value: state.tou, oninput: debouncedSet('tou') }))))),

      h('details.adv', null,
        h('summary', 'Saved searches'),
        h('div', savedSearches()))));
}

function savedSearches() {
  const saved = store.get('saved', []);
  const wrap = h('div.stack');

  wrap.append(h('div.flex',
    h('button.btn.sm', {
      onclick: () => {
        const name = prompt('Name this search');
        if (!name) return;
        const next = [...saved.filter((s) => s.name !== name), { name, hash: toHash(state) }];
        store.set('saved', next);
        toast(`Saved "${name}"`, 'ok');
        render();
      },
    }, '＋ Save current')));

  if (!saved.length) {
    wrap.append(h('div.muted.small', 'No saved searches yet.'));
    return wrap;
  }
  wrap.append(h('div.chips', saved.map((s) => h('span.chip', {
    onclick: () => { location.hash = s.hash; },
    title: s.hash,
  }, s.name, h('button.btn.ghost.sm', {
    style: { padding: '0 0 0 6px' },
    title: 'Delete',
    onclick: (e) => {
      e.stopPropagation();
      store.set('saved', saved.filter((x) => x.name !== s.name));
      render();
    },
  }, '×')))));
  return wrap;
}

// ------------------------------------------------------------------- view

let resultsEl;

async function run() {
  render();
  const { sql, params } = buildQuery(state);
  const countQ = buildQuery(state, { countOnly: true });
  const token = Symbol('run');
  lastRun = token;

  fill(resultsEl, loading('Searching'));
  try {
    const [res, total] = await Promise.all([
      api.query(sql, params, { fmt: 'objects', limit: state.size }),
      api.scalar(countQ.sql, countQ.params),
    ]);
    if (lastRun !== token) return; // a newer search overtook this one
    renderResults(res.rows, total ?? 0);
  } catch (e) {
    if (lastRun !== token) return;
    fill(resultsEl, errorBox(e));
  }
}

function renderResults(list, total) {
  const head = h('div.panel-head',
    h('h2', `${num(total)} ${total === 1 ? 'card' : 'cards'}`),
    state.group ? h('span.tag', 'grouped by name') : h('span.tag', 'every printing'),
    h('span.spacer'),
    h('div.seg',
      h('button', { class: state.view === 'grid' ? 'on' : '', onclick: () => push({ view: 'grid' }, { resetPage: false }) }, 'Grid'),
      h('button', { class: state.view === 'table' ? 'on' : '', onclick: () => push({ view: 'table' }, { resetPage: false }) }, 'Table')),
    h('select', {
      style: { width: 'auto' }, value: state.sort,
      onchange: (e) => push({ sort: e.target.value }),
    }, Object.entries(SORTS).map(([k, [label]]) => h('option', { value: k, selected: state.sort === k }, label))),
    h('button.btn.sm', {
      title: 'Reverse sort order',
      onclick: () => push({ dir: state.dir === 'asc' ? 'desc' : 'asc' }, { resetPage: false }),
    }, state.dir === 'asc' ? '↑' : '↓'),
    h('button.btn.sm', { onclick: () => push({ group: !state.group }) }, state.group ? 'Ungroup' : 'Group'),
    h('select', {
      style: { width: 'auto' }, value: String(state.size),
      onchange: (e) => push({ size: Number(e.target.value) }),
    }, PAGE_SIZES.map((n) => h('option', { value: n, selected: state.size === n }, `${n} / page`))),
    h('button.btn.sm', { onclick: () => exportCsv(total), title: 'Download every match as CSV' }, 'CSV'));

  const body = list.length
    ? (state.view === 'grid'
      ? h('div.panel-body', h('div.grid', list.map(cardTile)))
      : resultsTable(list))
    : empty('Nothing matches', 'Loosen a filter, or reset them all.');

  fill(resultsEl, h('div.panel', head, body), pager(total));
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

function render() {
  const root = $('#view');
  if (root.dataset.view !== 'search') {
    root.dataset.view = 'search';
    resultsEl = h('div');
    fill(root, h('div.wrap',
      h('div.split',
        h('div.sticky-side', { id: 'side' }),
        resultsEl)));
  }
  fill($('#side'), filterPanel());
}

/** Datalists for the type-ahead inputs; fetched once. */
async function loadFacets() {
  if (facets) return;
  try {
    const [types, sets, keywords, tags, subtypes, formats] = await Promise.all([
      // `//` shows up as a "type" on split cards — an artefact of parsing the
      // type line, not something anyone wants to filter on.
      api.query("SELECT DISTINCT type FROM card_types WHERE kind='type' AND type GLOB '[A-Za-z]*' ORDER BY type"),
      api.query('SELECT DISTINCT upper(setcode) FROM cards ORDER BY 1'),
      api.query('SELECT DISTINCT keyword FROM card_keywords ORDER BY 1'),
      api.query('SELECT tag_slug FROM card_tags GROUP BY 1 ORDER BY COUNT(*) DESC LIMIT 400'),
      api.query("SELECT DISTINCT type FROM card_types WHERE kind='subtype' ORDER BY 1"),
      api.query('SELECT DISTINCT format FROM legalities ORDER BY 1'),
    ]);
    facets = {
      types: types.rows.flat(),
      formats: formats.rows.flat(),
    };
    const dl = (id, values) => h('datalist', { id }, values.map((v) => h('option', { value: v })));
    document.body.append(
      dl('dl-sets', sets.rows.flat()),
      dl('dl-keywords', keywords.rows.flat()),
      dl('dl-tags', tags.rows.flat()),
      dl('dl-subtypes', subtypes.rows.flat()),
    );
  } catch {
    facets = { types: ['Creature', 'Instant', 'Sorcery', 'Artifact', 'Enchantment', 'Land', 'Planeswalker', 'Battle'], formats: ['commander'] };
  }
}

export async function show(queryString) {
  state = fromHash(queryString);
  render();
  await loadFacets();
  render();
  run();
}
