// The filter model: every column in the database, one place that turns it
// into SQL, and the URL codec so a search is always a link.
//
// Two ways in — the facet panel and the query box — and both land here, so
// there is exactly one definition of what "at most these colours" means.

export const COLOR_LETTERS = ['W', 'U', 'B', 'R', 'G'];

/** Fixed. One page size, and the page never shows printings separately. */
export const PAGE_SIZE = 100;

export const DEFAULTS = {
  // --- who and how many
  owner: 'matt',
  qtyMin: '', qtyMax: '',
  pool: 'all',            // all | free | committed
  freeMin: '',
  deck: '',               // deck slug, or _any / _none
  finish: '',

  // --- words
  q: '',                  // name contains
  text: '',               // oracle text, full-text
  textLike: '',           // oracle text, literal substring
  flavor: '',
  artist: '',
  watermark: '',
  typeLine: '',           // raw type line contains

  // --- colour
  colorTarget: 'id',      // id = color_identity, card = printed colours
  colorMode: 'atmost',    // exactly | atmost | atleast | anyof
  colors: [],             // W U B R G C
  ciMin: '', ciMax: '',   // number of colours in the identity
  produces: [],           // produced_mana

  // --- mana and stats
  cmcMin: '', cmcMax: '',
  manaCost: '',
  powOp: '>=', pow: '',
  touOp: '>=', tou: '',
  loyOp: '>=', loy: '',

  // --- types
  types: [], typesNot: [],
  supertypes: [],
  subtypes: [],

  // --- printing
  rarities: [],
  sets: [],
  setTypes: [],
  layouts: [],
  frames: [],
  borders: [],
  games: [],
  yearMin: '', yearMax: '',
  collnum: '',

  // --- boolean flags: '' (any) | 'yes' | 'no'
  reserved: '', gameChanger: '', fullArt: '', textless: '', promo: '',
  reprint: '', storySpotlight: '', booster: '', oversized: '', variation: '',

  // --- price
  priceMin: '', priceMax: '',

  // --- oracle-level
  keywords: [], tags: [],
  format: '', legality: 'legal',
  edhrecMin: '', edhrecMax: '',
  hasRulings: '',

  // --- the query box, ANDed on top of everything above
  adv: '',

  // --- presentation
  sort: 'name', dir: 'asc', page: 1,
};

// Columns are qualified with `c.` because card_usage is joined in as `u`
// and shares several names — an unqualified name_norm is ambiguous.
// `qty` and `free` are output aliases, so they stay bare.
export const SORTS = {
  name: ['Name', 'c.name_norm'],
  cmc: ['Mana value', 'c.cmc'],
  qty: ['Quantity', 'qty'],
  free: ['Free copies', 'free'],
  edhrec: ['EDHREC rank', 'c.edhrec_rank'],
  released: ['Released', 'c.released_at'],
  rarity: ['Rarity', "instr('common uncommon rare mythic special bonus', c.rarity)"],
  set: ['Set', 'c.setcode'],
  power: ['Power', "CASE WHEN c.power GLOB '[0-9]*' THEN CAST(c.power AS INTEGER) END"],
  toughness: ['Toughness', "CASE WHEN c.toughness GLOB '[0-9]*' THEN CAST(c.toughness AS INTEGER) END"],
  color: ['Colour identity', 'c.color_identity_count, c.color_identity'],
  artist: ['Artist', 'c.artist'],
  // Prices are not in the database, so this one is sorted client-side
  // after a lookup. The column is a placeholder for the SQL path.
  price: ['Price', 'price'],
  value: ['Stack value', 'value'],
};

export const COLOR_MODES = [
  ['exactly', 'Exactly', 'these colours and no others'],
  ['atmost', 'At most', 'nothing outside these colours — what fits a commander'],
  ['atleast', 'At least', 'all of these, others allowed'],
  ['anyof', 'Any of', 'at least one of these'],
];

