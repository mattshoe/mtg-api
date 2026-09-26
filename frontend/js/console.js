// SQL console — the escape hatch for anything the UI does not cover.
//
// Reads run for anyone. A statement that writes needs admin mode, the same
// as everywhere else; the server enforces it and this just asks first so
// the error is a prompt rather than a 401.

import * as api from './api.js';
import {
  h, $, fill, num, download, toCsv, toast, store, errorBox, loading,
} from './util.js';
import { isAdmin } from './admin.js';

const HISTORY_KEY = 'sql-history';

const SNIPPETS = [
  ['Unassigned pool', "SELECT name, free, cmc, type_line\n  FROM bulk_cards JOIN totals USING (owner, name_norm, name)\n WHERE bulk_cards.owner = 'matt'\n ORDER BY free DESC\n LIMIT 50"],
  ['Full-text search', "SELECT c.name, c.type_line\n  FROM card_search s JOIN cards c ON c.id = s.rowid\n WHERE card_search MATCH 'proliferate'\n LIMIT 50"],
  ['Cards in no deck', "SELECT name, owned FROM card_usage\n WHERE owner = 'matt' AND in_decks = 0\n ORDER BY owned DESC LIMIT 50"],
  ['Deck gaps', 'SELECT * FROM deck_gaps ORDER BY deck, name'],
  ['Colour identity spread', "SELECT COALESCE(NULLIF(color_identity,''),'C') AS ci, COUNT(*) n\n  FROM cards WHERE owner='matt' GROUP BY 1 ORDER BY n DESC"],
  ['Commander staples I own', "SELECT t.name, t.total_qty, c.edhrec_rank\n  FROM totals t JOIN cards c ON c.name_norm = t.name_norm\n WHERE t.owner='matt' AND c.edhrec_rank < 300\n GROUP BY t.name_norm ORDER BY c.edhrec_rank LIMIT 50"],
  ['Tag a card set', "SELECT ct.tag_slug, COUNT(*) n\n  FROM card_tags ct JOIN cards c ON c.id = ct.card_id\n WHERE c.owner='matt' GROUP BY 1 ORDER BY n DESC LIMIT 40"],
  ['Schema dump', "SELECT name, type FROM sqlite_master\n WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%'\n ORDER BY type, name"],
];

let editor;
let outEl;

function pushHistory(sql) {
  const hist = store.get(HISTORY_KEY, []).filter((s) => s !== sql);
  store.set(HISTORY_KEY, [sql, ...hist].slice(0, 40));
}

function resultTable(res) {
  if (!res.cols?.length) {
    return h('div.stack',
      h('span.tag.ok', 'statement ran'),
      res.changes !== undefined ? h('div', `${num(res.changes)} row${res.changes === 1 ? '' : 's'} changed`) : null);
  }
  return h('div.table-wrap', h('table',
      h('thead', h('tr', res.cols.map((c) => h('th', c)))),
      h('tbody', res.rows.map((r) => h('tr', r.map((v) => h('td', {
        class: typeof v === 'number' ? 'num' : '',
      }, v === null ? h('span.muted', 'NULL') : String(v))))))));
}

/**
 * A rough read of whether a statement writes, only to decide whether to
 * prompt. The server makes the real decision.
 */
function looksLikeWrite(sql) {
  const bare = sql
    .replace(/'(?:[^']|'')*'/g, ' ')
    .replace(/--[^\n]*/g, ' ')
    .replace(/\/\*[\s\S]*?\*\//g, ' ');
  return /\b(insert|update|delete|drop|create|alter|replace|attach|detach|reindex|vacuum)\b/i.test(bare);
}

async function run() {
  const sql = editor.value.trim();
  if (!sql) return;

  // Reads are open to everyone, so the console stays. A write is the one
  // thing it cannot do while locked, and it says so rather than offering
  // a second way to unlock.
  if (looksLikeWrite(sql) && !isAdmin()) {
    toast('That statement writes — unlock admin mode first', 'bad');
    return;
  }
  fill(outEl, h('div.panel', h('div.panel-body', h('span.spinner'), ' Running…')));
  const t0 = performance.now();
  try {
    const res = await api.query(sql, []);
    const ms = Math.round(performance.now() - t0);
    pushHistory(sql);
    renderHistory();
    fill(outEl, h('div.panel',
      h('div.panel-head',
        h('h2', `${num(res.n ?? 0)} row${res.n === 1 ? '' : 's'}`),
        res.truncated ? h('span.tag.warn', `capped at ${num(res.truncated)}`) : null,
        h('span.muted.small', `${ms} ms`),
        h('span.spacer'),
        res.cols?.length ? h('button.btn.sm', {
          onclick: () => {
            download(`query-${Date.now()}.csv`, toCsv(res.cols, res.rows), 'text/csv');
            toast('Downloaded', 'ok');
          },
        }, 'CSV') : null,
        res.cols?.length ? h('button.btn.sm', {
          onclick: () => {
            navigator.clipboard?.writeText(JSON.stringify(res, null, 2));
            toast('JSON copied', 'ok');
          },
        }, 'Copy JSON') : null),
      resultTable(res)));
  } catch (e) {
    // A 401 here means a token expired mid-session; admin.js has already
    // said so and put the header lock back. No second unlock button.
    fill(outEl, h('div.panel', h('div.panel-body', errorBox(e))));
  }
}

