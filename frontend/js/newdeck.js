// The new-deck wizard.
//
// One decision per screen, in the order you actually make them, and each
// screen says what it wants and refuses to go on without it. The format
// comes first because it decides whether the rest of the wizard asks
// about a commander at all.
//
// Nothing is written until the last step. The review step is the server's
// own dry run, so it shows the real list, the real card count, and the
// real shopping list — creating a deck pulls its cards out of bulk and
// buys whatever bulk cannot cover.

import * as api from './api.js';
import { h, $, fill, num, toast, loading, errorBox } from './util.js';
import { autocomplete } from './complete.js';

const OWNERS = [['matt', 'Matt'], ['kayla', 'Kayla']];

const BRACKETS = [
  ['', 'Not sure yet'],
  ['1', '1 — Exhibition'],
  ['2', '2 — Core'],
  ['3', '3 — Upgraded'],
  ['4', '4 — Optimised'],
  ['5', '5 — cEDH'],
];

let formats = [];
let state = null;

const blank = () => ({
  step: 0,
  format: '',
  owner: '',
  name: '',
  theme: '',
  commander: '',
  bracket: '',
  is_proxy: false,
  list: '',
  sources: {},     // name_norm -> 'bulk' | 'transfer' | 'buy'
  checked: null,   // the name check for the list as it currently stands
  checking: false,
  plan: null,
  error: null,
  busy: false,
});

const fmt = () => formats.find((f) => f.id === state.format) || null;
const wantsCommander = () => Boolean(fmt()?.singleton);

/** The steps, minus the ones this format does not need. */
function steps() {
  return [
    { id: 'format', title: 'Format' },
    { id: 'owner', title: 'Whose' },
    { id: 'name', title: 'Name' },
    wantsCommander() ? { id: 'commander', title: 'Commander' } : null,
    { id: 'list', title: 'Cards' },
    { id: 'check', title: 'Check' },
    { id: 'source', title: 'Source' },
    { id: 'review', title: 'Review' },
  ].filter(Boolean);
}

const at = () => steps()[state.step];

/** Why the current step will not let you continue, or null. */
function blocker() {
  switch (at().id) {
    case 'format': return state.format ? null : 'Pick a format.';
    case 'owner': return state.owner ? null : 'Pick whose deck this is.';
    case 'name': return state.name.trim() ? null : 'Give the deck a name.';
    case 'commander': return state.commander.trim() ? null : 'Name the commander.';
    case 'list': return countLines(state.list) ? null : 'Paste the decklist, or upload a file.';
    case 'check': {
      if (state.checking) return 'Checking the names…';
      if (!state.checked) return 'Checking the names…';
      const bad = state.checked.cards.filter((c) => !c.ok).length;
      return bad
        ? `${bad} name${bad === 1 ? ' is' : 's are'} not a real card. Fix ${bad === 1 ? 'it' : 'them'} before going on.`
        : null;
    }
    case 'source': return state.plan ? null : 'Working out where the cards come from…';
    default: return null;
  }
}

const countLines = (t) => String(t || '').split('\n')
  .filter((l) => l.trim() && !l.trim().startsWith('#') && !l.trim().startsWith('//')).length;

function go(delta) {
  const next = state.step + delta;
  if (next < 0 || next >= steps().length) return;
  if (delta > 0 && blocker()) return;
  state.step = next;
  state.error = null;
  // The plan is the same for Source and Review; only Review sends the
  // choices back, so going forward between them does not refetch.
  if (!['source', 'review'].includes(at().id)) state.plan = null;
  paint();
  if (at().id === 'check') checkNames();
  if (['source', 'review'].includes(at().id)) dryRun();
}

/** The names, as the list currently reads. Used to know when to re-check. */
const listFingerprint = () => `${state.commander}\n--\n${state.list}`;

async function checkNames() {
  const fingerprint = listFingerprint();
  if (state.checked?.of === fingerprint) return;    // already checked this exact list
  state.checking = true;
  state.checked = null;
  state.error = null;
  paint();
  try {
    const names = [
      ...(wantsCommander() ? state.commander.split('\n') : []),
      ...state.list.split('\n'),
    ].map((l) => l.trim()).filter((l) => l && !l.startsWith('#') && !l.startsWith('//'));
    const r = await api.validateNames({ list: names.join('\n') });
    state.checked = { ...r, of: fingerprint };
  } catch (e) {
    state.error = e;
  } finally {
    state.checking = false;
    paint();
  }
}

