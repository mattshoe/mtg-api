import { env } from 'cloudflare:test';
import { describe, it, expect } from 'vitest';
import { post, sql, stubScryfall } from './helpers.js';
import { signIn } from '../src/accounts.js';

/**
 * Every column an intake path writes, and whether it actually writes it.
 *
 * Matt: "Audit EVERY SINGLE fucking property set at intake time and
 * WRITE FUCKING TESTS AROUND THEM!!! This is such a fucking stupid and
 * avoidable problem you should've easily caught!!!"
 *
 * He is right, and the bug that prompted it was exactly this shape:
 * `createDeck` wrote `colors: null` and nothing ever filled it in, so
 * three decks made in the app were the only colourless ones in a
 * database where every imported deck had the column set. Nothing
 * failed. Nothing was red. The deck just quietly had no colour for as
 * long as nobody looked.
 *
 * So this is not a test of one column. It reads the table definition
 * out of the database, creates the row the way the app creates it, and
 * demands that every column is either **filled in** or **named here
 * with a reason**. A column added to a table tomorrow fails this until
 * somebody decides which it is — which is the only way an omission
 * gets noticed at the time rather than months later.
 */
describe('what intake actually fills in', () => {
  /** Every column of a table, as the database itself describes it. */
  async function columnsOf(table) {
    const rows = await sql(`PRAGMA table_info(${table})`);
    return rows.map((r) => r.name);
  }

  /**
   * Hold a row to its table: every column is filled in, or listed
   * here with the reason it is not.
   */
  function audit(table, row, emptyOnPurpose) {
    const unexplained = [];
    const pointless = [];
    Object.entries(row).forEach(([column, value]) => {
      const blank = value === null || value === '';
      const excused = Object.prototype.hasOwnProperty.call(emptyOnPurpose, column);
      if (blank && !excused) unexplained.push(column);
      if (!blank && excused) pointless.push(column);
    });
    expect(
      unexplained,
      `${table}: these columns came out empty and nothing here says why.\n`
      + 'Either fill them in at intake or add them to the list with a reason.',
    ).toEqual([]);
    expect(
      pointless,
      `${table}: these are excused for being empty but are not empty. Drop them from the list.`,
    ).toEqual([]);
  }

  /** The table has not grown a column nobody classified. */
  async function noNewColumns(table, known) {
    expect(
      (await columnsOf(table)).filter((c) => !known.includes(c)),
      `${table} has a column this audit has never heard of. `
      + 'Decide whether intake fills it in, then add it here.',
    ).toEqual([]);
  }

  // ------------------------------------------------------------- decks

  describe('a deck made in the app', () => {
    const NEW = {
      name: 'Audit Brew',
      format: 'commander',
      owner: 'matt',
      commander: "Akroma's Will",
      list: '1 Lightning Bolt',
      bracket: '3',
      theme: 'audit',
    };

    async function made(body = NEW) {
      const r = await post('/decks/create', { ...body, dry_run: false }, stubScryfall());
      expect(r.status, JSON.stringify(r.body)).toBe(201);
      return (await sql('SELECT * FROM decks WHERE slug = ?1', r.body.slug))[0];
    }

    it('fills in every column it can, and the rest are named here', async () => {
      audit('decks', await made(), {
        // There is no file. The deck was typed into the app, which is
        // the whole point of the wizard, and inventing a filename
        // would make a provenance trail that is not true.
        source_file: 'typed into the app, not imported from a file',
        source_md: 'likewise: there is no markdown to keep',
        // Free prose on the imported decks — "Stock precon,
        // unmodified", "Custom build, physically assembled". The
        // wizard does not ask and must not guess.
        status: 'a note the wizard does not ask for',
      });
    });

    it('and the table has not grown a column nobody thought about', async () => {
      await noNewColumns('decks', [
        'id', 'slug', 'name', 'owner', 'format', 'source_file', 'recorded_date',
        'status', 'colors', 'commander', 'theme', 'bracket', 'is_proxy',
        'card_count', 'owned_count', 'source_md',
      ]);
    });

    /**
     * The one that started this.
     *
     * `colors` is what draws the pips on a deck tile, and an empty
     * one is not "colourless" — it is "nobody worked it out", which
     * looks identical and is a different fact.
     */
    it('knows its colours, which is what the tile draws', async () => {
      expect((await made()).colors).toBe('W');
    });

    it('counts its cards and how many are owned', async () => {
      const deck = await made();
      expect(deck.card_count).toBeGreaterThan(0);
      expect(deck.owned_count).toBeGreaterThan(0);
    });

    it('records when it was made', async () => {
      expect((await made()).recorded_date).toMatch(/^\d{4}-\d{2}-\d{2}/);
    });

    it('keeps what the wizard was told, rather than dropping it on the floor', async () => {
      const deck = await made();
      expect(deck.name).toBe('Audit Brew');
      expect(deck.format).toBe('commander');
      expect(deck.owner).toBe('matt');
      expect(deck.commander).toBe("Akroma's Will");
      expect(deck.bracket).toBe('3');
      expect(deck.theme).toBe('audit');
      expect(deck.is_proxy).toBe(0);
    });

    it('a deck with no commander still gets its colours from the list', async () => {
      const deck = await made({
        name: 'Audit Modern', format: 'modern', owner: 'matt', list: '1 Lightning Bolt',
      });
      expect(deck.colors).toBe('R');
    });
  });

  // ------------------------------------------------------------- cards

  describe('a card added in the app', () => {
    async function added() {
      const r = await post(
        '/cards/add',
        { owner: 'matt', list: '1 Lightning Bolt (2X2) 117', dry_run: false },
        stubScryfall(),
      );
      expect(r.status, JSON.stringify(r.body)).toBe(200);
      return (await sql(
        "SELECT * FROM cards WHERE name_norm = 'lightning bolt' AND setcode = '2x2' LIMIT 1",
      ))[0];
    }

    it('fills in every column it can, and the rest are named here', async () => {
      audit('cards', await added(), {
        // Scryfall sends these only for the cards that have them, and
        // most cards have none: a Lightning Bolt is not legendary, has
        // no subtype, makes no mana and carries no watermark.
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
        foil_flag: 'the finish column says it; this is the old import\'s word for the same thing',
        // Zero is a real answer for a flag, not an empty one — the
        // audit treats 0 as filled in, so none of the ten appear here.
      });
    });

    it('and the table has not grown a column nobody thought about', async () => {
      await noNewColumns('cards', [
        'id', 'owner', 'qty', 'finish', 'foil_flag', 'scryfall_id', 'oracle_id',
        'name', 'name_norm', 'face1', 'face2', 'mana_cost', 'cmc', 'oracle_text',
        'flavor_text', 'power', 'toughness', 'loyalty', 'defense', 'type_line',
        'supertypes', 'types', 'subtypes', 'colors', 'color_identity',
        'color_identity_count', 'produced_mana', 'rarity', 'setcode', 'set_name',
        'set_type', 'released_at', 'collector_number', 'artist', 'layout', 'frame',
        'border_color', 'reserved', 'game_changer', 'full_art', 'textless', 'promo',
        'reprint', 'variation', 'oversized', 'story_spotlight', 'booster',
        'edhrec_rank', 'watermark', 'security_stamp',
      ]);
    });

    it('the things a card page reads are all there', async () => {
      // Every one of these was missing from the page itself, which is
      // a different bug — but the column being empty would make that
      // one unfixable.
      const card = await added();
      ['artist', 'layout', 'frame', 'border_color', 'released_at', 'set_type',
        'rarity', 'collector_number', 'edhrec_rank', 'color_identity',
      ].forEach((c) => {
        expect(card[c], `cards.${c} is empty on a card the app just added`).not.toBe(null);
      });
    });

    it('and its child rows are written too', async () => {
      const card = await added();
      const kinds = await Promise.all([
        sql('SELECT COUNT(*) AS n FROM card_colors WHERE card_id = ?1', card.id),
        sql('SELECT COUNT(*) AS n FROM card_types WHERE card_id = ?1', card.id),
        sql('SELECT COUNT(*) AS n FROM card_finishes WHERE card_id = ?1', card.id),
        sql('SELECT COUNT(*) AS n FROM card_games WHERE card_id = ?1', card.id),
      ]);
      const [colors, types, finishes, games] = kinds.map((r) => r[0].n);
      expect(colors, 'card_colors').toBeGreaterThan(0);
      expect(types, 'card_types').toBeGreaterThan(0);
      expect(finishes, 'card_finishes').toBeGreaterThan(0);
      expect(games, 'card_games').toBeGreaterThan(0);
    });
  });

  // ------------------------------------------------------------- users

  describe('an account made by signing in', () => {
    async function joined() {
      const user = await signIn(env.DB, {
        provider: 'google',
        subject: 'audit-1',
        name: 'Audit Person',
        email: 'audit@example.com',
        avatar: 'https://example.test/a.png',
      });
      return (await sql('SELECT * FROM users WHERE id = ?1', user.id))[0];
    }

    it('fills in every column it can, and the rest are named here', async () => {
      audit('users', await joined(), {});
    });

    it('and the table has not grown a column nobody thought about', async () => {
      await noNewColumns('users', [
        'id', 'key', 'slug', 'display_name', 'email', 'avatar_url', 'role', 'created_at',
      ]);
    });

    it('starts as a user, with a key and a slug of its own', async () => {
      const u = await joined();
      expect(u.role).toBe('user');
      expect(u.key).toMatch(/^[0-9a-hjkmnp-tv-z]{8}$/);
      expect(u.slug).toBe('audit-person');
      expect(u.created_at).toMatch(/^\d{4}-\d{2}-\d{2}/);
    });
  });
});