export const FLAGS = [
  ['reserved', 'Reserved list'],
  ['gameChanger', 'Game Changer'],
  ['fullArt', 'Full art'],
  ['textless', 'Textless'],
  ['promo', 'Promo'],
  ['reprint', 'Reprint'],
  ['storySpotlight', 'Story spotlight'],
  ['booster', 'In boosters'],
  ['oversized', 'Oversized'],
  ['variation', 'Variation'],
];

const FLAG_COLUMN = {
  reserved: 'reserved',
  gameChanger: 'game_changer',
  fullArt: 'full_art',
  textless: 'textless',
  promo: 'promo',
  reprint: 'reprint',
  storySpotlight: 'story_spotlight',
  booster: 'booster',
  oversized: 'oversized',
  variation: 'variation',
};

const NUM_OPS = new Set(['>=', '<=', '=', '>', '<', '!=']);

/**
 * Colour strings in this database are stored ALPHABETICALLY, not in WUBRG
 * order — 'UW' for Azorius, 'BG' for Golgari. Verified against every
 * multicolour row: none deviates. An exact match has to sort the same way
 * or it silently matches nothing.
 */
const sortedColors = (list) => COLOR_LETTERS.filter((c) => list.includes(c)).sort().join('');

/**
 * The colour clause. This is the part that matters most for Commander and
 * the part that has to be unambiguous, so it lives in one function used by
 * both the panel and the query box.
 *
 *   exactly  identity is precisely these
 *   atmost   nothing outside these — a card that fits the commander
 *   atleast  all of these, others allowed
 *   anyof    at least one of these
 *
 * Colourless (C) means an empty colour string, and is handled per mode
 * rather than pretended to be a sixth colour.
 */
export function colorClause(column, mode, selected, where, params) {
  const chosen = selected.filter((c) => COLOR_LETTERS.includes(c));
  const hasColorless = selected.includes('C');
  if (!chosen.length && !hasColorless) return;

  const col = `COALESCE(${column}, '')`;

  if (mode === 'exactly') {
    if (!chosen.length) { where.push(`${col} = ''`); return; }
    where.push(`${col} = ?`);
    params.push(sortedColors(chosen));
    return;
  }

  if (mode === 'atleast') {
    if (!chosen.length) { where.push(`${col} = ''`); return; }
    for (const c of chosen) { where.push(`${col} LIKE ?`); params.push(`%${c}%`); }
    return;
  }

  if (mode === 'anyof') {
    const ors = [];
    for (const c of chosen) { ors.push(`${col} LIKE ?`); params.push(`%${c}%`); }
    if (hasColorless) ors.push(`${col} = ''`);
    where.push(`(${ors.join(' OR ')})`);
    return;
  }

  // atmost: nothing outside the chosen set. A colourless card always fits,
  // which is why "at most WU" correctly returns Sol Ring.
  for (const c of COLOR_LETTERS) {
    if (!chosen.includes(c)) { where.push(`${col} NOT LIKE ?`); params.push(`%${c}%`); }
  }
  if (hasColorless && !chosen.length) where.push(`${col} = ''`);
}

function numeric(column, op, value, where, params) {
  if (value === '' || value === null || value === undefined) return;
  const n = Number(value);
  if (!Number.isFinite(n)) return;
  where.push(`${column} ${NUM_OPS.has(op) ? op : '>='} ?`);
  params.push(n);
}

/** Power and toughness are text: '*', '1+*', '3'. Only compare real numbers. */
function ptNumeric(column, op, value, where, params) {
  if (value === '') return;
  const n = Number(value);
  if (!Number.isFinite(n)) return;
  where.push(`${column} GLOB '[0-9]*' AND CAST(${column} AS INTEGER) ${NUM_OPS.has(op) ? op : '>='} ?`);
  params.push(n);
}

const inList = (column, values, where, params) => {
  if (!values.length) return;
  where.push(`${column} IN (${values.map(() => '?').join(',')})`);
  params.push(...values);
};

const exists = (sql, where, params, args) => { where.push(sql); params.push(...args); };

/**
 * Free copies come from the card_usage view, joined once rather than
 * correlated per row. As a subquery it re-evaluated card_usage — itself a
 * view over totals plus a grouped deck_cards join — for every candidate
 * row, which blew D1's CPU limit outright on a whole-collection query.
 * The join is 1:1 on (owner, name_norm), so it cannot fan rows out.
 */
