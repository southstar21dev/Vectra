import { describe, it, expect } from 'vitest';
import type { CatalogRoute, CatalogRouteVariant, CatalogStop } from '@/features/transit/catalog-types';
import { buildJourneyOptions, stopsAfter } from './journey-options';

const ROUTES: CatalogRoute[] = [
  { id: 'BKK_3040', shortName: '4', description: 'Újbuda-központ M | Széll Kálmán tér M', type: 'TRAM', color: 'FFD800', textColor: '000000' },
  { id: 'BKK_3060', shortName: '6', description: 'Móricz Zs. körtér M | Széll Kálmán tér M', type: 'TRAM', color: 'FFD800', textColor: '000000' },
  { id: 'BKK_M4', shortName: 'M4', description: 'Kelenföld vasútállomás M | Keleti pályaudvar M', type: 'SUBWAY', color: '009645', textColor: 'FFFFFF' },
  { id: 'BKK_9923', shortName: '23', description: 'Orczy tér | Fővám tér M', type: 'BUS', color: '009EE3', textColor: 'FFFFFF' },
];

const INDEX: Record<string, CatalogRouteVariant[]> = {
  BKK_3040: [{ h: 'Széll Kálmán tér M', s: ['kossuth ter', 'deak ferenc ter', 'kalvin ter', 'blaha lujza ter'] }],
  BKK_3060: [{ h: 'Széll Kálmán tér M', s: ['kossuth ter', 'deak ferenc ter', 'kalvin ter', 'blaha lujza ter'] }],
  BKK_M4: [{ h: 'Kelenföld vasútállomás M', s: ['kalvin ter', 'fovam ter', 'szent gellert ter'] }],
  BKK_9923: [{ h: 'Fővám tér M', s: ['orczy ter', 'fovam ter'] }],
};

const stop = (id: string, name: string): CatalogStop => ({ id, name, lat: 47.5, lon: 19.05 });
const STOPS: CatalogStop[] = [
  stop('S1', 'Kossuth tér'),
  stop('S2', 'Deák Ferenc tér'),
  stop('S3', 'Kálvin tér'),
  stop('S4', 'Blaha Lujza tér'),
  stop('S5', 'Fővám tér'),
  stop('S6', 'Szent Gellért tér'),
];

describe('buildJourneyOptions', () => {
  it('offers a direct ride as a single-leg option', () => {
    const options = buildJourneyOptions({ routes: ROUTES, index: INDEX, stops: STOPS, from: STOPS[0], to: STOPS[2] });
    expect(options.length).toBeGreaterThan(0);
    const direct = options.find((o) => o.legs.length === 1);
    expect(direct?.legs[0].routes[0].shortName).toBe('4/6');
    expect(direct?.legs[0].from.id).toBe('S1');
    expect(direct?.legs[0].to.id).toBe('S3');
  });

  it('chains a transfer when no single route reaches the destination', () => {
    const options = buildJourneyOptions({ routes: ROUTES, index: INDEX, stops: STOPS, from: STOPS[0], to: STOPS[4] });
    const transfer = options.find((o) => o.legs.length === 2);
    expect(transfer).toBeDefined();
    expect(transfer!.legs.map((l) => l.routes[0].shortName)).toEqual(['4/6', 'M4']);
    // The transfer stop resolves to the catalog stop, and the ends stay the user's picks.
    expect(transfer!.legs[0].to.id).toBe('S3');
    expect(transfer!.legs[1].from.id).toBe('S3');
    expect(transfer!.legs[1].to.id).toBe('S5');
  });

  it('ranks by estimated time and respects the limit', () => {
    const options = buildJourneyOptions({ routes: ROUTES, index: INDEX, stops: STOPS, from: STOPS[0], to: STOPS[4], limit: 1 });
    expect(options).toHaveLength(1);
  });

  it('returns nothing for the same start and end', () => {
    expect(buildJourneyOptions({ routes: ROUTES, index: INDEX, stops: STOPS, from: STOPS[0], to: STOPS[0] })).toEqual([]);
  });
});

describe('buildJourneyOptions: either/or merging', () => {
  it('folds lines sharing a stretch in the same time into one option', () => {
    const routes: CatalogRoute[] = [
      ...ROUTES,
      { id: 'BKK_0590', shortName: '59', description: 'x', type: 'TRAM', color: 'FFD800', textColor: '000000' },
      { id: 'BKK_0591', shortName: '59A', description: 'x', type: 'TRAM', color: 'FFD800', textColor: '000000' },
    ];
    const index: Record<string, CatalogRouteVariant[]> = {
      ...INDEX,
      BKK_0590: [{ h: 'Kálvin tér', s: ['szent gellert ter', 'kalvin ter'] }],
      BKK_0591: [{ h: 'Kálvin tér', s: ['szent gellert ter', 'kalvin ter'] }],
    };
    const options = buildJourneyOptions({ routes, index, stops: STOPS, from: STOPS[5], to: STOPS[2] });
    const shared = options.filter((o) => o.legs.length === 1 && o.legs[0].routes.some((r) => r.shortName.startsWith('59')));
    expect(shared).toHaveLength(1);
    expect(shared[0].legs[0].routes.map((r) => r.shortName).sort()).toEqual(['59', '59A']);
  });
});

describe('stopsAfter', () => {
  it('lists the stops after the boarding stop in riding order', () => {
    const after = stopsAfter(INDEX, ['BKK_3040', 'BKK_3060'], STOPS[0], STOPS);
    expect(after.map((s) => s.id)).toEqual(['S2', 'S3', 'S4']);
  });

  it('is empty when the route does not call at the boarding stop', () => {
    expect(stopsAfter(INDEX, ['BKK_M4'], STOPS[0], STOPS)).toEqual([]);
  });
});
