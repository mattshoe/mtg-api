// Add and remove. Same shape either way: paste a list, preview, apply.
//
// Preview is not optional politeness — it is a real dry run against the
// server, so what you approve is what happens.

import * as api from './api.js';
import { h, $, fill, num, toast, store, errorBox } from './util.js';
import { isAdmin, lockedPanel, onAdminChange } from './admin.js';

const HISTORY_KEY = 'history';
const MAX_HISTORY = 30;

const EXAMPLES = [
  'Sol Ring',
  '4 Lightning Bolt',
  '2x Arcane Signet',
  '1 Sol Ring (M3C) 409',
  '1 Sol Ring (M3C) 409 *F*',
  '1x Sol Ring (m3c) 409 [Ramp]',
  'SB: 2 Negate',
  '# and // are comments',
];

const MAX_UPLOAD = 2 * 1024 * 1024;   // a collection export, not a database
const MAX_LINES = 1000;               // the API's own cap

/**
 * Same detection the server uses: a CSV needs a header naming a card
 * column. Only used here to count rows honestly in the UI.
 */
function looksLikeCsv(text) {
  const first = text.split('\n').find((l) => l.trim());
  if (!first || !first.includes(',')) return false;
  const cols = first.toLowerCase().replace(/"/g, '').split(',').map((c) => c.replace(/[^a-z]/g, ''));
  return cols.includes('name') || cols.includes('cardname');
}

/** How many cards a list actually represents, whatever shape it is. */
function countCards(text) {
  if (!text.trim()) return 0;
  if (looksLikeCsv(text)) return text.split('\n').filter((l) => l.trim()).length - 1;
  return text.split('\n').filter((l) => {
    const t = l.trim();
    return t && !t.startsWith('#') && !t.startsWith('//');
  }).length;
}

function remember(entry) {
  const hist = store.get(HISTORY_KEY, []);
  store.set(HISTORY_KEY, [{ ...entry, at: new Date().toISOString() }, ...hist].slice(0, MAX_HISTORY));
}

function changesTable(changes, mode) {
  const arrow = (before, after) => {
    const up = after > before;
    return h('span', { class: `tag ${up ? 'ok' : 'bad'}` }, `${before} → ${after}`);
  };
  return h('div.table-wrap', h('table',
    h('thead', h('tr', h('th', 'Card'), h('th', 'Set'), h('th', '#'), h('th', 'Finish'), h('th', 'Quantity'))),
    h('tbody', changes.map(([name, set, cn, finish, before, after]) => h('tr',
      h('td.t-name', name),
      h('td.mono', set),
      h('td.mono', cn),
      h('td.small', finish),
      h('td', arrow(before, after)))))));
}

function resultBlock(r, mode) {
  const kids = [];

  if (r.dry_run) {
    kids.push(h('div.flex-wrap',
      h('span.tag.info', 'preview — nothing written yet'),
      h('span.muted.small', `${num(r.resolved)} line${r.resolved === 1 ? '' : 's'} resolved`)));
  } else {
    kids.push(h('div.flex-wrap',
      r.applied ? h('span.tag.ok', 'applied') : h('span.tag.warn', 'nothing applied'),
      h('span.muted.small', `${num(r.resolved)} resolved, ${num(r.failed)} failed`)));
  }

  if (r.changes?.length) kids.push(changesTable(r.changes, mode));

  if (r.errors?.length) {
    kids.push(h('div', { style: { marginTop: '12px' } },
      h('h3', { style: { marginBottom: '6px' } }, `${r.errors.length} problem${r.errors.length === 1 ? '' : 's'}`),
      h('div.err', r.errors.join('\n'))));
  }

  // Notes are things worth knowing that are not failures — enrichment
  // skipped on a big import, mostly. They must not read as errors.
  if (r.notes?.length) {
    kids.push(h('div.small.muted', { style: { marginTop: '10px' } }, r.notes.join(' · ')));
  }

  return h('div.stack', kids);
}

function historyPanel() {
  const hist = store.get(HISTORY_KEY, []);
  if (!hist.length) return null;
  return h('div.panel',
    h('div.panel-head',
      h('h2', 'Recent'),
      h('span.spacer'),
      h('button.btn.sm.ghost', {
        onclick: () => { store.del(HISTORY_KEY); render(currentMode); },
      }, 'Clear')),
    h('div.table-wrap', h('table',
      h('thead', h('tr', h('th', 'When'), h('th', 'What'), h('th', 'Owner'), h('th.num', 'Cards'), h('th', ''))),
      h('tbody', hist.slice(0, 12).map((e) => h('tr',
        h('td.small.muted.nowrap', new Date(e.at).toLocaleString()),
        h('td', h('span', { class: `tag ${e.mode === 'add' ? 'ok' : 'bad'}` }, e.mode)),
        h('td.small', e.owner),
        h('td.num', e.count),
        h('td', h('button.btn.sm.ghost', {
          title: 'Put this list back in the box',
          onclick: () => {
            const ta = $('#list-input');
            if (ta) { ta.value = e.list; ta.focus(); }
          },
        }, 'Reuse'))))))));
}

let currentMode = 'add';

/**
 * Reading a file only fills the box — it never submits. You still see
 * exactly what will be sent, and the preview still runs against it.
 */
function fileDrop(listInput, onChange) {
  const status = h('span.small.muted', 'or drop a file here');

  const input = h('input', {
    type: 'file',
    accept: '.txt,.csv,.dek,.md,text/plain,text/csv',
    multiple: true,
    style: { display: 'none' },
    onchange: (e) => { take([...e.target.files]); e.target.value = ''; },
  });

  async function take(files) {
    if (!files.length) return;
    const chunks = [];
    for (const f of files) {
      if (f.size > MAX_UPLOAD) {
        toast(`${f.name} is too big (${(f.size / 1e6).toFixed(1)} MB)`, 'bad');
        continue;
      }
      try {
        chunks.push(await f.text());
      } catch {
        toast(`could not read ${f.name}`, 'bad');
      }
    }
    if (!chunks.length) return;

    const incoming = chunks.join('\n');
    // Append rather than replace, so a file never eats something typed.
    listInput.value = listInput.value.trim()
      ? `${listInput.value.replace(/\s*$/, '')}\n${incoming}`
      : incoming;
    listInput.dispatchEvent(new Event('input'));

    const n = countCards(incoming);
    const kind = looksLikeCsv(incoming) ? 'CSV' : 'decklist';
    const names = files.map((f) => f.name).join(', ');
    status.textContent = `${names} — ${kind}, ${n} card${n === 1 ? '' : 's'}`;
    toast(`Loaded ${n} card${n === 1 ? '' : 's'} from ${kind}`, 'ok');
    if (onChange) onChange();
  }

  const zone = h('div.dropzone', {
    onclick: () => input.click(),
    ondragover: (e) => { e.preventDefault(); zone.classList.add('over'); },
    ondragleave: () => zone.classList.remove('over'),
    ondrop: (e) => {
      e.preventDefault();
      zone.classList.remove('over');
      take([...(e.dataTransfer?.files || [])]);
    },
  },
  input,
  h('span.dz-icon', '⤓'),
  h('div',
    h('div.dz-main', 'Upload a file'),
    h('div.dz-sub', status)));

  return zone;
}

function render(mode) {
  currentMode = mode;
  const isAdd = mode === 'add';
  const root = $('#view');

  // The server refuses these without a token anyway; this is so the page
  // says why up front instead of failing after you have typed a decklist.
  if (!isAdmin()) {
    fill(root, h('div.wrap',
      h('div.page-head',
        h('h1', isAdd ? 'Add cards' : 'Remove cards')),
      lockedPanel(isAdd ? 'add cards' : 'remove cards', () => render(mode))));
    return;
  }

  const owner = store.get('owner', 'matt');
  const out = h('div');

  const listInput = h('textarea', {
    id: 'list-input',
    rows: 14,
    placeholder: isAdd
      ? 'One card per line.\n\n4 Lightning Bolt\n1 Sol Ring (M3C) 409 *F*\nArcane Signet'
      : 'One card per line.\n\n1 Sol Ring\n2 Lightning Bolt (2X2) 117',
    spellcheck: false,
  });

  const ownerSel = h('div.seg', ['matt', 'kayla'].map((o) => h('button', {
    class: owner === o ? 'on' : '',
    onclick: (e) => {
      store.set('owner', o);
      [...e.target.parentElement.children].forEach((b) => b.classList.remove('on'));
      e.target.classList.add('on');
    },
  }, o[0].toUpperCase() + o.slice(1))));

  const count = h('span.muted.small', '0 cards');
  const updateCount = () => {
    const n = countCards(listInput.value);
    const over = n > MAX_LINES;
    count.textContent = `${n} card${n === 1 ? '' : 's'}${looksLikeCsv(listInput.value) ? ' · CSV' : ''}`;
    count.className = over ? 'small tag bad' : 'muted small';
    if (over) count.textContent += ` — over the ${MAX_LINES} limit`;
  };
  listInput.addEventListener('input', updateCount);

  const busy = (on) => {
    for (const b of [previewBtn, applyBtn]) b.disabled = on;
    previewBtn.textContent = on ? 'Working…' : 'Preview';
  };

  async function submit(dryRun) {
    const list = listInput.value.trim();
    if (!list) { toast('Nothing to submit', 'bad'); return; }
    const who = $('.seg button.on', root)?.textContent.toLowerCase() || owner;

    busy(true);
    fill(out, h('div.panel', h('div.panel-body', h('span.spinner'), ' Talking to Scryfall…')));
    try {
      const body = { owner: who, list, dry_run: dryRun };
      const r = isAdd ? await api.addCards(body) : await api.removeCards(body);

      fill(out, h('div.panel',
        h('div.panel-head', h('h2', dryRun ? 'Preview' : 'Result')),
        h('div.panel-body', resultBlock(r, mode))));

      if (!dryRun && r.applied) {
        remember({ mode, owner: who, list, count: r.resolved });
        toast(`${isAdd ? 'Added' : 'Removed'} ${r.resolved} card${r.resolved === 1 ? '' : 's'}`, 'ok');
        listInput.value = '';
        updateCount();
        const hp = $('#history-slot');
        if (hp) fill(hp, historyPanel());
      } else if (dryRun) {
        applyBtn.disabled = false;
        applyBtn.classList.add('primary');
      }
    } catch (e) {
      fill(out, h('div.panel', h('div.panel-body', errorBox(e))));
      toast(String(e.message), 'bad');
    } finally {
      busy(false);
    }
  }

  const previewBtn = h('button.btn', { onclick: () => submit(true) }, 'Preview');
  const applyBtn = h('button', {
    class: `btn ${isAdd ? 'primary' : 'danger'}`,
    onclick: () => {
      if (!isAdd && !confirm('Remove these cards from the collection?')) return;
      submit(false);
    },
  }, isAdd ? 'Add to collection' : 'Remove from collection');

  fill(root, h('div.wrap',
    h('div.page-head',
      h('h1', isAdd ? 'Add cards' : 'Remove cards'),
      h('span.sub', isAdd
        ? 'Resolved against Scryfall, then written to the collection.'
        : 'Matched against printings you already own.')),

    h('div.split',
      h('div.stack',
        h('div.panel',
          h('div.panel-head', h('h2', 'List'), h('span.spacer'), count),
          h('div.panel-body',
            h('div.field', h('label', 'Whose collection'), ownerSel),
            h('div.field', fileDrop(listInput, updateCount)),
            h('div.field', listInput),
            h('div.flex-wrap',
              previewBtn,
              applyBtn,
              h('span.spacer'),
              h('button.btn.sm.ghost', {
                onclick: () => { listInput.value = ''; updateCount(); fill(out); },
              }, 'Clear')))),
        out,
        h('div', { id: 'history-slot' }, historyPanel())),

      h('div.sticky-side',
        h('div.panel',
          h('div.panel-head', h('h2', 'Accepted formats')),
          h('div.panel-body',
            h('pre.out', EXAMPLES.join('\n')),
            h('div.small.muted', { style: { marginTop: '10px' } },
              isAdd
                ? 'A set code plus collector number pins an exact printing. Without one, Scryfall picks the most recent paper printing. A bad line does not sink the rest — it comes back in the errors list.'
                : 'Without a set code, copies are taken from the plainest printing first. Removing more than you own is refused outright and changes nothing.'))),

        h('div.panel', { style: { marginTop: '12px' } },
          h('div.panel-head', h('h2', 'Files it understands')),
          h('div.panel-body',
            h('div.small.muted',
              'Drop an export straight in. Text decklists from MTGA, Moxfield, '
              + 'Archidekt or MTGO, and CSV from ManaBox, Moxfield or Deckbox. '
              + 'Section headers like Deck and Sideboard, Archidekt categories '
              + 'and SB: prefixes are ignored rather than treated as cards. '
              + 'CSV columns are matched by name, so their order does not matter.')))))));

  applyBtn.disabled = false;
  listInput.focus();
}

// Locking from the topbar while sitting on one of these views should put
// the lock screen up immediately, not wait for a navigation.
onAdminChange(() => {
  if (document.querySelector('#view')?.dataset.view === currentMode) render(currentMode);
});

export function show(mode) {
  render(mode === 'remove' ? 'remove' : 'add');
}