export const USAGE_JOIN = 'LEFT JOIN card_usage u ON u.owner = c.owner AND u.name_norm = c.name_norm';
export const USAGE_FREE = 'u.free';

/**
 * Prices live in their own table now, refreshed by the daily job, so the
 * price that applies to a stack is a plain SQL expression — which means
 * sorting and filtering by it happen in the database instead of by pulling
 * the whole result set into the browser.
 */
export const PRICE_JOIN = 'LEFT JOIN prices pr ON pr.scryfall_id = c.scryfall_id';
export const PRICE_EXPR = `CASE c.finish
         WHEN 'foil'   THEN COALESCE(pr.usd_foil, pr.usd)
         WHEN 'etched' THEN COALESCE(pr.usd_etched, pr.usd_foil, pr.usd)
         ELSE pr.usd END`;

/**
 * State -> { where: [...], params: [...] }.
 * Everything the user typed is bound; only identifiers this file owns are
 * ever interpolated.
 */
export function conditions(s, { advError } = {}) {
  const where = [];
  const p = [];
  const like = (v) => `%${String(v).toLowerCase()}%`;

  if (s.owner && s.owner !== 'both') { where.push('c.owner = ?'); p.push(s.owner); }

  if (s.q.trim()) {
    where.push('(c.name_norm LIKE ? OR lower(c.face1) LIKE ? OR lower(c.face2) LIKE ?)');
    p.push(like(s.q.trim()), like(s.q.trim()), like(s.q.trim()));
  }
  if (s.text.trim()) {
    where.push('c.id IN (SELECT rowid FROM card_search WHERE card_search MATCH ?)');
    p.push(s.text.trim());
  }
  if (s.textLike.trim()) { where.push('lower(c.oracle_text) LIKE ?'); p.push(like(s.textLike.trim())); }
  if (s.flavor.trim()) { where.push('lower(c.flavor_text) LIKE ?'); p.push(like(s.flavor.trim())); }
  if (s.artist.trim()) { where.push('lower(c.artist) LIKE ?'); p.push(like(s.artist.trim())); }
  if (s.watermark.trim()) { where.push('lower(c.watermark) LIKE ?'); p.push(like(s.watermark.trim())); }
  if (s.typeLine.trim()) { where.push('lower(c.type_line) LIKE ?'); p.push(like(s.typeLine.trim())); }
  if (s.manaCost.trim()) { where.push('replace(c.mana_cost, \' \', \'\') LIKE ?'); p.push(like(s.manaCost.replace(/\s/g, ''))); }
  if (s.collnum.trim()) { where.push('c.collector_number = ?'); p.push(s.collnum.trim()); }

  colorClause(s.colorTarget === 'card' ? 'c.colors' : 'c.color_identity', s.colorMode, s.colors, where, p);
  numeric('c.color_identity_count', '>=', s.ciMin, where, p);
  numeric('c.color_identity_count', '<=', s.ciMax, where, p);
  for (const m of s.produces) { where.push('c.produced_mana LIKE ?'); p.push(`%${m}%`); }

  numeric('c.cmc', '>=', s.cmcMin, where, p);
  numeric('c.cmc', '<=', s.cmcMax, where, p);
  ptNumeric('c.power', s.powOp, s.pow, where, p);
  ptNumeric('c.toughness', s.touOp, s.tou, where, p);
  ptNumeric('c.loyalty', s.loyOp, s.loy, where, p);

  numeric('c.qty', '>=', s.qtyMin, where, p);
  numeric('c.qty', '<=', s.qtyMax, where, p);
  numeric('c.edhrec_rank', '>=', s.edhrecMin, where, p);
  numeric('c.edhrec_rank', '<=', s.edhrecMax, where, p);

  for (const t of s.types) {
    exists("EXISTS (SELECT 1 FROM card_types ct WHERE ct.card_id = c.id AND ct.kind = 'type' AND ct.type = ?)", where, p, [t]);
  }
  for (const t of s.typesNot) {
    exists("NOT EXISTS (SELECT 1 FROM card_types ct WHERE ct.card_id = c.id AND ct.kind = 'type' AND ct.type = ?)", where, p, [t]);
  }
  for (const t of s.supertypes) {
    exists("EXISTS (SELECT 1 FROM card_types ct WHERE ct.card_id = c.id AND ct.kind = 'supertype' AND ct.type = ?)", where, p, [t]);
  }
  for (const t of s.subtypes) {
    exists("EXISTS (SELECT 1 FROM card_types ct WHERE ct.card_id = c.id AND ct.kind = 'subtype' AND lower(ct.type) = ?)", where, p, [String(t).toLowerCase()]);
  }
  for (const k of s.keywords) {
    exists('EXISTS (SELECT 1 FROM card_keywords k WHERE k.card_id = c.id AND lower(k.keyword) = ?)', where, p, [String(k).toLowerCase()]);
  }
  for (const t of s.tags) {
    exists('EXISTS (SELECT 1 FROM card_tags ct WHERE ct.card_id = c.id AND ct.tag_slug = ?)', where, p, [t]);
  }

  inList('c.rarity', s.rarities, where, p);
  inList('lower(c.setcode)', s.sets.map((x) => x.toLowerCase()), where, p);
  inList('c.set_type', s.setTypes, where, p);
  inList('c.layout', s.layouts, where, p);
  inList('c.frame', s.frames, where, p);
  inList('c.border_color', s.borders, where, p);
  if (s.finish) { where.push('c.finish = ?'); p.push(s.finish); }
  for (const g of s.games) {
    exists('EXISTS (SELECT 1 FROM card_games g WHERE g.card_id = c.id AND g.game = ?)', where, p, [g]);
  }

  if (s.yearMin !== '') { where.push('c.released_at >= ?'); p.push(`${s.yearMin}-01-01`); }
  if (s.yearMax !== '') { where.push('c.released_at <= ?'); p.push(`${s.yearMax}-12-31`); }

  for (const [key, column] of Object.entries(FLAG_COLUMN)) {
    if (s[key] === 'yes') where.push(`c.${column} = 1`);
    else if (s[key] === 'no') where.push(`COALESCE(c.${column}, 0) = 0`);
  }

  if (s.format) {
    if (s.legality === 'not_legal') {
      exists('NOT EXISTS (SELECT 1 FROM legalities l WHERE l.oracle_id = c.oracle_id AND l.format = ?)', where, p, [s.format]);
    } else {
      exists('EXISTS (SELECT 1 FROM legalities l WHERE l.oracle_id = c.oracle_id AND l.format = ? AND l.status = ?)', where, p, [s.format, s.legality]);
    }
  }
  if (s.hasRulings === 'yes') where.push('EXISTS (SELECT 1 FROM rulings r WHERE r.oracle_id = c.oracle_id)');
  if (s.hasRulings === 'no') where.push('NOT EXISTS (SELECT 1 FROM rulings r WHERE r.oracle_id = c.oracle_id)');

  if (s.priceMin !== '') { where.push(`(${PRICE_EXPR}) >= ?`); p.push(Number(s.priceMin)); }
  if (s.priceMax !== '') { where.push(`(${PRICE_EXPR}) <= ?`); p.push(Number(s.priceMax)); }

  if (s.pool === 'free') where.push('COALESCE(u.free, 0) > 0');
  if (s.pool === 'committed') where.push('COALESCE(u.free, 0) <= 0');
  numeric('COALESCE(u.free, 0)', '>=', s.freeMin, where, p);

  if (s.deck === '_any') {
    where.push('EXISTS (SELECT 1 FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id WHERE dc.name_norm = c.name_norm AND d.owner = c.owner)');
  } else if (s.deck === '_none') {
    where.push('NOT EXISTS (SELECT 1 FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id WHERE dc.name_norm = c.name_norm AND d.owner = c.owner)');
  } else if (s.deck) {
    exists('EXISTS (SELECT 1 FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id WHERE dc.name_norm = c.name_norm AND d.slug = ?)', where, p, [s.deck]);
  }

  // The query box last, so its clauses read after the structured ones.
  if (s.adv && s.adv.trim()) {
    try {
      const adv = parseAdvanced(s.adv);
      where.push(...adv.where);
      p.push(...adv.params);
    } catch (e) {
      if (advError) advError(e.message);
      else throw e;
    }
  }

  return { where, params: p };
}