function payload(dryRun) {
  return {
    name: state.name.trim(),
    format: state.format,
    owner: state.owner,
    commander: wantsCommander() ? state.commander.trim() : '',
    bracket: wantsCommander() ? state.bracket : '',
    theme: state.theme.trim(),
    is_proxy: state.is_proxy,
    list: state.list,
    sources: state.sources,
    dry_run: dryRun,
  };
}

async function dryRun() {
  state.busy = true;
  state.error = null;
  paint();
  try {
    state.plan = await api.createDeck(payload(true));
  } catch (e) {
    state.error = e;
  } finally {
    state.busy = false;
    paint();
  }
}

async function create() {
  state.busy = true;
  state.error = null;
  paint();
  try {
    const r = await api.createDeck(payload(false));
    const bought = (r.acquired || []).reduce((a, [, q]) => a + q, 0);
    toast(`Created ${r.deck.name}`
      + (bought ? ` — ${num(bought)} card${bought === 1 ? '' : 's'} added to the collection` : ''), 'ok');
    location.hash = `#/decks/${encodeURIComponent(r.slug)}`;
  } catch (e) {
    state.error = e;
    state.busy = false;
    paint();
  }
}

// ------------------------------------------------------------- the steps

const field = (label, hint, ...kids) => h('div.field',
  h('label', label),
  ...kids,
  hint ? h('div.small.muted', { style: { marginTop: '5px' } }, hint) : null);

function pick(options, current, onPick) {
  return h('div.owner-pick', options.map(([id, label]) => h('button', {
    class: `owner-opt${current === id ? ' on' : ''}`,
    onclick: () => { onPick(id); paint(); },
  }, label)));
}

function stepBody() {
  switch (at().id) {
    case 'format':
      return field('What are you building?', 'Commander and Brawl have a commander; the rest do not.',
        h('div.fmt-pick', formats.map((f) => h('button', {
          class: `owner-opt${state.format === f.id ? ' on' : ''}`,
          onclick: () => {
            state.format = f.id;
            if (!wantsCommander()) { state.commander = ''; state.bracket = ''; }
            paint();
          },
        }, f.label, f.size ? h('div.small.muted', `${f.size} cards`) : null))));

    case 'owner':
      return field('Whose deck is this?', 'It decides whose collection the cards come out of.',
        pick(OWNERS, state.owner, (v) => { state.owner = v; }));

    case 'name': {
      const name = h('input', { type: 'text', value: state.name, placeholder: 'Fairy Deck (Alela)' });
      name.addEventListener('input', () => { state.name = name.value; refreshFooter(); });
      const theme = h('input', { type: 'text', value: state.theme, placeholder: 'Faerie tribal, flying beats' });
      theme.addEventListener('input', () => { state.theme = theme.value; });
      const proxy = h('input', { type: 'checkbox', checked: state.is_proxy });
      proxy.addEventListener('change', () => { state.is_proxy = proxy.checked; });
      queueMicrotask(() => name.focus());
      return h('div',
        field('Deck name', 'Used for the URL too, so keep it recognisable.', name),
        field('Theme', 'Optional. What the deck is trying to do.', theme),
        h('label.check', { style: { marginTop: '4px' } }, proxy,
          h('span', 'It is a proxy deck'),
          h('div.small.muted', 'A proxy deck does not consume real cards, so nothing is bought for it.')));
    }

    case 'commander': {
      const cmdr = h('input', { type: 'text', value: state.commander, placeholder: 'Alela, Cunning Conqueror' });
      cmdr.addEventListener('input', () => { state.commander = cmdr.value; refreshFooter(); });
      const cmdrField = autocomplete(cmdr);
      queueMicrotask(() => cmdr.focus());
      const sel = h('select',
        BRACKETS.map(([v, label]) => h('option', { value: v, selected: state.bracket === v }, label)));
      sel.addEventListener('change', () => { state.bracket = sel.value; });
      return h('div',
        field('Commander', 'Starts suggesting real cards after two letters.', cmdrField),
        field('Bracket', 'Optional. The Commander power bracket, 1 to 5.', sel));
    }

    case 'list': {
      const box = h('textarea', {
        rows: 16,
        spellcheck: false,
        placeholder: wantsCommander()
          ? 'The 99, one per line.\n\n1 Sol Ring\n1 Arcane Signet\n20 Island'
          : 'One per line.\n\n4 Lightning Bolt\n4 Monastery Swiftspear\n20 Mountain',
      });
      box.value = state.list;
      box.addEventListener('input', () => { state.list = box.value; refreshFooter(); });
      const file = h('input', {
        type: 'file',
        accept: '.txt,.csv,.dek,.md,text/plain,text/csv',
        style: { display: 'none' },
      });
      file.addEventListener('change', async () => {
        const f = file.files[0];
        file.value = '';
        if (!f) return;
        try {
          const text = await f.text();
          box.value = box.value.trim() ? `${box.value.replace(/\s*$/, '')}\n${text}` : text;
          state.list = box.value;
          refreshFooter();
          toast(`Loaded ${num(countLines(text))} lines from ${f.name}`, 'ok');
        } catch {
          toast(`could not read ${f.name}`, 'bad');
        }
      });
      queueMicrotask(() => box.focus());
      return h('div',
        field(wantsCommander() ? 'The 99' : 'The deck',
          'A decklist, a ManaBox or Moxfield CSV, whatever your exporter produces.'
          + (wantsCommander() ? ' The commander is already handled, so leave it out.' : ''),
          box),
        h('div.flex-wrap',
          file,
          h('button.btn.sm.ghost', { onclick: () => file.click() }, 'Upload a file')));
    }

    case 'check':
      return checkBody();

    case 'source':
      return sourceBody();

    case 'review':
    default:
      return reviewBody();
  }
}

