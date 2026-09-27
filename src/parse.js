// Decklist parsing.
//
// Takes whatever an exporter actually produces, not just the tidy shape.
// Text decklists from MTGA, Moxfield, Archidekt, MTGO and ManaBox:
//
//   Sol Ring
//   4 Sol Ring
//   4x Sol Ring
//   1 Sol Ring (M3C) 409
//   1 Sol Ring (M3C) 409 *F*
//   1 Sol Ring (M3C) 409 foil
//   1x Sol Ring (m3c) 409 [Ramp]        Archidekt category
//   SB: 2 Negate                        MTGO sideboard prefix
//
// ...plus the section headers those files are full of ("Deck",
// "Sideboard", "Commander"), which are not cards and must not be sent to
// Scryfall as if they were.
//
// And CSV, which is what ManaBox, Moxfield and Deckbox export by default.
// Columns are matched by name rather than position, because every one of
// them orders and spells them differently.
//
// A bare trailing number only counts as a collector number when a set was
// given, otherwise "Fear of Missing Out 2" would lose its 2.

const LINE = new RegExp(
  '^\\s*' +
  '(?:(\\d+)\\s*[xX]?\\s+)?' +          // 1 qty
  '(.+?)' +                              // 2 name
  '(?:\\s+\\(([A-Za-z0-9]{2,6})\\))?' +  // 3 set
  '(?:\\s+([A-Za-z0-9][A-Za-z0-9\\-★☆*]*))?' + // 4 collector number
  '(?:\\s+(?:\\*([A-Za-z])\\*|(foil|etched|nonfoil)))?' + // 5 flag, 6 word
  '\\s*$',
  'i',
);

const FLAG_OF = { foil: 'F', etched: 'E', nonfoil: '' };
export const LABEL = { '': 'nonfoil', F: 'foil', E: 'etched' };

const MAX_NAME = 199;

/**
 * Lines that are structure, not cards. Exporters scatter these through a
 * list and every one of them would otherwise be looked up as a card name.
 */
const SECTIONS = new Set([
  'deck', 'decklist', 'main', 'mainboard', 'maindeck',
  'sideboard', 'side', 'sb',
  'commander', 'commanders', 'companion', 'partner',
  'maybeboard', 'maybe', 'considering', 'tokens', 'token',
  'about', 'planes', 'scheme', 'schemes', 'conspiracy',
  'creatures', 'creature', 'lands', 'land', 'instants', 'instant',
  'sorceries', 'sorcery', 'artifacts', 'artifact',
  'enchantments', 'enchantment', 'planeswalkers', 'planeswalker',
  'battles', 'battle', 'other', 'ramp', 'removal', 'draw',
]);

/** 'SB:' and friends, which prefix a line without changing the card. */
const BOARD_PREFIX = /^(sb|mb|cm|side|main|maybe|commander|deck)\s*[:|]\s*/i;

/** One line -> a request, or null with a reason. */
export function parseLine(raw) {
  let line = raw.trim();
  // Comment markers: # is ours, // is MTGA and Moxfield.
  if (!line || line.startsWith('#') || line.startsWith('//')) return null;

  // A section header is not a card. Bare, or with a count after it the way
  // MTGA writes "Deck (99)".
  const bare = line.replace(/\s*\(\d+\)\s*$/, '').replace(/[:：]\s*$/, '').trim();
  if (SECTIONS.has(bare.toLowerCase())) return null;

  line = line.replace(BOARD_PREFIX, '').trim();
  if (!line) return null;

  // Archidekt appends categories and tags after the card: "[Ramp]",
  // "[Commander{top}]", sometimes "^Buy^".
  line = line.replace(/\s*\[[^\]]*\]\s*$/, '').replace(/\s*\^[^^]*\^\s*$/, '').trim();
  if (!line) return null;

  // Control characters would sail through the regex as part of the name and
  // end up in the database. The old queue rejected them at the door; so do we.
  if (/[\u0000-\u001f\u007f]/.test(line)) {
    return { error: 'control character in line' };
  }

  const m = LINE.exec(line);
  if (!m) return { error: 'unparseable' };

  const [, qtyRaw, nameRaw, setRaw, numRaw, flagRaw, wordRaw] = m;

  const qty = qtyRaw === undefined ? 1 : parseInt(qtyRaw, 10);
  if (!Number.isFinite(qty) || qty < 1) return { error: 'quantity must be at least 1' };
  if (qty > 10000) return { error: 'quantity absurd' };

  const set = (setRaw || '').toUpperCase();
  const num = set ? (numRaw || null) : null;

  // Reassemble first, strip the trailing comma second. The name group is
  // non-greedy, so "Kardur, Doomscourge" with no set code parses as name
  // "Kardur," plus collector number "Doomscourge" — stripping before the
  // two are put back together swallowed the comma and gave Scryfall a
  // card that does not exist.
  let name = nameRaw.trim();
  if (!set && numRaw) name = `${name} ${numRaw}`.trim();
  name = name.replace(/,+$/, '').trim();
  if (!name) return { error: 'no card name' };
  if (name.length > MAX_NAME) return { error: 'name too long' };

  const flag = (flagRaw || FLAG_OF[(wordRaw || '').toLowerCase()] || '').toUpperCase();
  if (flag && !(flag in LABEL)) return { error: `unknown finish flag ${flag}` };

  return {
    qty,
    name,
    set,
    num,
    flag,
    finish: LABEL[flag],
    flagGiven: Boolean(flagRaw || wordRaw),
    raw: line,
  };
}

