import { env } from 'cloudflare:test';
import { describe, it, expect } from 'vitest';
import { post, sql, stubScryfall } from './helpers.js';
import { MATT } from './helpers.js';
import { signIn } from '../src/accounts.js';

/**
 * Every column an intake path writes: that it is written, and that
 * what lands in it is the right shape.
 *
 * Matt: "Audit EVERY SINGLE fucking property set at intake time and
 * WRITE FUCKING TESTS AROUND THEM!!!" and then, when the first version
 * only checked for emptiness: "THEN FUCKING WRITE TESTS THAT ASSERT
 * THE PROPER FUCKING FORMAT FOR ALL FUCKING FIELDS!!!!"
 *
 * Both right. The bug that started this was `createDeck` writing
 * `colors: null` forever — but an emptiness check would have been just
 * as happy with `colors: 'ZZZ'`, and a deck whose colours are "ZZZ"
 * draws no pips for exactly the same reason a null one does.
 *
 * So every column is declared here with the shape it must hold. The
 * row is created the way the app creates it, the table's own column
 * list is read back with `PRAGMA table_info`, and three things have to
 * be true:
 *
 *   1. every column is either declared or excused, with a reason
 *   2. every declared column's value matches its shape
 *   3. nothing excused for being empty is actually full
 *
 * A column added tomorrow fails this until somebody says what belongs
 * in it. That is the whole point: the omission gets noticed at the
 * time rather than months later when a deck looks colourless.
 */