// ------------------------------------------------------------- 5. check
//
// Every name, against the collection first and Scryfall for the rest. A
// typo that gets past here becomes a card nobody owns and a deck slot
// nothing can fill, so this step will not let you go on until it is clean.

function checkBody() {
  if (state.checking) return h('div', h('span.spinner'), ' Checking every name…');
  if (state.error) {
    return h('div',
      h('div.err', String(state.error.message || state.error)),
      h('button.btn.sm', { style: { marginTop: '10px' }, onclick: checkNames }, 'Try again'));
  }
  const r = state.checked;
  if (!r) return h('div', h('span.spinner'));

  const bad = r.cards.filter((c) => !c.ok);

  if (!bad.length) {
    return h('div',
      h('div.flex-wrap',
        h('span.tag.ok', 'all real'),
        h('span.small.says', `${num(r.checked)} name${r.checked === 1 ? '' : 's'} checked, every one is a card.`)),
      h('div.small.muted', { style: { marginTop: '8px' } },
        'Names already in the collection are confirmed from it; the rest were looked up on Scryfall.'));
  }

  // Replacing a name edits the list itself, so the fix survives to the end.
  const replace = (from, to) => {
    const swap = (text) => text.split('\n').map((line) => {
      const m = line.match(/^(\s*\d*\s*[xX]?\s*)(.+?)(\s*)$/);
      if (!m) return line;
      return m[2].trim().toLowerCase() === from.toLowerCase() ? `${m[1]}${to}` : line;
    }).join('\n');
    state.list = swap(state.list);
    state.commander = swap(state.commander);
    state.checked = null;
    paint();
    checkNames();
  };

  return h('div',
    h('div.flex-wrap',
      h('span.tag.bad', `${bad.length} not found`),
      h('span.small.says',
        `${num(bad.length)} of ${num(r.checked)} name${r.checked === 1 ? '' : 's'} is not a real card. `
        + 'Fix them here, or go back and edit the list.')),

    h('div.table-wrap', { style: { marginTop: '10px', maxHeight: '44vh', overflowY: 'auto' } },
      h('table',
        h('thead', h('tr', h('th', 'What you typed'), h('th', 'Did you mean'), h('th', ''))),
        h('tbody', bad.map((c) => h('tr',
          h('td.t-name', c.name),
          h('td', c.suggestion
            ? h('span', c.suggestion)
            : h('span.small.muted', 'no close match')),
          h('td', c.suggestion
            ? h('button.btn.sm', { onclick: () => replace(c.name, c.suggestion) }, 'Use it')
            : null)))))),

    h('div.small.muted', { style: { marginTop: '10px' } },
      'A name with no close match is usually a card that does not exist, or one '
      + 'spelled differently enough that Scryfall will not guess between two cards.'));
}

// --------------------------------------------------------- 6. where from
//
// The server has already worked out what each collection could cover. The
// arithmetic for a given choice is simple and fixed, so it is repeated here
// rather than asking the server again on every toggle — the choices go back
// with the dry run on the next step, which is what confirms them.