// ------------------------------------------------------------ query box

const IS_SHAPES = {
  // layout and shape
  dfc: "c.layout IN ('transform','modal_dfc','reversible_card','double_faced_token')",
  transform: "c.layout = 'transform'",
  mdfc: "c.layout = 'modal_dfc'",
  split: "c.layout = 'split'",
  adventure: "c.layout = 'adventure'",
  saga: "c.layout = 'saga'",
  token: "c.layout IN ('token','double_faced_token')",
  // broad type buckets
  land: "c.type_line LIKE '%Land%'",
  creature: "c.type_line LIKE '%Creature%'",
  artifact: "c.type_line LIKE '%Artifact%'",
  enchantment: "c.type_line LIKE '%Enchantment%'",
  instant: "c.type_line LIKE '%Instant%'",
  sorcery: "c.type_line LIKE '%Sorcery%'",
  planeswalker: "c.type_line LIKE '%Planeswalker%'",
  battle: "c.type_line LIKE '%Battle%'",
  legendary: "c.type_line LIKE '%Legendary%'",
  basic: "c.type_line LIKE '%Basic%'",
  snow: "c.type_line LIKE '%Snow%'",
  permanent: "(c.type_line LIKE '%Creature%' OR c.type_line LIKE '%Artifact%' OR c.type_line LIKE '%Enchantment%' OR c.type_line LIKE '%Land%' OR c.type_line LIKE '%Planeswalker%' OR c.type_line LIKE '%Battle%')",
  spell: "(c.type_line LIKE '%Instant%' OR c.type_line LIKE '%Sorcery%')",
  commander: "(c.type_line LIKE '%Legendary%' AND c.type_line LIKE '%Creature%')",
  vanilla: "(c.oracle_text IS NULL OR c.oracle_text = '')",
  // colour shorthands
  colorless: "COALESCE(c.color_identity,'') = ''",
  multicolor: 'c.color_identity_count > 1',
  gold: 'c.color_identity_count > 1',
  mono: 'c.color_identity_count = 1',
  // finish
  foil: "c.finish = 'foil'",
  nonfoil: "c.finish = 'nonfoil'",
  etched: "c.finish = 'etched'",
  // flags
  reserved: 'c.reserved = 1',
  gamechanger: 'c.game_changer = 1',
  gc: 'c.game_changer = 1',
  fullart: 'c.full_art = 1',
  textless: 'c.textless = 1',
  promo: 'c.promo = 1',
  reprint: 'c.reprint = 1',
  firstprint: 'COALESCE(c.reprint, 0) = 0',
  storyspotlight: 'c.story_spotlight = 1',
  booster: 'c.booster = 1',
  oversized: 'c.oversized = 1',
  // collection
  free: 'COALESCE(u.free, 0) > 0',
  indeck: 'EXISTS (SELECT 1 FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id WHERE dc.name_norm = c.name_norm AND d.owner = c.owner)',
  hasrulings: 'EXISTS (SELECT 1 FROM rulings r WHERE r.oracle_id = c.oracle_id)',
  priced: 'pr.usd IS NOT NULL',
  unpriced: 'pr.usd IS NULL',
};

