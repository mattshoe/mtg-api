// Add and remove. Same three steps either way, in order, no skipping:
//
//   1. List    paste it, or drop a file in
//   2. Who     whose collection it lands in
//   3. Review  a real dry run against the server, then Apply
//
// Nothing reaches the write without passing through step 3, and step 3
// shows what the server did when told not to commit — so what you approve
// is what happens.

import * as api from './api.js';
import { h, $, fill, num, toast, store, errorBox } from './util.js';
import { isAdmin } from './admin.js';
import { sharedNow, shareUsed, reportShare } from './share.js';

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
        onclick: () => { store.del(HISTORY_KEY); paint(); },
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
          onclick: () => { flow.list = e.list; flow.step = 'list'; paint(); },
        }, 'Reuse'))))))));
}

/**
 * The worker's account of a share that produced nothing, on the page.
 *
 * Reading it out of a server log is fine for me and useless to the person
 * holding the phone, so it says the whole thing here: what went wrong,
 * what actually arrived, and which build of the worker handled it.
 */
function shareTrouble() {
  const shared = sharedNow();
  if (!shared?.problem) return null;
  const r = shared.report || {};
  const bits = [];
  if (r.files?.length) {
    bits.push(r.files.map((f) => `${f.name || 'unnamed'} · ${f.type || 'no type'} · ${f.size}B`).join('; '));
  } else if (r.files) {
    bits.push('no files in the share');
  }
  if (r.fields?.length) bits.push(`text fields: ${r.fields.join(', ')}`);
  if (r.req) bits.push(`body: ${r.req.rawLen} bytes, ${r.req.ct || 'no content-type'}`);
  if (r.req?.partsSeen?.length) {
    bits.push(`parts: ${r.req.partsSeen.map((x) => `${x.field}${x.filename ? `=${x.filename}` : ''} ${x.type || '?'} ${x.bytes}B`).join('; ')}`);
  }
  if (r.failure) bits.push(`error: ${r.failure}`);
  if (r.rescued) bits.push('parsed by hand');
  if (r.v) bits.push(r.v);
  return { problem: shared.problem, detail: bits.join(' — ') };
}

// One flow at a time, reset on every mount. It lives outside paint() so a
// step change can repaint without threading state through every caller.
let flow = null;

const blank = (mode) => ({
  mode,
  step: 'list',
  // A decklist shared in from another Android app is just a list somebody
  // already typed, so it starts in the box. Seeding it here rather than
  // filling the box after the render is what makes it survive being
  // rendered twice, which is the normal case on a phone.
  list: mode === 'add' ? (sharedNow()?.list || '') : '',
  // Set when a share arrived that the worker could not make anything of.
  // An empty box with no explanation is the worst possible outcome here.
  shareNote: mode === 'add' ? shareTrouble() : null,
  // Deliberately nothing. Remembering the last choice, or defaulting to
  // matt, is how a list lands in the wrong person's collection — the
  // whole reason this is its own step is to make it a decision.
  owner: null,
  preview: null,     // the dry run, once it comes back
  result: null,      // the real write, once applied
  error: null,
  busy: false,
});

const STEPS = [['list', 'List'], ['who', 'Who'], ['review', 'Review']];

/** The 1-2-3 across the top. You can go back, never forward. */
function stepper() {
  const at = flow.step === 'done' ? STEPS.length : STEPS.findIndex(([k]) => k === flow.step);
  return h('div.steps', STEPS.map(([key, label], i) => h('button', {
    class: `step${i === at ? ' on' : ''}${i < at ? ' done' : ''}`,
    disabled: i >= at || flow.busy,
    onclick: () => goto(key),
  }, h('span.step-n', i < at ? '✓' : String(i + 1)), label)));
}

function goto(step) {
  // Past step one the list belongs to the flow, so the share it came from
  // is spent and must not seed the box again.
  if (step !== 'list') shareUsed();
  // Nothing past step two happens without an owner, whatever calls this.
  if ((step === 'review' || step === 'done') && !flow.owner) {
    flow.step = 'who';
    paint();
    return;
  }
  flow.step = step;
  // Stepping back invalidates the dry run: the next one has to be taken
  // against whatever the list and owner have become.
  if (step !== 'review') { flow.preview = null; flow.error = null; }
  paint();
  if (step === 'review' && !flow.preview) runPreview();
}

/**
 * Reading a file only fills the box. It never submits and never advances
 * a step.
 */