describe('what intake writes, and what shape it is', () => {
  // ------------------------------------------------------------ shapes

  const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
  const DATE = /^\d{4}-\d{2}-\d{2}$/;
  const STAMP = /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}/;
  const KEY = /^[0-9a-hjkmnp-tv-z]{8}$/;

  /** WUBRG letters, in WUBRG order, each at most once. */
  const colourString = (value) => {
    if (!/^[WUBRG]*$/.test(value)) return `"${value}" is not WUBRG letters`;
    const order = [...'WUBRG'];
    const seen = [...value].map((c) => order.indexOf(c));
    if (new Set(seen).size !== seen.length) return `"${value}" repeats a colour`;
    if (seen.some((n, i) => i > 0 && n <= seen[i - 1])) return `"${value}" is not in WUBRG order`;
    return null;
  };

  const oneOf = (...allowed) => (value) => (
    allowed.includes(value) ? null : `"${value}" is not one of ${allowed.join(', ')}`
  );

  const matching = (re) => (value) => (re.test(String(value)) ? null : `"${value}" does not match ${re}`);

  const wholeNumber = (min = 0) => (value) => {
    if (!Number.isInteger(value)) return `${JSON.stringify(value)} is not a whole number`;
    return value >= min ? null : `${value} is below ${min}`;
  };

  const flag = (value) => (value === 0 || value === 1 ? null : `${JSON.stringify(value)} is not 0 or 1`);

  const text = (value) => {
    if (typeof value !== 'string') return `${JSON.stringify(value)} is not text`;
    if (value !== value.trim()) return `"${value}" has space around it`;
    return value.length ? null : 'is empty';
  };

  /** A comma-separated list of words, with no stray spaces. */
  const wordList = (value) => {
    if (typeof value !== 'string') return `${JSON.stringify(value)} is not text`;
    const parts = value.split(',');
    const bad = parts.find((p) => p !== p.trim() || p === '');
    return bad === undefined ? null : `"${value}" has a blank or padded entry`;
  };

  const number = (min = 0) => (value) => {
    if (typeof value !== 'number' || Number.isNaN(value)) return `${JSON.stringify(value)} is not a number`;
    return value >= min ? null : `${value} is below ${min}`;
  };

  /**
   * Hold a row to its declaration: shape for what is there, a stated
   * reason for what is not, and nothing unaccounted for either way.
   */
  function audit(table, row, declared, emptyOnPurpose) {
    const wrong = [];
    const unexplained = [];
    const pointless = [];

    Object.entries(row).forEach(([column, value]) => {
      const blank = value === null || value === '';
      const excused = Object.prototype.hasOwnProperty.call(emptyOnPurpose, column);
      if (blank) {
        if (!excused) unexplained.push(column);
        return;
      }
      if (excused) pointless.push(column);
      const shape = declared[column];
      if (!shape) {
        unexplained.push(`${column} (no shape declared)`);
        return;
      }
      const complaint = shape(value, row);
      if (complaint) wrong.push(`${column}: ${complaint}`);
    });

    expect(
      unexplained,
      `${table}: these columns are empty or undeclared and nothing here says why.\n`
      + 'Either fill them in at intake, or declare the shape, or excuse them with a reason.',
    ).toEqual([]);
    expect(
      wrong,
      `${table}: these columns hold something of the wrong shape.`,
    ).toEqual([]);
    expect(
      pointless,
      `${table}: these are excused for being empty but are not empty. Drop them from the list.`,
    ).toEqual([]);
  }

  async function columnsOf(table) {
    return (await sql(`PRAGMA table_info(${table})`)).map((r) => r.name);
  }

  /** No column exists that this audit has never heard of. */
  async function everyColumnIsAccountedFor(table, declared, excused) {
    const known = [...Object.keys(declared), ...Object.keys(excused)];
    expect(
      (await columnsOf(table)).filter((c) => !known.includes(c)),
      `${table} has a column this audit has never heard of. `
      + 'Say what shape belongs in it, or why it is empty.',
    ).toEqual([]);
  }

  // ------------------------------------------------------------- decks

  describe('a deck made in the app', () => {
    const NEW = {
      name: 'Audit Brew',
      format: 'commander',
      collection: MATT,
      commander: "Akroma's Will",
      list: '1 Lightning Bolt',
      bracket: '3',
      theme: 'audit',
    };

    const SHAPES = {
      id: wholeNumber(1),
      key: matching(KEY),
      name: text,
      // Matt's, because Matt's session made it: the id, not a name.
      owner_id: (value) => (value === 1 ? null : `${JSON.stringify(value)} is not account 1`),
      format: oneOf('commander', 'standard', 'modern', 'legacy', 'vintage', 'pauper', 'pioneer', 'brawl', 'historic', 'oathbreaker', 'other'),
      recorded_date: matching(DATE),
      colors: colourString,
      commander: text,
      theme: text,
      bracket: matching(/^[1-5]$/),
      is_proxy: flag,
      card_count: wholeNumber(1),
      // Never more owned than the deck asks for: that would be a
      // count of something other than this deck.
      owned_count: (value, row) => wholeNumber(0)(value)
        ?? (value <= row.card_count ? null : `${value} owned of ${row.card_count} wanted`),
    };

    const EMPTY = {
      source_file: 'typed into the app, not imported from a file',
      source_md: 'likewise: there is no markdown to keep',
      status: 'free prose on the imported decks; the wizard does not ask and must not guess',
      slug: 'retired: the address is `key` now, and a new deck leaves this empty',
      owner: 'retired: the owner is `owner_id` now, and a new deck leaves this empty',
    };

    async function made(body = NEW) {
      const r = await post('/decks/create', { ...body, dry_run: false }, stubScryfall());
      expect(r.status, JSON.stringify(r.body)).toBe(201);
      return (await sql('SELECT * FROM decks WHERE key = ?1', r.body.key))[0];
    }

    it('every column is the shape it should be', async () => {
      audit('decks', await made(), SHAPES, EMPTY);
    });

    it('and the table has not grown a column nobody declared', async () => {
      await everyColumnIsAccountedFor('decks', SHAPES, EMPTY);
    });

    /**
     * The one that started this, now checked for value rather than
     * for presence. `colors` draws the pips, and "ZZZ" draws none of
     * them just as surely as null does.
     */
    it('takes its colours from its commander, in WUBRG order', async () => {
      expect((await made()).colors).toBe('W');
    });

    it('a deck with no commander takes them from the list', async () => {
      const deck = await made({
        name: 'Audit Modern', format: 'modern', collection: MATT, list: '1 Lightning Bolt',
      });
      expect(deck.colors).toBe('R');
    });

    it('keeps what the wizard was told, exactly as it was told', async () => {
      const deck = await made();
      expect(deck).toMatchObject({
        name: 'Audit Brew',
        format: 'commander',
        owner_id: 1,
        commander: "Akroma's Will",
        bracket: '3',
        theme: 'audit',
        is_proxy: 0,
      });
      expect(deck.key).toMatch(KEY);
    });
  });

  // ------------------------------------------------------------- cards

  describe('a card added in the app', () => {
    const SHAPES = {
      id: wholeNumber(1),
      owner_id: (value) => (value === 1 ? null : `${JSON.stringify(value)} is not account 1`),
      qty: wholeNumber(1),
      finish: oneOf('nonfoil', 'foil', 'etched', 'glossy'),
      scryfall_id: matching(UUID),
      oracle_id: matching(UUID),
      name: text,
      // The join key every other table uses. Lower case, trimmed, and
      // nothing a `LIKE` would have to escape.
      name_norm: (value) => (value === String(value).toLowerCase().trim()
        ? null
        : `"${value}" is not a normalised name`),
      mana_cost: matching(/^(?:\{[^}]+\})*$/),
      cmc: number(0),
      oracle_text: text,
      type_line: text,
      types: wordList,
      colors: colourString,
      color_identity: colourString,
      // The column exists so a five-colour filter does not have to
      // count letters; if it disagrees with the letters it is worse
      // than useless.
      color_identity_count: (value, row) => wholeNumber(0)(value)
        ?? (value === String(row.color_identity ?? '').length
          ? null
          : `${value} against identity "${row.color_identity}"`),
      rarity: oneOf('common', 'uncommon', 'rare', 'mythic', 'special', 'bonus'),
      setcode: matching(/^[a-z0-9]{3,6}$/),
      set_name: text,
      set_type: matching(/^[a-z_]+$/),
      released_at: matching(DATE),
      collector_number: matching(/^[A-Za-z0-9★†\-+]+$/),
      artist: text,
      layout: matching(/^[a-z_]+$/),
      frame: matching(/^(?:1993|1997|2003|2015|future)$/),
      border_color: oneOf('black', 'white', 'borderless', 'silver', 'gold', 'yellow'),
      edhrec_rank: wholeNumber(1),
      reserved: flag,
      game_changer: flag,
      full_art: flag,
      textless: flag,
      promo: flag,
      reprint: flag,
      variation: flag,
      oversized: flag,
      story_spotlight: flag,
      booster: flag,
      // Only the cards that have them, but the shape still holds.
      face1: text,
      face2: text,
      flavor_text: text,
      supertypes: wordList,
      subtypes: wordList,
      produced_mana: colourString,
      power: text,
      toughness: text,
      loyalty: text,
      defense: text,
      watermark: matching(/^[a-z0-9_]+$/),
      security_stamp: oneOf('oval', 'triangle', 'acorn', 'circle', 'arena', 'heart'),
      foil_flag: text,
    };

    const EMPTY = {
      face2: 'only a double-faced card has a back',
      supertypes: 'a Bolt is not Legendary or Basic',
      subtypes: 'an Instant has no creature type',
      produced_mana: 'it makes no mana',
      watermark: 'no watermark on this printing',
      security_stamp: 'no stamp on this printing',
      power: 'not a creature',
      toughness: 'not a creature',
      loyalty: 'not a planeswalker',
      defense: 'not a battle',
      foil_flag: "the finish column says it; this is the old import's word for the same thing",
      owner: 'retired: the owner is `owner_id` now, and a new card leaves this empty',
    };

    async function added() {
      const r = await post(
        '/cards/add',
        { collection: MATT, list: '1 Lightning Bolt (2X2) 117', dry_run: false },
        stubScryfall(),
      );
      expect(r.status, JSON.stringify(r.body)).toBe(200);
      return (await sql(
        "SELECT * FROM cards WHERE name_norm = 'lightning bolt' AND setcode = '2x2' LIMIT 1",
      ))[0];
    }

    it('every column is the shape it should be', async () => {
      audit('cards', await added(), SHAPES, EMPTY);
    });

    it('and the table has not grown a column nobody declared', async () => {
      await everyColumnIsAccountedFor('cards', SHAPES, EMPTY);
    });

    it('the card is the card it was asked for', async () => {
      const card = await added();
      expect(card.name).toBe('Lightning Bolt');
      expect(card.name_norm).toBe('lightning bolt');
      expect(card.setcode).toBe('2x2');
      expect(card.collector_number).toBe('117');
      expect(card.colors).toBe('R');
      expect(card.color_identity).toBe('R');
      expect(card.type_line).toContain('Instant');
    });

    it('and its child rows say the same thing the columns do', async () => {
      const card = await added();
      const colours = await sql('SELECT color, kind FROM card_colors WHERE card_id = ?1', card.id);
      expect(colours.length).toBeGreaterThan(0);
      colours.forEach((r) => {
        expect('WUBRG', `card_colors.color "${r.color}"`).toContain(r.color);
        // The vocabulary the table actually uses, read off the live
        // database rather than guessed: color, identity, produced.
        expect(['color', 'identity', 'produced'], `kind "${r.kind}"`).toContain(r.kind);
      });

      const types = await sql('SELECT type, kind FROM card_types WHERE card_id = ?1', card.id);
      expect(types.length).toBeGreaterThan(0);
      types.forEach((r) => {
        expect(r.type, 'a blank type').toBeTruthy();
        expect(r.type).toBe(String(r.type).trim());
        expect(['type', 'subtype', 'supertype'], `kind "${r.kind}"`).toContain(r.kind);
      });

      const finishes = await sql('SELECT finish FROM card_finishes WHERE card_id = ?1', card.id);
      expect(finishes.length).toBeGreaterThan(0);
      finishes.forEach((r) => {
        expect(['nonfoil', 'foil', 'etched', 'glossy'], `finish "${r.finish}"`).toContain(r.finish);
      });

      const games = await sql('SELECT game FROM card_games WHERE card_id = ?1', card.id);
      expect(games.length).toBeGreaterThan(0);
      games.forEach((r) => {
        expect(['paper', 'arena', 'mtgo', 'astral', 'sega'], `game "${r.game}"`).toContain(r.game);
      });
    });

    it('a second copy adds to the quantity rather than making a second row', async () => {
      const first = await added();
      const again = await added();
      expect(again.id).toBe(first.id);
      expect(again.qty).toBe(first.qty + 1);
    });
  });

  // ------------------------------------------------------------- users

  describe('an account made by signing in', () => {
    const SHAPES = {
      id: wholeNumber(1),
      key: matching(KEY),
      // Retired, but NOT NULL UNIQUE until a migration drops it, so a
      // new account's slug is its key and nothing else.
      slug: (value, row) => (value === row.key ? null : `"${value}" is not the key "${row.key}"`),
      display_name: text,
      email: matching(/^[^@\s]+@[^@\s]+\.[^@\s]+$/),
      avatar_url: matching(/^https:\/\/\S+$/),
      role: oneOf('user', 'admin'),
      created_at: matching(STAMP),
    };

    async function joined(over = {}) {
      const user = await signIn(env.DB, {
        provider: 'google',
        subject: 'audit-1',
        name: 'Audit Person',
        email: 'audit@example.com',
        avatar: 'https://example.test/a.png',
        ...over,
      });
      return (await sql('SELECT * FROM users WHERE id = ?1', user.id))[0];
    }

    it('every column is the shape it should be', async () => {
      audit('users', await joined(), SHAPES, {});
    });

    it('and the table has not grown a column nobody declared', async () => {
      await everyColumnIsAccountedFor('users', SHAPES, {});
    });

    it('starts as a user, never as an admin', async () => {
      expect((await joined()).role).toBe('user');
    });

    it('and a name that is all punctuation still gets a usable address', async () => {
      const u = await joined({ subject: 'audit-2', name: '!!!' });
      expect(u.key).toMatch(KEY);
      expect(u.slug).toBe(u.key);
    });
  });
});