export const IS_VALUES = Object.keys(IS_SHAPES);

const ALIASES = {
  n: 'name', name: 'name',
  o: 'oracle', oracle: 'oracle', text: 'oracle',
  t: 'type', type: 'type',
  c: 'color', color: 'color', colors: 'color',
  id: 'identity', identity: 'identity', ci: 'identity',
  mv: 'mv', cmc: 'mv',
  pow: 'pow', power: 'pow',
  tou: 'tou', toughness: 'tou',
  loy: 'loy', loyalty: 'loy',
  r: 'rarity', rarity: 'rarity',
  s: 'set', set: 'set', e: 'set', edition: 'set',
  st: 'settype', settype: 'settype',
  a: 'artist', artist: 'artist',
  kw: 'keyword', keyword: 'keyword',
  tag: 'tag', otag: 'tag',
  f: 'format', format: 'format', legal: 'format',
  banned: 'banned', restricted: 'restricted',
  is: 'is', not: 'not',
  qty: 'qty', have: 'qty',
  free: 'free',
  usd: 'price', price: 'price',
  deck: 'deck',
  owner: 'owner',
  year: 'year',
  produces: 'produces', prod: 'produces',
  layout: 'layout',
  game: 'game',
  ft: 'flavor', flavor: 'flavor',
  wm: 'watermark', watermark: 'watermark',
  cn: 'cn', number: 'cn',
  edhrec: 'edhrec', rank: 'edhrec',
  m: 'manacost', mana: 'manacost',
};

