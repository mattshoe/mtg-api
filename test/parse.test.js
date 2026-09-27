import { describe, it, expect } from 'vitest';
import { parseLine, parseList, normalize } from '../src/parse.js';

describe('parseLine', () => {
  const accepts = [
    ['Sol Ring', { qty: 1, name: 'Sol Ring', set: '', num: null, finish: 'nonfoil' }],
    ['4 Sol Ring', { qty: 4, name: 'Sol Ring', set: '', num: null }],
    ['4x Sol Ring', { qty: 4, name: 'Sol Ring', set: '', num: null }],
    ['4 x Sol Ring', { qty: 4, name: 'Sol Ring' }],
    ['1 Sol Ring (M3C) 409', { qty: 1, name: 'Sol Ring', set: 'M3C', num: '409' }],
    ['1 Sol Ring (m3c) 409', { qty: 1, set: 'M3C', num: '409' }],
    ['1 Sol Ring (M3C) 409 *F*', { finish: 'foil', flag: 'F', flagGiven: true }],
    ['1 Sol Ring (M3C) 409 *E*', { finish: 'etched', flag: 'E' }],
    ['1 Sol Ring (M3C) 409 foil', { finish: 'foil', flagGiven: true }],
    ['1 Sol Ring (M3C) 409 etched', { finish: 'etched' }],
    ['1 Sol Ring (M3C) 409 nonfoil', { finish: 'nonfoil', flagGiven: true }],
    ['  2   Lightning Bolt  ', { qty: 2, name: 'Lightning Bolt' }],
    ["Alchemist's Refuge", { name: "Alchemist's Refuge" }],
    ['Fire // Ice', { name: 'Fire // Ice' }],
    ['Delver of Secrets // Insectile Aberration', { name: 'Delver of Secrets // Insectile Aberration' }],
    ['1 Krark-Clan Ironworks (5DN) 121', { name: 'Krark-Clan Ironworks', set: '5DN', num: '121' }],
    ['1 Brainstorm (MH2) 42★', { name: 'Brainstorm', set: 'MH2', num: '42★' }],
    ['3 Sol Ring,', { qty: 3, name: 'Sol Ring' }],
  ];

  for (const [input, want] of accepts) {
    it(`accepts ${JSON.stringify(input)}`, () => {
      const got = parseLine(input);
      expect(got, `parse failed for ${input}`).toBeTruthy();
      expect(got.error).toBeUndefined();
      expect(got).toMatchObject(want);
    });
  }

  it('keeps the comma in a two-word legend written without a set code', () => {
    // The name group is non-greedy, so this parses as name "Kardur," plus
    // collector number "Doomscourge" before the two are put back together.
    // Stripping the trailing comma too early swallowed it.
    expect(parseLine('1 Kardur, Doomscourge').name).toBe('Kardur, Doomscourge');
    expect(parseLine('Kardur, Doomscourge').name).toBe('Kardur, Doomscourge');
    expect(parseLine('2x Alela, Cunning Conqueror').name).toBe('Alela, Cunning Conqueror');
  });

  it('keeps a trailing number in the name when no set was given', () => {
    // "Fear of Missing Out 2" must not lose its 2 to the collector-number slot.
    expect(parseLine('1 Borrowed Time 2').name).toBe('Borrowed Time 2');
  });

  it('skips blank lines and comments', () => {
    expect(parseLine('')).toBeNull();
    expect(parseLine('   ')).toBeNull();
    expect(parseLine('# a comment')).toBeNull();
  });

  const rejects = [
    ['0 Sol Ring', /at least 1/],
    ['99999999 Sol Ring', /absurd/],
    [`1 ${'x'.repeat(250)}`, /too long/],
    ['1 SolRing', /control character/],
  ];

  for (const [input, pattern] of rejects) {
    it(`rejects ${JSON.stringify(input.slice(0, 30))}`, () => {
      const got = parseLine(input);
      expect(got.error).toMatch(pattern);
    });
  }

  it('treats a negative quantity as part of the name, not a quantity', () => {
    // The regex cannot match a minus sign as a qty, so "-1 Sol Ring" is a
    // name. It will simply fail to resolve, which is the honest outcome.
    const got = parseLine('-1 Sol Ring');
    expect(got.qty).toBe(1);
    expect(got.name).toBe('-1 Sol Ring');
  });
});

