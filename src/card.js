// Turning a Scryfall card object into database rows.
//
// A direct port of build_collection_db.py's enrichment loop. The column order
// and the derivations are deliberately identical, so a row written here is
// indistinguishable from a row the old builder would have written.

import { normalize } from './parse.js';

const TYPE_SUPER = new Set(['Basic', 'Legendary', 'Ongoing', 'Snow', 'World', 'Host', 'Elite']);

/** 'Legendary Creature — Elf Druid' -> [supers, types, subs] */
export function splitTypeLine(typeLine) {
  if (!typeLine) return [[], [], []];
  let idx = typeLine.indexOf('—'); // em dash, what Scryfall actually uses
  if (idx === -1) idx = typeLine.indexOf('-');
  const left = idx === -1 ? typeLine : typeLine.slice(0, idx);
  const right = idx === -1 ? '' : typeLine.slice(idx + 1);

  const supers = [];
  const types = [];
  for (const w of left.split(/\s+/).filter(Boolean)) {
    (TYPE_SUPER.has(w) ? supers : types).push(w);
  }
  return [supers, types, right.split(/\s+/).filter(Boolean)];
}

const bool = (v) => (v ? 1 : 0);
const joined = (arr) => (arr && arr.length ? arr.join('') : null);
const words = (arr) => (arr && arr.length ? arr.join(' ') : null);

/**
 * Reversible cards and a few other multi-face layouts carry no top-level
 * oracle_id / type_line / mana cost — Scryfall puts those on the faces. Without
 * this fallback such a card joins to nothing in legalities, rulings or tags,
 * and reads as "not legendary" to deckcheck.
 */
function withFaceFallbacks(card) {
  const faces = card.card_faces || [];
  if (!faces.length) return card;
  const out = { ...card };
  for (const key of ['oracle_id', 'mana_cost', 'cmc', 'type_line', 'power',
    'toughness', 'loyalty', 'defense', 'colors']) {
    if (out[key] === undefined || out[key] === null) {
      const v = faces[0][key];
      if (v !== undefined && v !== null) out[key] = v;
    }
  }
  return out;
}

/** Column order for `cards`, minus the autoincrement id. */
export const CARD_COLUMNS = [
  'owner', 'qty', 'finish', 'foil_flag', 'scryfall_id', 'oracle_id',
  'name', 'name_norm', 'face1', 'face2', 'mana_cost', 'cmc',
  'oracle_text', 'flavor_text', 'power', 'toughness', 'loyalty', 'defense',
  'type_line', 'supertypes', 'types', 'subtypes',
  'colors', 'color_identity', 'color_identity_count', 'produced_mana',
  'rarity', 'setcode', 'set_name', 'set_type', 'released_at',
  'collector_number', 'artist', 'layout', 'frame', 'border_color',
  'watermark', 'security_stamp',
  'reserved', 'game_changer', 'full_art', 'textless', 'promo', 'reprint',
  'variation', 'oversized', 'story_spotlight', 'booster', 'edhrec_rank',
];

/**
 * Everything a card contributes to the database, ready to bind.
 * @param {object} raw   a Scryfall card object
 * @param {object} opts  { owner, qty, finish, flag }
 */
export function cardRows(raw, { owner, qty, finish, flag }) {
  const s = withFaceFallbacks(raw);
  const faces = s.card_faces || [];
  const [supers, types, subs] = splitTypeLine(s.type_line || '');

  // Front and back oracle text joined, so single-column search still works.
  const fullText = s.oracle_text
    ?? (faces.length ? faces.map((f) => f.oracle_text || '').join('\n//\n') : null);

  const card = [
    owner, qty, finish, flag,
    s.id, s.oracle_id ?? null,
    s.name, normalize(s.name),
    faces.length ? faces[0].name : s.name,
    faces.length > 1 ? faces[1].name : null,
    s.mana_cost ?? null, s.cmc ?? null,
    fullText, s.flavor_text ?? null,
    s.power ?? null, s.toughness ?? null, s.loyalty ?? null, s.defense ?? null,
    s.type_line ?? null, words(supers), words(types), words(subs),
    joined(s.colors), joined(s.color_identity), (s.color_identity || []).length,
    joined(s.produced_mana),
    s.rarity ?? null, s.set ?? null, s.set_name ?? null, s.set_type ?? null,
    s.released_at ?? null, s.collector_number ?? null, s.artist ?? null,
    s.layout ?? null, s.frame ?? null, s.border_color ?? null,
    s.watermark ?? null, s.security_stamp ?? null,
    bool(s.reserved), bool(s.game_changer), bool(s.full_art), bool(s.textless),
    bool(s.promo), bool(s.reprint), bool(s.variation), bool(s.oversized),
    bool(s.story_spotlight), bool(s.booster), s.edhrec_rank ?? null,
  ];

  const keywords = (s.keywords || []).map((k) => [k]);

  const colors = [
    ...(s.colors || []).map((c) => [c, 'color']),
    ...(s.color_identity || []).map((c) => [c, 'identity']),
    ...(s.produced_mana || []).map((c) => [c, 'produced']),
  ];

  const typeRows = [
    ...supers.map((t) => [t, 'supertype']),
    ...types.map((t) => [t, 'type']),
    ...subs.map((t) => [t, 'subtype']),
  ];

  const cardFaces = faces.map((f, i) => [
    i, f.name ?? null, f.mana_cost ?? null, f.type_line ?? null,
    f.oracle_text ?? null, f.flavor_text ?? null, f.power ?? null,
    f.toughness ?? null, f.loyalty ?? null, f.defense ?? null,
    f.artist ?? null, joined(f.colors),
  ]);

  // "not_legal" is the default; absence means not legal. Storing only
  // meaningful statuses drops ~16k rows with no loss of information.
  const legalities = Object.entries(s.legalities || {})
    .filter(([, status]) => status !== 'not_legal')
    .map(([fmt, status]) => [fmt, status]);

  return {
    oracleId: s.oracle_id ?? null,
    scryfallId: s.id,
    name: s.name,
    setcode: s.set ?? null,
    collectorNumber: s.collector_number ?? null,
    card,
    keywords,
    colors,
    types: typeRows,
    finishes: (s.finishes || []).map((f) => [f]),
    games: (s.games || []).map((g) => [g]),
    promoTypes: (s.promo_types || []).map((p) => [p]),
    frameEffects: (s.frame_effects || []).map((f) => [f]),
    faces: cardFaces,
    legalities,
    // What the FTS row gets. Tags are appended later, if the card has any.
    search: {
      name: s.name,
      type_line: s.type_line ?? null,
      oracle_text: fullText,
      flavor_text: s.flavor_text ?? null,
      keywords: (s.keywords || []).join(' ') || null,
    },
  };
}