function renderHistory() {
  const slot = $('#sql-history');
  if (!slot) return;
  const hist = store.get(HISTORY_KEY, []);
  fill(slot, hist.length
    ? h('div.scroll-list', hist.map((s) => h('div', {
      style: { padding: '5px 0', cursor: 'pointer', borderBottom: '1px solid var(--line)' },
      onclick: () => { editor.value = s; editor.focus(); },
    }, h('code.small', { style: { whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', display: 'block' } }, s.replace(/\s+/g, ' ').slice(0, 90)))))
    : h('div.muted.small', 'Nothing yet.'));
}

async function schemaPanel() {
  const slot = h('div', loading('Reading schema'));
  try {
    const s = await api.schema();
    const item = (t, isView) => h('details', { style: { marginBottom: '4px' } },
      h('summary', { style: { cursor: 'pointer', fontSize: '13px' } },
        h('code', t.name),
        h('span.muted.small', ` ${num(t.rows)}`),
        isView ? h('span.tag.mini', { style: { marginLeft: '6px' } }, 'view') : null),
      h('div.small.muted', { style: { padding: '5px 0 8px 12px', lineHeight: '1.7' } },
        t.columns.split(' ').map((c) => h('code', {
          style: { marginRight: '8px', cursor: 'pointer' },
          title: 'Insert column name',
          onclick: () => { insert(c.split(':')[0]); },
        }, c))));

    fill(slot,
      h('div.scroll-list', { style: { maxHeight: '420px' } },
        s.tables.map((t) => item(t, false)),
        s.views.map((v) => item(v, true))));
  } catch (e) {
    fill(slot, errorBox(e));
  }
  return slot;
}

function insert(text) {
  const start = editor.selectionStart;
  editor.setRangeText(text, start, editor.selectionEnd, 'end');
  editor.focus();
}

export async function show() {
  const root = $('#view');
  editor = h('textarea', {
    rows: 12,
    spellcheck: false,
    placeholder: "SELECT name, qty FROM cards WHERE owner = 'matt' LIMIT 20",
    onkeydown: (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); run(); }
      if (e.key === 'Tab') { e.preventDefault(); insert('  '); }
    },
  }, store.get('sql-last', ''));
  editor.addEventListener('input', () => store.set('sql-last', editor.value));

  outEl = h('div');

  fill(root, h('div.wrap',
    h('div.page-head',
      h('h1', 'SQL console'),
      h('span.sub', isAdmin() ? 'Reads and writes. One statement per run.' : 'Reads only. One statement per run.')),

    h('div.split',
      h('div.stack',
        h('div.panel',
          h('div.panel-head',
            h('h2', 'Query'),
            h('span.spacer'),
            h('span.muted.small', isAdmin() ? 'admin on · ⌘/Ctrl ↵ to run' : 'reads only · ⌘/Ctrl ↵ to run')),
          h('div.panel-body',
            editor,
            h('div.flex-wrap', { style: { marginTop: '10px' } },
              h('button.btn.primary', { onclick: run }, 'Run'),
              h('button.btn.sm.ghost', { onclick: () => { editor.value = ''; editor.focus(); fill(outEl); } }, 'Clear')))),
        outEl),

      h('div.sticky-side.stack',
        h('div.panel',
          h('div.panel-head', h('h2', 'Snippets')),
          h('div.panel-body', h('div.chips', SNIPPETS.map(([label, sql]) => h('button.chip', {
            onclick: () => { editor.value = sql; editor.focus(); store.set('sql-last', sql); },
          }, label))))),
        h('div.panel',
          h('div.panel-head', h('h2', 'Schema'), h('span.muted.small', 'click a column to insert')),
          h('div.panel-body', await schemaPanel())),
        h('div.panel',
          h('div.panel-head', h('h2', 'History'), h('span.spacer'),
            h('button.btn.sm.ghost', { onclick: () => { store.del(HISTORY_KEY); renderHistory(); } }, 'Clear')),
          h('div.panel-body', h('div', { id: 'sql-history' })))))));

  renderHistory();
  editor.focus();
}