function fileDrop(listInput, onChange) {
  const status = h('span.small.muted', 'or drop a file here');

  const input = h('input', {
    type: 'file',
    // Everything. A narrow list greys out the file you actually want in
    // Android's picker, and the same guesswork about MIME types that broke
    // the share sheet would break this too. What can be read is decided
    // after it is read.
    accept: '*/*',
    multiple: true,
    // Hidden by being tiny and transparent, NOT by `display: none`.
    // Android Chrome will not open a picker for an input that is not
    // rendered, so a display:none input is a button that does nothing at
    // all — no error, no picker, no event.
    style: {
      position: 'absolute', width: '1px', height: '1px',
      opacity: '0', overflow: 'hidden', pointerEvents: 'none',
    },
    onchange: (e) => {
      const picked = [...e.target.files];
      reportShare('page: file picker returned', {
        count: picked.length,
        files: picked.map((f) => ({ name: f.name, type: f.type, size: f.size })),
      });
      take(picked);
      e.target.value = '';
    },
  });

  async function take(files) {
    reportShare('page: reading picked files', { count: files.length });
    if (!files.length) return;
    const chunks = [];
    for (const f of files) {
      if (f.size > MAX_UPLOAD) {
        reportShare('page: picked file over the cap', { name: f.name, size: f.size }, 'warn');
        toast(`${f.name} is too big (${(f.size / 1e6).toFixed(1)} MB)`, 'bad');
        continue;
      }
      try {
        const text = await f.text();
        reportShare('page: picked file read', {
          name: f.name, type: f.type, size: f.size, chars: text.length, sample: text.slice(0, 200),
        });
        chunks.push(text);
      } catch (e) {
        reportShare('page: picked file would not read', {
          name: f.name, type: f.type, size: f.size, error: String(e && e.message),
        }, 'error');
        toast(`could not read ${f.name}`, 'bad');
      }
    }
    if (!chunks.length) {
      reportShare('page: nothing came out of the picked files', { count: files.length }, 'warn');
      return;
    }

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
    // The share note was about a file that never arrived. One has now, so
    // the panel asking for it has to go, which means a repaint.
    reportShare('page: picked files landed in the box', {
      chars: listInput.value.length, cards: n, names,
    });
    if (flow.shareNote) {
      shareUsed();
      flow.shareNote = null;
      paint();
    }
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

// ------------------------------------------------------------- 1. list

function stepList() {
  const isAdd = flow.mode === 'add';

  const listInput = h('textarea', {
    id: 'list-input',
    rows: 14,
    spellcheck: false,
    placeholder: isAdd
      ? 'One card per line.\n\n4 Lightning Bolt\n1 Sol Ring (M3C) 409 *F*\nArcane Signet'
      : 'One card per line.\n\n1 Sol Ring\n2 Lightning Bolt (2X2) 117',
  });
  listInput.value = flow.list;

  const count = h('span.muted.small');
  const next = h('button.btn.primary', {
    onclick: () => { flow.list = listInput.value.trim(); goto('who'); },
  }, 'Continue →');

  const updateCount = () => {
    const n = countCards(listInput.value);
    const over = n > MAX_LINES;
    count.textContent = `${n} card${n === 1 ? '' : 's'}${looksLikeCsv(listInput.value) ? ' · CSV' : ''}`;
    count.className = over ? 'small tag bad' : 'muted small';
    if (over) count.textContent += ` — over the ${MAX_LINES} limit`;
    next.disabled = !n || over;
    flow.list = listInput.value;
  };
  listInput.addEventListener('input', updateCount);
  updateCount();
  // Not on a share that came up empty: the thing to do there is tap the
  // big button, and raising the keyboard over it helps nobody.
  if (!flow.shareNote) queueMicrotask(() => listInput.focus());

  const zone = fileDrop(listInput, updateCount);

  /**
   * The rescue, for a share that arrived without its file.
   *
   * Offering a file picker first was wrong: ManaBox builds its export in
   * memory and hands it straight to the share sheet, so there is no file
   * on disk to browse to. Paste is the one that always has something
   * behind it, so paste goes first.
   */
  async function pasteIn() {
    try {
      const text = await navigator.clipboard.readText();
      reportShare('page: pasted from clipboard', { chars: text.length, sample: text.slice(0, 200) });
      if (!text.trim()) { toast('The clipboard is empty', 'bad'); return; }
      listInput.value = text;
      updateCount();
      shareUsed();
      flow.shareNote = null;
      paint();
      toast(`Pasted ${countCards(text)} cards`, 'ok');
    } catch (e) {
      reportShare('page: clipboard read refused', { error: String(e && e.message) }, 'warn');
      toast('Could not read the clipboard — long-press the box and paste', 'bad');
    }
  }

  const rescue = flow.shareNote
    ? h('div.panel', { style: { marginBottom: '14px', borderColor: 'var(--accent)' } },
      h('div.panel-body',
        h('h2', { style: { marginBottom: '6px' } }, 'The file did not come through'),
        h('div.says', { style: { marginBottom: '12px' } },
          'Android handed the share over with nothing attached. Share the export to '
          + 'My Files first, then open it here.'),
        h('button.btn.primary', {
          style: { width: '100%', padding: '16px', fontSize: '1.05rem' },
          onclick: () => {
            const picker = zone.querySelector('input[type=file]');
            reportShare('page: choose file tapped', { found: Boolean(picker) });
            if (picker) picker.click();
          },
        }, 'Choose a saved file'),
        h('button.btn.ghost', {
          style: { width: '100%', padding: '12px', marginTop: '8px' },
          onclick: pasteIn,
        }, 'Or paste'),
        h('details', { style: { marginTop: '12px' } },
          h('summary.small.muted', 'Why'),
          h('div.small.muted', { style: { marginTop: '6px' } }, flow.shareNote.problem),
          flow.shareNote.detail
            ? h('div.small.mono', { style: { marginTop: '6px', opacity: '.8' } }, flow.shareNote.detail)
            : null)))
    : null;

  return h('div.stack', rescue, h('div.panel',
    h('div.panel-head',
      h('h2', isAdd ? 'What are you adding?' : 'What are you removing?'),
      h('span.spacer'), count),
    h('div.panel-body',
      h('div.field', zone),
      h('div.field', listInput),
      h('div.flex-wrap',
        next,
        h('span.spacer'),
        h('button.btn.sm.ghost', {
          onclick: () => {
            shareUsed();   // emptied on purpose, so do not seed it back
            listInput.value = '';
            updateCount();
            listInput.focus();
          },
        }, 'Clear')))));
}

// -------------------------------------------------------------- 2. who

function stepWho() {
  const n = countCards(flow.list);
  const picked = Boolean(flow.owner);
  return h('div.panel',
    h('div.panel-head',
      h('h2', 'Whose collection?'),
      h('span.spacer'),
      h('span.muted.small', `${num(n)} card${n === 1 ? '' : 's'} on the list`)),
    h('div.panel-body',
      // Neither is selected until you say so, and nothing moves until one is.
      h('div.owner-pick', ['matt', 'kayla'].map((o) => h('button', {
        class: `owner-opt${flow.owner === o ? ' on' : ''}`,
        onclick: () => { flow.owner = o; paint(); },
      }, o[0].toUpperCase() + o.slice(1)))),
      h('div.flex-wrap', { style: { marginTop: '16px' } },
        h('button.btn.ghost', { onclick: () => goto('list') }, '← Back'),
        h('button.btn.primary', {
          disabled: !picked,
          title: picked ? '' : 'Pick whose collection this goes to',
          onclick: () => goto('review'),
        }, picked ? `Preview changes · ${flow.owner} →` : 'Pick one to continue'),
        picked ? null : h('span.small.says', 'Pick whose collection this goes to.'))));
}

// ----------------------------------------------------------- 3. review

async function runPreview() {
  if (!flow.owner) { goto('who'); return; }
  flow.busy = true;
  flow.error = null;
  paint();
  try {
    const body = { owner: flow.owner, list: flow.list, dry_run: true };
    flow.preview = flow.mode === 'add' ? await api.addCards(body) : await api.removeCards(body);
  } catch (e) {
    flow.error = e;
  } finally {
    flow.busy = false;
    paint();
  }
}

async function apply() {
  // The server would fall back to matt if owner were missing. It cannot
  // be missing by the time this renders, but this is the write.
  if (!flow.owner) { goto('who'); return; }
  const isAdd = flow.mode === 'add';
  flow.busy = true;
  paint();
  try {
    const body = { owner: flow.owner, list: flow.list, dry_run: false };
    const r = isAdd ? await api.addCards(body) : await api.removeCards(body);
    flow.result = r;
    flow.step = 'done';
    if (r.applied) {
      remember({ mode: flow.mode, owner: flow.owner, list: flow.list, count: r.resolved });
      toast(`${isAdd ? 'Added' : 'Removed'} ${r.resolved} card${r.resolved === 1 ? '' : 's'}`, 'ok');
    }
  } catch (e) {
    flow.error = e;
  } finally {
    flow.busy = false;
    paint();
  }
}

function stepReview() {
  const isAdd = flow.mode === 'add';

  if (flow.busy) {
    return h('div.panel',
      h('div.panel-head', h('h2', 'Review')),
      h('div.panel-body', h('span.spinner'), ' Checking against Scryfall…'));
  }

  if (flow.error) {
    return h('div.panel',
      h('div.panel-head', h('h2', 'Review')),
      h('div.panel-body',
        errorBox(flow.error),
        h('div.flex-wrap', { style: { marginTop: '12px' } },
          h('button.btn.ghost', { onclick: () => goto('who') }, '← Back'),
          h('button.btn', { onclick: runPreview }, 'Try again'))));
  }

  const r = flow.preview;
  if (!r) return h('div.panel', h('div.panel-body', h('span.spinner')));

  const n = r.changes?.length || 0;

  return h('div.panel',
    h('div.panel-head',
      h('h2', 'Review'),
      h('span.spacer'),
      h('span.tag', flow.owner)),
    h('div.panel-body',
      resultBlock(r, flow.mode),
      h('div.flex-wrap', { style: { marginTop: '16px' } },
        h('button.btn.ghost', { onclick: () => goto('who') }, '← Back'),
        h('button', {
          class: `btn ${isAdd ? 'primary' : 'danger'}`,
          disabled: !n,
          title: n ? '' : 'Nothing resolved, so there is nothing to apply',
          onclick: apply,
        }, n
          ? `${isAdd ? 'Add' : 'Remove'} ${num(n)} printing${n === 1 ? '' : 's'} · ${flow.owner}`
          : 'Nothing to apply'))));
}

// --------------------------------------------------------------- done

function stepDone() {
  const isAdd = flow.mode === 'add';
  return h('div.panel',
    h('div.panel-head', h('h2', flow.result?.applied ? 'Applied' : 'Nothing applied')),
    h('div.panel-body',
      resultBlock(flow.result, flow.mode),
      h('div.flex-wrap', { style: { marginTop: '16px' } },
        h('button.btn.primary', {
          onclick: () => { flow = blank(flow.mode); paint(); },
        }, isAdd ? 'Add more' : 'Remove more'),
        h('button.btn.ghost', {
          onclick: () => { location.hash = '#/search'; },
        }, 'Back to search'))));
}

// -------------------------------------------------------------- paint

const BODIES = { list: stepList, who: stepWho, review: stepReview, done: stepDone };

function paint() {
  const isAdd = flow.mode === 'add';
  const root = $('#view');
  if (!isAdmin()) { fill(root); return; }

  fill(root, h('div.wrap',
    h('div.page-head',
      h('h1', isAdd ? 'Add cards' : 'Remove cards'),
      h('span.sub', isAdd
        ? 'Resolved against Scryfall, then written to the collection.'
        : 'Matched against printings you already own.')),

    stepper(),

    h('div.split',
      h('div.stack',
        BODIES[flow.step](),
        // The history table is a way back into step one, so it only
        // belongs on step one.
        flow.step === 'list' ? h('div', { id: 'history-slot' }, historyPanel()) : null),

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
}

function render(mode) {
  flow = blank(mode);
  paint();
}

export function show(mode) {
  render(mode === 'remove' ? 'remove' : 'add');

  // The list is already in the box by now; this only says where it came
  // from, once, however many times the page gets rendered.
  const shared = flow.mode === 'add' ? sharedNow() : null;
  reportShare('page: add page rendered', {
    mode,
    hasShare: Boolean(shared),
    boxChars: flow.list.length,
    note: flow.shareNote?.problem || null,
    id: shared?.report?.id || null,
  });
  if (!shared || shared.announced) return;
  shared.announced = true;
  if (!shared.list) {
    // The note is on the panel already; the share is spent either way.
    shareUsed();
    return;
  }
  const n = countCards(shared.list);
  const from = shared.names?.length ? ` from ${shared.names.join(', ')}` : '';
  toast(`Loaded ${n} card${n === 1 ? '' : 's'}${from}`, 'ok');
}