/** How a row breaks down under a choice. Mirrors decideSources on the server. */
function split(row, choice) {
  if (row.basic) return { bulk: row.need, transfer: 0, buy: 0 };
  const bulk = choice === 'buy' ? 0 : Math.min(row.need, row.own_free);
  let left = row.need - bulk;
  const transfer = choice === 'transfer' ? Math.min(left, row.other_free) : 0;
  left -= transfer;
  return { bulk, transfer, buy: left };
}

const choiceOf = (row) => state.sources[row.name_norm] || 'bulk';

function sourceTotals(rows) {
  const t = { bulk: 0, transfer: 0, buy: 0 };
  for (const r of rows) {
    const s = split(r, choiceOf(r));
    t.bulk += s.bulk; t.transfer += s.transfer; t.buy += s.buy;
  }
  return t;
}

function setAll(rows, choice) {
  for (const r of rows) {
    if (r.basic) continue;
    if (choice === 'transfer' && !r.other_free) continue;
    state.sources[r.name_norm] = choice;
  }
  paint();
}

function sourceBody() {
  if (state.busy) return h('div', h('span.spinner'), ' Working out where the cards come from…');
  if (state.error) {
    return h('div',
      h('div.err', [state.error.message, ...(state.error.errors || [])].join('\n')),
      h('button.btn.sm', { style: { marginTop: '10px' }, onclick: dryRun }, 'Try again'));
  }
  const rows = state.plan?.sourcing || [];
  if (!rows.length) return h('div', h('span.spinner'));

  const other = rows.find((r) => r.other_owner)?.other_owner;
  const t = sourceTotals(rows);
  const decidable = rows.filter((r) => !r.basic);
  const transferable = decidable.filter((r) => r.other_free > 0);

  if (!decidable.length) {
    return h('div.small.muted',
      'Nothing to decide — every card here is a basic land, which the collection does not track.');
  }

  const chooser = (r) => {
    if (r.basic) return h('span.small.muted', 'untracked');
    const c = choiceOf(r);
    const opt = (id, label, enabled, title) => h('button', {
      class: c === id ? 'on' : '',
      disabled: !enabled,
      title: title || '',
      onclick: () => { state.sources[r.name_norm] = id; paint(); },
    }, label);
    return h('div.seg.seg-sm',
      opt('bulk', 'Bulk', r.own_free > 0, r.own_free ? '' : 'nothing spare in this collection'),
      opt('transfer', other ? `From ${other}` : 'Transfer', r.other_free > 0,
        r.other_free ? '' : `nothing spare in ${other || 'the other collection'}`),
      opt('buy', 'New', true, 'get new copies and leave bulk alone'));
  };

  const covered = (r) => {
    const s = split(r, choiceOf(r));
    return h('td.small',
      s.bulk ? h('span.chip.mini.ok', `${s.bulk} bulk`) : null,
      s.transfer ? h('span.chip.mini', `${s.transfer} ${other}`) : null,
      s.buy ? h('span.chip.mini.bad', `${s.buy} new`) : null);
  };

  return h('div',
    h('div.flex-wrap', { style: { marginBottom: '10px' } },
      h('span.small.says', 'Set every card at once:'),
      h('button.btn.sm', { onclick: () => setAll(decidable, 'bulk') }, 'All from bulk'),
      other && transferable.length
        ? h('button.btn.sm', {
          onclick: () => setAll(transferable, 'transfer'),
        }, `All from ${other} where possible`)
        : null,
      h('button.btn.sm', { onclick: () => setAll(decidable, 'buy') }, 'All new')),

    h('div.summary-bar',
      h('span', h('strong', num(t.bulk)), ' out of bulk'),
      other ? h('span', h('strong', num(t.transfer)), ` from ${other}`) : null,
      h('span', h('strong', num(t.buy)), ' bought new'),
      h('span.spacer'),
      h('span.muted', `${num(transferable.length)} card${transferable.length === 1 ? '' : 's'} `
        + `could come from ${other || 'the other collection'}`)),

    h('div.table-wrap', { style: { maxHeight: '46vh', overflowY: 'auto', marginTop: '10px' } },
      h('table',
        h('thead', h('tr',
          h('th', 'Card'), h('th.num', 'Need'), h('th.num', 'Your bulk'),
          h('th.num', other ? `${other}'s bulk` : 'Other'),
          h('th', 'Source'), h('th', 'Covered by'))),
        h('tbody', rows.map((r) => h('tr',
          h('td.t-name', r.name),
          h('td.num', r.need),
          h('td.num', r.basic ? '—' : r.own_free),
          h('td.num', r.basic ? '—' : r.other_free),
          h('td', chooser(r)),
          covered(r)))))));
}

