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
  state.plan = null;
  state.error = null;
  paint();
  if (at().id === 'review') dryRun();
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
      queueMicrotask(() => cmdr.focus());
      const sel = h('select',
        BRACKETS.map(([v, label]) => h('option', { value: v, selected: state.bracket === v }, label)));
      sel.addEventListener('change', () => { state.bracket = sel.value; });
      return h('div',
        field('Commander', 'Two names, one per line, if the deck runs partners.', cmdr),
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

    case 'review':
    default:
      return reviewBody();
  }
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

    p.acquired?.length
      ? h('div', { style: { marginTop: '14px' } },
        h('div.small',
          h('span.tag.warn', 'added to the collection'),
          ' bulk has no spare copy of these, so creating the deck records them as acquired'),
        h('div.chips', { style: { marginTop: '6px' } },
          p.acquired.map(([n, q]) => h('span.chip.mini.bad', `+${q} ${n}`))))
      : h('div.small.muted', { style: { marginTop: '14px' } },
        'Every card comes out of bulk — nothing needs to be acquired.'));
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
          h('span.small.muted', { id: 'wiz-why' }, last ? '' : (b || '')))))));
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
