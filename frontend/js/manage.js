// Add and remove. Same shape either way: paste a list, preview, apply.
//
// Preview is not optional politeness — it is a real dry run against the
// server, so what you approve is what happens.

import * as api from './api.js';
import { h, $, fill, num, toast, store, errorBox } from './util.js';

const HISTORY_KEY = 'history';
const MAX_HISTORY = 30;

const EXAMPLES = [
  'Sol Ring',
  '4 Lightning Bolt',
  '2x Arcane Signet',
  '1 Sol Ring (M3C) 409',
  '1 Sol Ring (M3C) 409 *F*',
  '# a comment line is ignored',
];

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

function render(mode) {
  currentMode = mode;
  const isAdd = mode === 'add';
  const root = $('#view');

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

  const count = h('span.muted.small', '0 lines');
  listInput.addEventListener('input', () => {
    const n = listInput.value.split('\n').filter((l) => l.trim() && !l.trim().startsWith('#')).length;
    count.textContent = `${n} line${n === 1 ? '' : 's'}`;
  });

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
        count.textContent = '0 lines';
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
            h('div.field', listInput),
            h('div.flex-wrap',
              previewBtn,
              applyBtn,
              h('span.spacer'),
              h('button.btn.sm.ghost', {
                onclick: () => { listInput.value = ''; count.textContent = '0 lines'; fill(out); },
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
                : 'Without a set code, copies are taken from the plainest printing first. Removing more than you own is refused outright and changes nothing.')))))));

  applyBtn.disabled = false;
  listInput.focus();
}

export function show(mode) {
  render(mode === 'remove' ? 'remove' : 'add');
}
