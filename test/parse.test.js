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