function reviewBody() {
  if (state.busy) return h('div', h('span.spinner'), ' Checking the list against Scryfall…');
  if (state.error) {
    return h('div',
      h('div.err', [state.error.message, ...(state.error.errors || [])].join('\n')),
      h('button.btn.sm', { style: { marginTop: '10px' }, onclick: dryRun }, 'Try again'));
  }
  const p = state.plan;
  if (!p) return h('div', h('span.spinner'));

  const row = (k, v) => [h('dt', k), h('dd', v)];
  return h('div',
    h('dl.kv',
      ...row('Format', fmt()?.label || state.format),
      ...row('Owner', state.owner),
      ...row('Name', state.name.trim()),
      ...row('URL', `#/decks/${p.slug}`),
      ...(wantsCommander() ? row('Commander', state.commander.trim()) : []),
      ...(wantsCommander() && state.bracket ? row('Bracket', state.bracket) : []),
      ...(state.theme.trim() ? row('Theme', state.theme.trim()) : []),
      ...(state.is_proxy ? row('Proxy', 'yes — nothing will be bought') : []),
      ...row('Cards', `${num(p.card_count)} across ${num(p.rows)} rows`)),

    p.transferred?.length
      ? h('div', { style: { marginTop: '14px' } },
        h('div.small',
          h('span.tag.info', 'moving collections'),
          ` these leave ${p.transferred[0][2]}'s collection and join ${state.owner}'s`),
        h('div.chips', { style: { marginTop: '6px' } },
          p.transferred.map(([n, q]) => h('span.chip.mini', `${q}× ${n}`))))
      : null,

    p.acquired?.length
      ? h('div', { style: { marginTop: '14px' } },
        h('div.small',
          h('span.tag.warn', 'added to the collection'),
          ' nothing spare covers these, so creating the deck records them as acquired'),
        h('div.chips', { style: { marginTop: '6px' } },
          p.acquired.map(([n, q]) => h('span.chip.mini.bad', `+${q} ${n}`))))
      : null,

    !p.acquired?.length && !p.transferred?.length
      ? h('div.small.muted', { style: { marginTop: '14px' } },
        'Every card comes out of bulk — nothing is bought and nothing moves.')
      : null);
}

// ------------------------------------------------------------------ shell

/** Re-enable Next without repainting the inputs and losing the caret. */
function refreshFooter() {
  const next = $('#wiz-next');
  const why = $('#wiz-why');
  if (!next) return;
  const b = blocker();
  next.disabled = Boolean(b) || state.busy;
  if (why) why.textContent = b || '';
}

function paint() {
  const root = $('#view');
  const list = steps();
  const last = state.step === list.length - 1;
  const b = blocker();

  fill(root, h('div.wrap',
    h('div.page-head',
      h('a.btn.sm', { href: '#/decks' }, '←'),
      h('h1', 'New deck'),
      h('span.sub', at().title)),

    h('div.steps', list.map((s, i) => h('button', {
      class: `step${i === state.step ? ' on' : ''}${i < state.step ? ' done' : ''}`,
      disabled: i >= state.step || state.busy,
      onclick: () => { state.step = i; state.plan = null; paint(); },
    }, h('span.step-n', i < state.step ? '✓' : String(i + 1)), s.title))),

    h('div.panel',
      h('div.panel-body',
        stepBody(),
        h('div.flex-wrap', { style: { marginTop: '18px' } },
          state.step > 0
            ? h('button.btn.ghost', { onclick: () => go(-1), disabled: state.busy }, '← Back')
            : h('a.btn.ghost', { href: '#/decks' }, 'Cancel'),
          last
            ? h('button.btn.primary', {
              id: 'wiz-next',
              disabled: state.busy || !state.plan,
              onclick: create,
            }, state.busy ? 'Working…' : 'Create deck')
            : h('button.btn.primary', {
              id: 'wiz-next',
              disabled: Boolean(b),
              onclick: () => go(1),
            }, 'Continue →'),
          h('span.small.says', { id: 'wiz-why' }, last ? '' : (b || '')))))));
}

export async function newDeckView() {
  state = blank();
  const root = $('#view');
  fill(root, h('div.wrap', loading('Loading formats')));
  try {
    if (!formats.length) formats = (await api.deckFormats()).formats;
  } catch (e) {
    fill(root, h('div.wrap', errorBox(e)));
    return;
  }
  paint();
}