describe('parseList', () => {
  it('collects good lines and reports bad ones without dropping either', () => {
    const { items, errors } = parseList(
      '4 Lightning Bolt (2X2) 117\n\n# note\n0 Sol Ring\n1 Arcane Signet',
    );
    expect(items.map((i) => i.name)).toEqual(['Lightning Bolt', 'Arcane Signet']);
    expect(errors).toHaveLength(1);
    expect(errors[0]).toMatch(/Sol Ring/);
  });

  it('handles CRLF input', () => {
    const { items } = parseList('1 Sol Ring\r\n2 Lightning Bolt\r\n');
    expect(items).toHaveLength(2);
    expect(items[1].name).toBe('Lightning Bolt');
  });

  it('rejects a non-string list', () => {
    expect(parseList(undefined).errors).toHaveLength(1);
    expect(parseList(42).items).toEqual([]);
  });
});

describe('normalize', () => {
  it('matches build_collection_db.py: lower, then strip', () => {
    expect(normalize('  Sol Ring ')).toBe('sol ring');
    expect(normalize("Alchemist's Refuge")).toBe("alchemist's refuge");
    expect(normalize('Fire // Ice')).toBe('fire // ice');
  });
});

describe('real-world decklist exports', () => {
  it('skips section headers instead of looking them up as cards', () => {
    const { items, errors } = parseList([
      'Deck',
      '1 Black Lotus (LEA) 233',
      '',
      'Sideboard',
      '2 Negate (DMU) 58',
      'Commander',
      '1 Sol Ring (M3C) 409',
    ].join('\n'));
    expect(errors).toEqual([]);
    expect(items.map((i) => i.name)).toEqual(['Black Lotus', 'Negate', 'Sol Ring']);
  });

  it('handles MTGA counts on the header, like "Deck (99)"', () => {
    const { items } = parseList('Deck (99)\n1 Sol Ring');
    expect(items.map((i) => i.name)).toEqual(['Sol Ring']);
  });

  it('strips Archidekt categories and tags', () => {
    const { items, errors } = parseList([
      '1x Black Lotus (lea) 233 [Commander{top}]',
      '1x Sol Ring (m3c) 409 [Ramp]',
      '1x Opt (stx) 45 ^Buy^',
    ].join('\n'));
    expect(errors).toEqual([]);
    expect(items.map((i) => [i.name, i.set, i.num]))
      .toEqual([['Black Lotus', 'LEA', '233'], ['Sol Ring', 'M3C', '409'], ['Opt', 'STX', '45']]);
  });

  it('strips MTGO board prefixes', () => {
    const { items } = parseList('SB: 2 Negate\nMB: 1 Sol Ring');
    expect(items.map((i) => [i.qty, i.name])).toEqual([[2, 'Negate'], [1, 'Sol Ring']]);
  });

  it('treats // as a comment, the way MTGA and Moxfield write them', () => {
    const { items } = parseList('// my deck\n1 Sol Ring');
    expect(items.map((i) => i.name)).toEqual(['Sol Ring']);
  });

  it('keeps set and collector number through every quantity style', () => {
    const { items, errors } = parseList([
      '1 Black Lotus (XYZ) 123',
      '4x Lightning Bolt (2X2) 117',
      '2 x Sol Ring (M3C) 409',
    ].join('\n'));
    expect(errors).toEqual([]);
    expect(items.map((i) => [i.qty, i.set, i.num]))
      .toEqual([[1, 'XYZ', '123'], [4, '2X2', '117'], [2, 'M3C', '409']]);
  });
});

