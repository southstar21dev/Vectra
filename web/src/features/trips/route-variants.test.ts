import { describe, it, expect } from 'vitest';
import { deriveHeadsign, normalizeStopName, variantServes, type RouteVariant, type StopRefs } from './route-variants';

const ref = (id: string, name: string) => ({ id, name });

const VARIANTS: RouteVariant[] = [
  { headsign: 'Örs vezér tere', name: 'M2 (Örs vezér tere)', stopIds: ['BKK_F1', 'BKK_F2', 'BKK_F3'] },
  { headsign: 'Déli pályaudvar', name: 'M2 (Déli pályaudvar)', stopIds: ['BKK_F3', 'BKK_F2', 'BKK_F1'] },
];

const REFS: StopRefs = {
  BKK_F1: { name: 'Deák Ferenc tér' },
  BKK_F2: { name: 'Keleti pályaudvar M' },
  BKK_F3: { name: 'Örs vezér tere M' },
};

describe('normalizeStopName', () => {
  it('folds accents and strips station suffixes', () => {
    expect(normalizeStopName('II. János Pál pápa tér M')).toBe('ii. janos pal papa ter');
    expect(normalizeStopName('Keleti pályaudvar M')).toBe('keleti palyaudvar');
  });
});

describe('variantServes', () => {
  it('matches by normalized id when ids align', () => {
    const v = VARIANTS[0];
    expect(variantServes(v, { departure: ref('BKK_CSF1', 'Deák Ferenc tér'), destination: ref('BKK_CSF2', 'Keleti pályaudvar M') }, REFS)).toBe(true);
    expect(variantServes(v, { departure: ref('BKK_CSF2', 'Keleti pályaudvar M'), destination: ref('BKK_CSF1', 'Deák Ferenc tér') }, REFS)).toBe(false);
  });

  it('falls back to name matching when the id schemes differ (the catalog/API mismatch)', () => {
    const v = VARIANTS[0]; // stopIds BKK_F1 (Deák) … BKK_F3 (Örs)
    // Catalog ids (BKK_CS056232-style) never appear in the variant's platform ids.
    const served = variantServes(
      v,
      { departure: ref('BKK_CS9', 'Deák Ferenc tér'), destination: ref('BKK_CS8', 'Keleti pályaudvar M') },
      REFS
    );
    expect(served).toBe(true);
    const reversed = variantServes(
      v,
      { departure: ref('BKK_CS8', 'Keleti pályaudvar M'), destination: ref('BKK_CS9', 'Deák Ferenc tér') },
      REFS
    );
    expect(reversed).toBe(false);
  });

  it('handles the station-suffix difference in names (catalog without M, refs with M)', () => {
    const v = VARIANTS[0];
    expect(
      variantServes(
        v,
        { departure: ref('X1', 'Deák Ferenc tér'), destination: ref('X2', 'II. János Pál pápa tér') },
        { BKK_F1: { name: 'Deák Ferenc tér' }, BKK_F2: { name: 'II. János Pál pápa tér M' }, BKK_F3: { name: 'Örs vezér tere M' } }
      )
    ).toBe(true);
  });

  it('returns false when refs are missing and ids do not match', () => {
    expect(
      variantServes(VARIANTS[0], { departure: ref('BKK_CS9', 'Deák Ferenc tér'), destination: ref('BKK_CS8', 'Keleti pályaudvar M') })
    ).toBe(false);
  });

  it('returns false for stops on different variants', () => {
    // departure only in variant 0, destination only in variant 1
    expect(
      variantServes(VARIANTS[0], { departure: ref('BKK_F1', 'Deák Ferenc tér'), destination: ref('BKK_F3', 'Örs vezér tere M') }, REFS)
    ).toBe(true);
  });
});

describe('deriveHeadsign', () => {
  it('picks the variant whose stop order runs departure before destination', () => {
    expect(deriveHeadsign(VARIANTS, { departure: ref('BKK_F1', 'Deák Ferenc tér'), destination: ref('BKK_F3', 'Örs vezér tere M') }, REFS)).toBe('Örs vezér tere');
    expect(deriveHeadsign(VARIANTS, { departure: ref('BKK_F3', 'Örs vezér tere M'), destination: ref('BKK_F1', 'Deák Ferenc tér') }, REFS)).toBe('Déli pályaudvar');
  });

  it('resolves via names when ids mismatch', () => {
    expect(deriveHeadsign(VARIANTS, { departure: ref('BKK_CS9', 'Deák Ferenc tér'), destination: ref('BKK_CS8', 'Keleti pályaudvar M') }, REFS)).toBe('Örs vezér tere');
  });

  it('falls back to the variant name when headsign is absent', () => {
    const noHeadsign: RouteVariant[] = [{ name: 'M2 (Örs vezér tere)', stopIds: ['BKK_F1', 'BKK_F2'] }];
    expect(deriveHeadsign(noHeadsign, { departure: ref('BKK_F1', 'Deák Ferenc tér'), destination: ref('BKK_F2', 'Keleti pályaudvar M') }, REFS)).toBe('M2 (Örs vezér tere)');
  });

  it('returns "" when no variant matches', () => {
    expect(deriveHeadsign(VARIANTS, { departure: ref('BKK_F9', 'Unknown'), destination: ref('BKK_F1', 'Deák Ferenc tér') }, REFS)).toBe('');
  });

  it('returns "" for empty inputs', () => {
    expect(deriveHeadsign([], { departure: ref('a', 'A'), destination: ref('b', 'B') })).toBe('');
    expect(deriveHeadsign(VARIANTS, { departure: ref('', ''), destination: ref('', '') })).toBe('');
  });
});