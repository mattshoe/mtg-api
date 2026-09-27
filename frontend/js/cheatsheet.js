// The query box reference. Everything it understands, in one overlay.

import { h } from './util.js';
import { IS_VALUES } from './filters.js';

const KEYS = [
  ['Words', [
    ['bare word', 'name contains it', 'bolt'],
    ['name:', 'same, explicitly', 'name:"sol ring"'],
    ['o: oracle: text:', 'rules text contains', 'o:"draw a card"'],
    ['t: type:', 'type line contains', 't:creature  t:equipment'],
    ['ft: flavor:', 'flavour text', 'ft:goblin'],
    ['a: artist:', 'artist', 'a:"rebecca guay"'],
    ['wm: watermark:', 'watermark', 'wm:izzet'],
    ['m: mana:', 'mana cost contains', 'm:{G}{G}'],
  ]],
  ['Colour', [
    ['id<=wub', 'identity fits in these — what a commander allows', 'id<=wub'],
    ['id=wu', 'identity is exactly these', 'id=wu'],
    ['id>=wu', 'identity includes all of these', 'id>=wu'],
    ['id:wu', 'identity includes all of these (same as >=)', 'id:wu'],
    ['c<=r c=r c>=r', 'same four, on the printed colour instead', 'c=r'],
    ['produces:g', 'taps for this colour', 'produces:g'],
    ['is:colorless is:mono is:multicolor', 'shorthand', 'is:multicolor'],
  ]],
  ['Numbers', [
    ['mv: cmc:', 'mana value', 'mv<=3  mv=0  mv>5'],
    ['pow: tou: loy:', 'power, toughness, loyalty', 'pow>=5  tou<2'],
    ['qty:', 'copies of this printing owned', 'qty>=4'],
    ['free:', 'copies not committed to a deck', 'free>=1'],
    ['edhrec:', 'EDHREC rank, lower is more played', 'edhrec<=250'],
    ['year:', 'release year', 'year>=2023'],
  ]],
  ['Printing', [
    ['r: rarity:', 'rarity', 'r:mythic'],
    ['s: set: e:', 'set code', 's:mh3'],
    ['st: settype:', 'set type', 'st:commander  st:masters'],
    ['layout:', 'card layout', 'layout:saga'],
    ['cn:', 'collector number', 'cn:117'],
    ['game:', 'available in', 'game:paper'],
  ]],
  ['Oracle-level', [
    ['kw: keyword:', 'keyword ability', 'kw:flying'],
    ['tag:', 'Scryfall tag', 'tag:mana-rock'],
    ['f: format:', 'legal in a format', 'f:commander'],
    ['banned: restricted:', 'banned or restricted there', 'banned:commander'],
  ]],
  ['Collection', [
    ['owner:', 'matt or kayla', 'owner:kayla'],
    ['deck:', 'in this deck, by slug', 'deck:fairy-alela-faerie-tribal'],
    ['is:free', 'has an unassigned copy', 'is:free'],
    ['is:indeck', 'slotted into some deck', '-is:indeck'],
  ]],
];

export function cheatsheet() {
  const close = () => scrim.remove();

  const table = (rows) => h('table', h('tbody', rows.map(([key, what, eg]) => h('tr',
    h('td', { style: { whiteSpace: 'nowrap' } }, h('code', key)),
    h('td.small.muted', what),
    h('td', { style: { whiteSpace: 'nowrap' } },
      h('code.small', { style: { cursor: 'pointer', color: 'var(--accent-2)' }, title: 'Copy', onclick: () => navigator.clipboard?.writeText(eg) }, eg))))));

  const scrim = h('div.palette-scrim', {
    onclick: (e) => { if (e.target === scrim) close(); },
  }, h('div.palette', { style: { width: 'min(820px, 94vw)', maxHeight: '82vh', overflowY: 'auto' } },
    h('div.panel-head', { style: { position: 'sticky', top: '0', zIndex: '1' } },
      h('h2', 'Query box'),
      h('span.spacer'),
      h('button.btn.sm.ghost', { onclick: close }, 'Close')),
    h('div.panel-body.stack',
      h('div.small.muted',
        'Terms are ANDed. Put a minus in front to negate anything. '
        + 'Quote a phrase with spaces. Everything here also works alongside the panel on the left.'),
      KEYS.map(([title, rows]) => h('div',
        h('h3', { style: { margin: '10px 0 6px' } }, title),
        h('div.table-wrap', table(rows)))),
      h('div',
        h('h3', { style: { margin: '10px 0 6px' } }, `is: values (${IS_VALUES.length})`),
        h('div.chips', IS_VALUES.map((v) => h('span.chip.mini', v)))))));

  document.body.append(scrim);
  addEventListener('keydown', function esc(e) {
    if (e.key === 'Escape') { close(); removeEventListener('keydown', esc); }
  });
}