/**
 * A whole list -> { items, errors }.
 *
 * Accepts a text decklist or a CSV export; the format is detected rather
 * than declared, because nobody pasting a file wants to tell us what it
 * is. Unparseable lines do not sink the request — they come back as
 * errors and the rest still applies.
 */
export function parseList(text) {
  if (typeof text !== 'string') return { items: [], errors: ['list must be a string'] };

  if (looksLikeCsv(text)) return parseCsv(text);

  const items = [];
  const errors = [];
  for (const raw of text.split('\n')) {
    const r = parseLine(raw);
    if (r === null) continue;
    if (r.error) errors.push(`${raw.trim()}: ${r.error}`);
    else items.push(r);
  }
  return { items, errors };
}

// -------------------------------------------------------------- CSV

/** One CSV line -> fields, respecting quotes and escaped quotes. */
export function splitCsvLine(line) {
  const out = [];
  let cur = '';
  let quoted = false;
  for (let i = 0; i < line.length; i += 1) {
    const c = line[i];
    if (quoted) {
      if (c === '"') {
        if (line[i + 1] === '"') { cur += '"'; i += 1; } else quoted = false;
      } else cur += c;
    } else if (c === '"') quoted = true;
    else if (c === ',') { out.push(cur); cur = ''; } else cur += c;
  }
  out.push(cur);
  return out.map((f) => f.trim());
}

const norm = (h) => h.toLowerCase().replace(/[^a-z]/g, '');

// Every exporter spells these differently, so match on meaning.
const COLUMNS = {
  qty: ['quantity', 'count', 'qty', 'amount', 'have'],
  name: ['name', 'cardname', 'card'],
  set: ['setcode', 'edition', 'set', 'editioncode', 'setid', 'expansion'],
  num: ['collectornumber', 'cardnumber', 'collectornum', 'number', 'cardnum'],
  foil: ['foil', 'finish', 'printing', 'isfoil'],
};

function headerMap(fields) {
  const map = {};
  fields.forEach((raw, i) => {
    const h = norm(raw);
    for (const [key, names] of Object.entries(COLUMNS)) {
      if (map[key] === undefined && names.includes(h)) map[key] = i;
    }
  });
  return map;
}

/** A CSV needs a header with at least a recognisable name column. */
export function looksLikeCsv(text) {
  const first = text.split('\n').find((l) => l.trim());
  if (!first || !first.includes(',')) return false;
  const map = headerMap(splitCsvLine(first));
  return map.name !== undefined && (map.qty !== undefined || map.set !== undefined);
}

const FOIL_WORDS = { foil: 'F', etched: 'E', normal: '', nonfoil: '', '': '', false: '', true: 'F', yes: 'F', no: '' };

function parseCsv(text) {
  const lines = text.split('\n').filter((l) => l.trim());
  const map = headerMap(splitCsvLine(lines[0]));
  const items = [];
  const errors = [];

  for (let i = 1; i < lines.length; i += 1) {
    const f = splitCsvLine(lines[i]);
    const name = (f[map.name] || '').trim();
    if (!name) continue;

    const qty = Math.max(1, parseInt(f[map.qty] ?? '1', 10) || 1);
    const set = (f[map.set] || '').trim().toUpperCase();
    const num = set ? (f[map.num] || '').trim() : '';
    const foilRaw = (f[map.foil] || '').trim().toLowerCase();
    const flag = FOIL_WORDS[foilRaw] ?? (foilRaw.includes('etch') ? 'E' : foilRaw.includes('foil') ? 'F' : '');

    if (name.length > MAX_NAME) {
      errors.push(`row ${i + 1}: name too long`);
      continue;
    }
    if (/[\u0000-\u001f\u007f]/.test(name)) {
      errors.push(`row ${i + 1}: control character in name`);
      continue;
    }

    items.push({
      qty,
      name,
      set,
      num: num || null,
      flag,
      finish: LABEL[flag] ?? 'nonfoil',
      flagGiven: Boolean(foilRaw) && foilRaw !== 'normal' && foilRaw !== 'false' && foilRaw !== 'no',
      raw: `${qty} ${name}${set ? ` (${set})${num ? ` ${num}` : ''}` : ''}`,
    });
  }

  if (!items.length && !errors.length) errors.push('CSV had a header but no card rows');
  return { items, errors };
}

/** The same normalization build_collection_db.py uses as a join key. */
export function normalize(name) {
  return String(name).toLowerCase().trim();
}