/** Split on whitespace, keeping quoted runs together. */
function tokenize(input) {
  const out = [];
  const re = /(-?)([A-Za-z]+)(>=|<=|!=|[:=<>])("([^"]*)"|'([^']*)'|[^\s]*)|(-?)"([^"]*)"|(-?)(\S+)/g;
  let m;
  while ((m = re.exec(input)) !== null) {
    if (m[2]) {
      out.push({
        neg: m[1] === '-',
        key: m[2].toLowerCase(),
        op: m[3],
        value: m[5] ?? m[6] ?? m[4] ?? '',
      });
    } else if (m[8] !== undefined) {
      out.push({ neg: m[7] === '-', bare: m[8] });
    } else if (m[10]) {
      out.push({ neg: m[9] === '-', bare: m[10] });
    }
  }
  return out;
}

const CMP = { ':': '>=', '=': '=', '>=': '>=', '<=': '<=', '>': '>', '<': '<', '!=': '!=' };

/**
 * The query box. Scryfall-ish on purpose — it is the syntax Magic players
 * already have in their fingers.
 *
 *   c<=wu  t:creature  mv<=3  pow>=4  -is:reprint  o:"draw a card"
 */
export function parseAdvanced(input) {
  const where = [];
  const params = [];
  const unknown = [];

  const push = (sql, args = [], neg = false) => {
    where.push(neg ? `NOT (${sql})` : sql);
    params.push(...args);
  };
  const likeArg = (v) => `%${String(v).toLowerCase()}%`;

  for (const tok of tokenize(input)) {
    if (tok.bare !== undefined) {
      if (!tok.bare.trim()) continue;
      push('(c.name_norm LIKE ? OR lower(c.face1) LIKE ? OR lower(c.face2) LIKE ?)',
        [likeArg(tok.bare), likeArg(tok.bare), likeArg(tok.bare)], tok.neg);
      continue;
    }

    const key = ALIASES[tok.key];
    const v = tok.value;
    const op = CMP[tok.op] || '>=';

    if (!key) { unknown.push(tok.key); continue; }

    switch (key) {
      case 'name':
        push('(c.name_norm LIKE ? OR lower(c.face1) LIKE ? OR lower(c.face2) LIKE ?)',
          [likeArg(v), likeArg(v), likeArg(v)], tok.neg);
        break;
      case 'oracle':
        push('lower(c.oracle_text) LIKE ?', [likeArg(v)], tok.neg);
        break;
      case 'flavor':
        push('lower(c.flavor_text) LIKE ?', [likeArg(v)], tok.neg);
        break;
      case 'type':
        push('lower(c.type_line) LIKE ?', [likeArg(v)], tok.neg);
        break;
      case 'manacost':
        push("replace(c.mana_cost, ' ', '') LIKE ?", [likeArg(v.replace(/\s/g, ''))], tok.neg);
        break;
      case 'color':
      case 'identity': {
        const column = key === 'color' ? 'c.colors' : 'c.color_identity';
        // `c:wu` means "any of" the way Scryfall reads it; the comparison
        // operators carry the precise meanings.
        const mode = { ':': 'atleast', '=': 'exactly', '<=': 'atmost', '<': 'atmost', '>=': 'atleast', '>': 'atleast' }[tok.op] || 'atleast';
        const letters = v.toUpperCase().split('').filter((x) => 'WUBRGC'.includes(x));
        const sub = []; const subP = [];
        colorClause(column, mode, letters, sub, subP);
        if (sub.length) push(sub.join(' AND '), subP, tok.neg);
        break;
      }
      case 'produces':
        for (const ch of v.toUpperCase().split('').filter((x) => 'WUBRGC'.includes(x))) {
          push('c.produced_mana LIKE ?', [`%${ch}%`], tok.neg);
        }
        break;
      case 'mv': push(`c.cmc ${op} ?`, [Number(v)], tok.neg); break;
      case 'pow': push(`(c.power GLOB '[0-9]*' AND CAST(c.power AS INTEGER) ${op} ?)`, [Number(v)], tok.neg); break;
      case 'tou': push(`(c.toughness GLOB '[0-9]*' AND CAST(c.toughness AS INTEGER) ${op} ?)`, [Number(v)], tok.neg); break;
      case 'loy': push(`(c.loyalty GLOB '[0-9]*' AND CAST(c.loyalty AS INTEGER) ${op} ?)`, [Number(v)], tok.neg); break;
      case 'qty': push(`c.qty ${op} ?`, [Number(v)], tok.neg); break;
      case 'free': push(`COALESCE(u.free, 0) ${op} ?`, [Number(v)], tok.neg); break;
      case 'price': push(`(${PRICE_EXPR}) ${op} ?`, [Number(v)], tok.neg); break;
      case 'edhrec': push(`c.edhrec_rank ${tok.op === ':' ? '<=' : op} ?`, [Number(v)], tok.neg); break;
      case 'year': push(`substr(c.released_at, 1, 4) ${tok.op === ':' ? '=' : op} ?`, [String(Number(v))], tok.neg); break;
      case 'rarity': push('c.rarity = ?', [v.toLowerCase()], tok.neg); break;
      case 'set': push('lower(c.setcode) = ?', [v.toLowerCase()], tok.neg); break;
      case 'settype': push('c.set_type = ?', [v.toLowerCase()], tok.neg); break;
      case 'layout': push('c.layout = ?', [v.toLowerCase()], tok.neg); break;
      case 'cn': push('c.collector_number = ?', [v], tok.neg); break;
      case 'artist': push('lower(c.artist) LIKE ?', [likeArg(v)], tok.neg); break;
      case 'watermark': push('lower(c.watermark) LIKE ?', [likeArg(v)], tok.neg); break;
      case 'owner': push('c.owner = ?', [v.toLowerCase()], tok.neg); break;
      case 'game':
        push('EXISTS (SELECT 1 FROM card_games g WHERE g.card_id = c.id AND g.game = ?)', [v.toLowerCase()], tok.neg);
        break;
      case 'keyword':
        push('EXISTS (SELECT 1 FROM card_keywords k WHERE k.card_id = c.id AND lower(k.keyword) = ?)', [v.toLowerCase()], tok.neg);
        break;
      case 'tag':
        push('EXISTS (SELECT 1 FROM card_tags ct WHERE ct.card_id = c.id AND ct.tag_slug = ?)', [v.toLowerCase()], tok.neg);
        break;
      case 'format':
        push("EXISTS (SELECT 1 FROM legalities l WHERE l.oracle_id = c.oracle_id AND l.format = ? AND l.status = 'legal')", [v.toLowerCase()], tok.neg);
        break;
      case 'banned':
        push("EXISTS (SELECT 1 FROM legalities l WHERE l.oracle_id = c.oracle_id AND l.format = ? AND l.status = 'banned')", [v.toLowerCase()], tok.neg);
        break;
      case 'restricted':
        push("EXISTS (SELECT 1 FROM legalities l WHERE l.oracle_id = c.oracle_id AND l.format = ? AND l.status = 'restricted')", [v.toLowerCase()], tok.neg);
        break;
      case 'deck':
        push('EXISTS (SELECT 1 FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id WHERE dc.name_norm = c.name_norm AND d.slug = ?)', [v.toLowerCase()], tok.neg);
        break;
      case 'is':
      case 'not': {
        const shape = IS_SHAPES[v.toLowerCase()];
        if (!shape) { unknown.push(`${tok.key}:${v}`); break; }
        push(shape, [], key === 'not' ? !tok.neg : tok.neg);
        break;
      }
      default:
        unknown.push(tok.key);
    }
  }

  if (unknown.length) {
    throw new Error(`don't know "${[...new Set(unknown)].join('", "')}" — try the cheatsheet`);
  }
  return { where, params };
}

// ---------------------------------------------------------------- SQL

const SELECT_COLS = `c.owner, c.name, c.name_norm, c.face2, c.layout,
       c.scryfall_id, c.mana_cost, c.cmc, c.type_line,
       c.color_identity, c.rarity, c.setcode, c.set_name, c.collector_number,
       c.edhrec_rank, c.released_at, c.finish, c.power, c.toughness, c.artist,
       pr.tcg_url, pr.updated_at AS priced_at`;

export function buildQuery(s, { countOnly = false, advError } = {}) {
  const { where, params } = conditions(s, { advError });
  const clause = where.length ? `WHERE ${where.join('\n  AND ')}` : '';

  if (countOnly) {
    const inner = `SELECT 1 FROM cards c ${USAGE_JOIN} ${PRICE_JOIN} ${clause} GROUP BY c.owner, c.name_norm`;
    return { sql: `SELECT COUNT(*) FROM (${inner})`, params };
  }

  const [, sortCol] = SORTS[s.sort] || SORTS.name;
  const dir = s.dir === 'desc' ? 'DESC' : 'ASC';
  const order = `ORDER BY (${sortCol}) IS NULL, (${sortCol}) ${dir}, c.name_norm ASC`;
  const size = s.size || PAGE_SIZE;
  const offset = (s.page - 1) * size;

  // One row per card, never one per printing. MIN(c.id) makes SQLite take
  // the other bare columns from that same row, which is the representative
  // printing we show.
  const select = `SELECT MIN(c.id) AS id, ${SELECT_COLS},
              SUM(c.qty) AS qty, COUNT(*) AS printings, u.free AS free,
              (${PRICE_EXPR}) AS price,
              ROUND(SUM(c.qty * (${PRICE_EXPR})), 2) AS value`;

  return {
    sql: `${select}\nFROM cards c\n${USAGE_JOIN}\n${PRICE_JOIN}\n${clause}\nGROUP BY c.owner, c.name_norm\n${order}\nLIMIT ${size} OFFSET ${offset}`,
    params,
  };
}

// ---------------------------------------------------------------- URL

export function toHash(s) {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(s)) {
    const d = DEFAULTS[k];
    if (d === undefined) continue;
    const same = Array.isArray(v) ? v.join(',') === d.join(',') : v === d;
    if (same) continue;
    q.set(k, Array.isArray(v) ? v.join(',') : String(v));
  }
  const str = q.toString();
  return `#/search${str ? `?${str}` : ''}`;
}

export function fromHash(queryString) {
  const s = { ...DEFAULTS };
  for (const [k, v] of new URLSearchParams(queryString || '')) {
    if (!(k in DEFAULTS)) continue;
    if (Array.isArray(DEFAULTS[k])) s[k] = v ? v.split(',').filter(Boolean) : [];
    else if (typeof DEFAULTS[k] === 'number') s[k] = Number(v) || DEFAULTS[k];
    else if (typeof DEFAULTS[k] === 'boolean') s[k] = v === 'true';
    else s[k] = v;
  }
  return s;
}

/** How many real filters are on, for the "N active" badge and Reset. */
export function activeCount(s) {
  const skip = new Set(['page', 'sort', 'dir', 'colorMode', 'colorTarget',
    'powOp', 'touOp', 'loyOp', 'legality']);
  return Object.keys(DEFAULTS).filter((k) => {
    if (skip.has(k)) return false;
    const v = s[k]; const d = DEFAULTS[k];
    return Array.isArray(v) ? v.length !== d.length : v !== d;
  }).length;
}