describe('CSV exports', () => {
  const MANABOX = [
    '"Binder Name","Binder Type","Name","Set code","Set name","Collector number","Foil","Rarity","Quantity","Scryfall ID"',
    '"Collection","binder","Black Lotus","lea","Limited Edition Alpha","233","normal","rare","1","abc"',
    '"Collection","binder","Sol Ring","m3c","MH3 Commander","409","foil","uncommon","2","def"',
    '"Collection","binder","Arcane Signet","blc","Bloomburrow","127","etched","common","3","ghi"',
  ].join('\n');

  it('detects and parses a ManaBox export', () => {
    const { items, errors } = parseList(MANABOX);
    expect(errors).toEqual([]);
    expect(items).toHaveLength(3);
    expect(items[0]).toMatchObject({ qty: 1, name: 'Black Lotus', set: 'LEA', num: '233', finish: 'nonfoil' });
    expect(items[1]).toMatchObject({ qty: 2, name: 'Sol Ring', set: 'M3C', num: '409', finish: 'foil' });
    expect(items[2]).toMatchObject({ qty: 3, name: 'Arcane Signet', finish: 'etched' });
  });

  it('matches columns by name, not position', () => {
    // Moxfield orders them completely differently.
    const moxfield = [
      '"Count","Name","Edition","Condition","Language","Foil","Collector Number"',
      '"4","Lightning Bolt","2x2","Near Mint","English","","117"',
      '"1","Sol Ring","m3c","Near Mint","English","foil","409"',
    ].join('\n');
    const { items, errors } = parseList(moxfield);
    expect(errors).toEqual([]);
    expect(items.map((i) => [i.qty, i.name, i.set, i.num, i.finish])).toEqual([
      [4, 'Lightning Bolt', '2X2', '117', 'nonfoil'],
      [1, 'Sol Ring', 'M3C', '409', 'foil'],
    ]);
  });

  it('handles commas and quotes inside a field', () => {
    const csv = [
      'Name,Set code,Quantity',
      '"Alela, Cunning Conqueror",woc,1',
      '"Borrowing 100,000 Arrows",chk,2',
    ].join('\n');
    const { items, errors } = parseList(csv);
    expect(errors).toEqual([]);
    expect(items.map((i) => i.name)).toEqual(['Alela, Cunning Conqueror', 'Borrowing 100,000 Arrows']);
    expect(items[1].qty).toBe(2);
  });

  it('reads true/false foil columns as well as words', () => {
    const csv = 'Name,Quantity,Foil\nSol Ring,1,true\nOpt,1,false';
    const { items } = parseList(csv);
    expect(items.map((i) => i.finish)).toEqual(['foil', 'nonfoil']);
  });

  it('skips blank rows rather than erroring on them', () => {
    const csv = 'Name,Quantity\nSol Ring,1\n\n,2\nOpt,1';
    const { items, errors } = parseList(csv);
    expect(items.map((i) => i.name)).toEqual(['Sol Ring', 'Opt']);
    expect(errors).toEqual([]);
  });

  it('says so when a CSV has a header and nothing else', () => {
    const { items, errors } = parseList('Name,Set code,Quantity');
    expect(items).toEqual([]);
    expect(errors[0]).toMatch(/no card rows/);
  });

  it('does not mistake a decklist for a CSV', () => {
    // A card with a comma in its name must not trigger CSV detection.
    const { items, errors } = parseList('1 Alela, Cunning Conqueror (WOC) 3\n1 Sol Ring');
    expect(errors).toEqual([]);
    expect(items.map((i) => i.name)).toEqual(['Alela, Cunning Conqueror', 'Sol Ring']);
  });

  it('a collector number without a set is ignored, as in the text path', () => {
    const { items } = parseList('Name,Quantity,Collector number\nSol Ring,1,409');
    expect(items[0].num).toBeNull();
  });
});
