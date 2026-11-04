import { describe, it, expect } from 'vitest';
import {
  suggestRoutes,
  groupForRoute,
  servesPair,
  headsignFor,
  headsignsForSuggestions,
  routesThrough,
  findTransferChains,
  findJourneyChains,
  estimateJourneyMinutes,
  estimateRouteMinutes,
  isExcludedFromSuggestions,
  type RouteSuggestion,
} from './route-suggest';
import type { CatalogRoute, CatalogRouteVariant } from '@/features/transit/catalog-types';

const ROUTES: CatalogRoute[] = [
  { id: 'BKK_3040', shortName: '4', description: 'Újbuda-központ M | Széll Kálmán tér M', type: 'TRAM', color: 'FFD800', textColor: '000000' },
  { id: 'BKK_3060', shortName: '6', description: 'Móricz Zs. körtér M | Széll Kálmán tér M', type: 'TRAM', color: 'FFD800', textColor: '000000' },
  { id: 'BKK_3280', shortName: '28', description: 'Blaha Lujza tér M | Izraelita temető', type: 'TRAM', color: 'FFD800', textColor: '000000' },
  { id: 'BKK_5200', shortName: 'M2', description: 'Örs vezér tere | Déli pályaudvar', type: 'SUBWAY', color: 'E41F18', textColor: 'FFFFFF' },
  { id: 'BKK_9920', shortName: '99', description: 'Blaha Lujza tér M | Kőbánya alsó vasútállomás', type: 'BUS', color: '009EE3', textColor: 'FFFFFF' },
  { id: 'BKK_M4', shortName: 'M4', description: 'Kelenföld vasútállomás M | Keleti pályaudvar M', type: 'SUBWAY', color: '009645', textColor: 'FFFFFF' },
  { id: 'BKK_9923', shortName: '23', description: 'Orczy tér | Fővám tér M', type: 'BUS', color: '009EE3', textColor: 'FFFFFF' },
];

const INDEX: Record<string, CatalogRouteVariant[]> = {
  BKK_3040: [
    { h: 'Széll Kálmán tér M', s: ['kossuth ter', 'deak ferenc ter', 'kalvin ter', 'corvin-negyed m', 'blaha lujza ter'] },
    { h: 'Újbuda-központ M', s: ['blaha lujza ter', 'corvin-negyed m', 'kalvin ter', 'deak ferenc ter', 'kossuth ter'] },
  ],
  BKK_3060: [
    { h: 'Széll Kálmán tér M', s: ['kossuth ter', 'deak ferenc ter', 'kalvin ter', 'blaha lujza ter'] },
    { h: 'Móricz Zs. körtér M', s: ['blaha lujza ter', 'kalvin ter', 'deak ferenc ter', 'kossuth ter'] },
  ],
  BKK_3280: [
    { h: 'Izraelita temető', s: ['blaha lujza ter', 'teleki laszlo ter', 'magdolna utca', 'orczy ter'] },
    { h: 'Blaha Lujza tér M', s: ['orczy ter', 'teleki laszlo ter', 'blaha lujza ter', 'kalvin ter'] },
  ],
  BKK_M4: [
    { h: 'Kelenföld vasútállomás M', s: ['kalvin ter', 'fovam ter', 'szent gellert ter'] },
    { h: 'Keleti pályaudvar M', s: ['szent gellert ter', 'fovam ter', 'kalvin ter'] },
  ],
  BKK_9923: [{ h: 'Fővám tér M', s: ['orczy ter', 'magdolna utca', 'fovam ter'] }],
  BKK_5200: [
    { h: 'Örs vezér tere', s: ['deak ferenc ter', 'keleti palyaudvar', 'ors vezér tere'] },
    { h: 'Déli pályaudvar', s: ['ors vezér tere', 'keleti palyaudvar', 'deak ferenc ter'] },
  ],
  BKK_9920: [{ h: 'Kőbánya alsó vasútállomás', s: ['kalvin ter', 'blaha lujza ter', 'orczy ter', 'teleki laszlo ter'] }],
};

describe('suggestion service exclusions', () => {
  const retro: CatalogRoute = { id: 'BKK_R3299', shortName: 'R29Y', description: 'Őrház | Keleti pályaudvar M', type: 'TRAM', color: 'FFD800', textColor: '000000' };
  const nightBus: CatalogRoute = { id: 'BKK_9010', shortName: '901', description: 'Bécsi út | Kelenföld', type: 'BUS', color: '000000', textColor: 'FFFFFF' };
  const exclusionIndex: Record<string, CatalogRouteVariant[]> = {
    BKK_R3299: [{ h: 'Keleti pályaudvar M', s: ['orczy ter', 'keleti palyaudvar'] }],
    BKK_9010: [{ h: 'Kelenföld', s: ['orczy ter', 'keleti palyaudvar'] }],
  };

  it('excludes the paid R29Y heritage tram from suggestions', () => {
    expect(isExcludedFromSuggestions(retro)).toBe(true);
    expect(suggestRoutes([retro], exclusionIndex, 'Orczy tér', 'Keleti pályaudvar M')).toEqual([]);
    expect(routesThrough([retro], exclusionIndex, 'Orczy tér')).toEqual([]);
  });

  it('excludes 9xx night buses from transfer suggestions', () => {
    expect(isExcludedFromSuggestions(nightBus)).toBe(true);
    expect(suggestRoutes([nightBus], exclusionIndex, 'Orczy tér', 'Keleti pályaudvar M')).toEqual([]);
    expect(routesThrough([nightBus], exclusionIndex, 'Orczy tér')).toEqual([]);
  });
});

describe('suggestRoutes', () => {
  it('suggests the direct route with its direction headsign', () => {
    const out = suggestRoutes(ROUTES, INDEX, 'Deák Ferenc tér', 'Keleti pályaudvar M');
    expect(out.length).toBe(1);
    expect(out[0].shortName).toBe('M2');
    expect(out[0].headsign).toBe('Örs vezér tere');
  });

  it('is direction-aware (reversed pair gets the other direction)', () => {
    const out = suggestRoutes(ROUTES, INDEX, 'Keleti pályaudvar M', 'Deák Ferenc tér');
    expect(out[0].headsign).toBe('Déli pályaudvar');
  });

  it('merges combined-route groups into a single suggestion', () => {
    const out = suggestRoutes(ROUTES, INDEX, 'Kálvin tér', 'Blaha Lujza tér');
    const fourSix = out.find((s) => s.shortName === '4/6');
    expect(fourSix).toBeDefined();
    expect(fourSix!.routeIds).toEqual(['BKK_3040', 'BKK_3060', 'BKK_3046']);
  });

  it('returns [] when no direct route exists', () => {
    const out = suggestRoutes(ROUTES, INDEX, 'Teleki László tér', 'Fővám tér');
    expect(out).toEqual([]);
  });

  it('merges 1 and 1A into one suggestion', () => {
    const routes: CatalogRoute[] = [
      { id: 'BKK_3010', shortName: '1', description: 'Kelenföld | Bécsi út', type: 'TRAM', color: 'FFD800', textColor: '000000' },
      { id: 'BKK_3011', shortName: '1A', description: 'Népliget | Bécsi út', type: 'TRAM', color: 'FFD800', textColor: '000000' },
    ];
    const index: Record<string, CatalogRouteVariant[]> = {
      BKK_3010: [{ h: 'Bécsi út', s: ['hungaria korut', 'kalvin ter'] }],
      BKK_3011: [{ h: 'Bécsi út', s: ['hungaria korut', 'kalvin ter'] }],
    };
    const out = suggestRoutes(routes, index, 'Hungária körút', 'Kálvin tér');
    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({ shortName: '1/1A', routeIds: ['BKK_3010', 'BKK_3011'] });
  });

  it('matches stops with station-suffix differences (catalog vs route names)', () => {
    // Catalog: "Blaha Lujza tér" (no M); index: "blaha lujza ter" — matches.
    const out = suggestRoutes(ROUTES, INDEX, 'Blaha Lujza tér', 'Kálvin tér');
    expect(out.length).toBeGreaterThan(0);
    expect(out.some((s) => s.shortName === '4/6')).toBe(true);
  });

  it('returns [] for empty names', () => {
    expect(suggestRoutes(ROUTES, INDEX, '', 'Kálvin tér')).toEqual([]);
  });

  it('sorts suggestions metro/tram first, then by short name', () => {
    const out = suggestRoutes(ROUTES, INDEX, 'Kálvin tér', 'Blaha Lujza tér');
    const types = out.map((s) => s.type);
    const typeOrder = [...new Set(types)];
    expect(typeOrder).toEqual(['TRAM', 'BUS']); // no SUBWAY between
  });
});

describe('RouteSuggestion shape', () => {
  it('carries route metadata for the leg', () => {
    const out: RouteSuggestion[] = suggestRoutes(ROUTES, INDEX, 'Deák Ferenc tér', 'Keleti pályaudvar M');
    expect(out[0]).toMatchObject({
      routeId: 'BKK_5200',
      shortName: 'M2',
      type: 'SUBWAY',
      color: 'E41F18',
      textColor: 'FFFFFF',
    });
  });
});

describe('groupForRoute', () => {
  it('combines the 1 and 1A trams', () => {
    expect(groupForRoute('BKK_3010')).toEqual(['BKK_3010', 'BKK_3011']);
    expect(groupForRoute('BKK_3011')).toEqual(['BKK_3010', 'BKK_3011']);
  });

  it('expands a combined-route member to its full group', () => {
    expect(groupForRoute('BKK_3040')).toEqual(['BKK_3040', 'BKK_3060', 'BKK_3046']);
    expect(groupForRoute('BKK_3281')).toEqual(['BKK_3280', 'BKK_3281']);
  });

  it('returns the route alone when it is not in a combined group', () => {
    expect(groupForRoute('BKK_5200')).toEqual(['BKK_5200']);
  });
});

describe('servesPair / headsignFor', () => {
  it('servesPair is false when no member direction serves dep before dest', () => {
    expect(servesPair(INDEX, ['BKK_3280'], 'Teleki László tér', 'Fővám tér')).toBe(false);
    expect(servesPair(INDEX, ['BKK_3280'], 'Orczy tér', 'Blaha Lujza tér')).toBe(true);
  });

  it('servesPair checks all group members (combined routes)', () => {
    // 28 serves Blaha → Orczy in the "Izraelita temető" direction.
    expect(servesPair(INDEX, ['BKK_3280', 'BKK_3281'], 'Blaha Lujza tér', 'Orczy tér')).toBe(true);
  });

  it('headsignFor returns the direction headsign, null when unsupported', () => {
    expect(headsignFor(INDEX, ['BKK_3280'], 'Blaha Lujza tér', 'Orczy tér')).toBe('Izraelita temető');
    expect(headsignFor(INDEX, ['BKK_3280'], 'Teleki László tér', 'Fővám tér')).toBeNull();
  });
});

describe('headsignsForSuggestions', () => {
  it('derives the pair-specific headsign for every suggestion', () => {
    const sug = suggestRoutes(ROUTES, INDEX, 'Blaha Lujza tér', 'Orczy tér');
    // Both 28/28A (Izraelita direction) and 99 (Kőbánya direction) serve it.
    expect(sug.length).toBe(2);
    const hs = headsignsForSuggestions(INDEX, sug, 'Blaha Lujza tér', 'Orczy tér');
    expect(hs).toEqual(['Izraelita temető', 'Kőbánya alsó vasútállomás']);
  });

  it('maps headsigns in the same order as the suggestions', () => {
    const sug = suggestRoutes(ROUTES, INDEX, 'Blaha Lujza tér', 'Orczy tér');
    const hs = headsignsForSuggestions(INDEX, sug, 'Blaha Lujza tér', 'Orczy tér');
    expect(sug.map((s, i) => `${s.shortName}:${hs[i]}`)).toEqual(['28/28A:Izraelita temető', '99:Kőbánya alsó vasútállomás']);
  });

  it('null for suggestions that do not serve the pair (validation)', () => {
    const sug = suggestRoutes(ROUTES, INDEX, 'Deák Ferenc tér', 'Keleti pályaudvar M');
    expect(sug.length).toBe(1);
    expect(headsignsForSuggestions(INDEX, sug, 'Deák Ferenc tér', 'Fővám tér')).toEqual([null]);
  });
});

describe('findJourneyChain', () => {
  it('does not create three consecutive metro legs', () => {
    const routes: CatalogRoute[] = [
      { id: 'tram', shortName: '1/1A', description: 'Start tram', type: 'TRAM', color: 'FFD800', textColor: '000000' },
      { id: 'm2', shortName: 'M2', description: 'M2', type: 'SUBWAY', color: 'E41F18', textColor: 'FFFFFF' },
      { id: 'm3', shortName: 'M3', description: 'M3', type: 'SUBWAY', color: '2F4F9D', textColor: 'FFFFFF' },
      { id: 'm4', shortName: 'M4', description: 'M4', type: 'SUBWAY', color: '009645', textColor: 'FFFFFF' },
    ];
    const index: Record<string, CatalogRouteVariant[]> = {
      tram: [{ h: 'Metro stop', s: ['start', 'metro stop'] }],
      m2: [{ h: 'M2 end', s: ['metro stop', 'm2 stop'] }],
      m3: [{ h: 'M3 end', s: ['m2 stop', 'm3 stop'] }],
      m4: [{ h: 'M4 end', s: ['m3 stop', 'destination'] }],
    };
    const chains = findJourneyChains(routes, index, {
      routeId: 'tram',
      routeIds: ['tram'],
      shortName: '1/1A',
      displayName: 'Start tram',
      type: 'TRAM',
      color: 'FFD800',
      textColor: '000000',
      headsign: 'Metro stop',
    }, 'Start', 'Destination', 4, 100);

    expect(chains.some((chain) => chain.legs.map((leg) => leg.route.shortName).join(' → ') === '1/1A → M2 → M3 → M4')).toBe(false);
    expect(chains.every((chain) => {
      let consecutive = 0;
      return chain.legs.every((leg) => {
        consecutive = leg.route.type === 'SUBWAY' ? consecutive + 1 : 0;
        return consecutive <= 2;
      });
    })).toBe(true);
  });

  it('prefers the direct downstream stop over a longer detour', () => {
    const routes: CatalogRoute[] = [
      { id: 'tram', shortName: '1/1A', description: 'Start tram', type: 'TRAM', color: 'FFD800', textColor: '000000' },
      { id: 'm3', shortName: 'M3', description: 'M3', type: 'SUBWAY', color: '2F4F9D', textColor: 'FFFFFF' },
      { id: 'm4', shortName: 'M4', description: 'M4', type: 'SUBWAY', color: '4CA22F', textColor: 'FFFFFF' },
      { id: 'bus49', shortName: '49', description: '49', type: 'TRAM', color: 'FFD800', textColor: '000000' },
    ];
    const index: Record<string, CatalogRouteVariant[]> = {
      tram: [{ h: 'Népliget', s: ['start', 'nepliget'] }],
      m3: [{ h: 'Újpest-központ', s: ['nepliget', 'kalvin ter'] }],
      m4: [{ h: 'Kelenföld', s: ['kalvin ter', 'fovam ter', 'szent gellert ter - muegyetem'] }],
      bus49: [{ h: 'Fővám tér', s: ['szent gellert ter - muegyetem', 'fovam ter'] }],
    };
    const chains = findJourneyChains(routes, index, {
      routeId: 'tram',
      routeIds: ['tram'],
      shortName: '1/1A',
      displayName: 'Start tram',
      type: 'TRAM',
      color: 'FFD800',
      textColor: '000000',
      headsign: 'Népliget',
    }, 'Start', 'Fővám tér', 4, 100);

    expect(chains[0]?.legs.map((leg) => leg.route.shortName)).toEqual(['1/1A', 'M3', 'M4']);
    expect(chains[0]?.legs.at(-1)?.toStopName).toBe('fovam ter');
    expect(chains.some((chain) => chain.legs.map((leg) => leg.route.shortName).join(' → ') === '1/1A → M3 → M4 → 49')).toBe(false);
  });

  it('estimates a complete journey from its route segments and transfers', () => {
    const route: RouteSuggestion = {
      routeId: 'tram',
      routeIds: ['tram'],
      shortName: '1/1A',
      displayName: 'Start tram',
      type: 'TRAM',
      color: 'FFD800',
      textColor: '000000',
      headsign: 'Népliget',
    };
    const chain = {
      legs: [
        { route, fromStopName: 'Start', toStopName: 'Népliget', headsign: 'Népliget' },
      ],
    };
    expect(estimateRouteMinutes({ tram: [{ h: 'Népliget', s: ['start', 'middle', 'nepliget'] }] }, route, 'Start', 'Népliget')).toBe(4);
    expect(estimateJourneyMinutes({ tram: [{ h: 'Népliget', s: ['start', 'middle', 'nepliget'] }] }, chain)).toBe(9);
  });

});

describe('findTransferChains', () => {
  const firstLegs = () => routesThrough(ROUTES, INDEX, 'Teleki László tér');

  it('finds the transfer point where the first leg meets a line to the destination', () => {
    // Teleki → Fővám: 28/28A passes Blaha (no connection) then Kálvin where M4
    // connects — the chain must land on Kálvin with M4 as the second leg.
    const chains = findTransferChains(ROUTES, INDEX, firstLegs(), 'Teleki László tér', 'Fővám tér');
    const chain = chains.find((c) => c.first.shortName === '28/28A');
    expect(chain).toBeDefined();
    expect(chain!.transferStopName).toBe('kalvin ter');
    expect(chain!.firstHeadsign).toBe('Blaha Lujza tér M');
    expect(chain!.secondLeg.map((s) => s.shortName)).toEqual(['M4']);
    expect(chain!.secondLeg[0].headsign).toBe('Kelenföld vasútállomás M');
  });

  it('prefers a subway second leg over a bus one (M4 at Kálvin beats 23 at Magdolna)', () => {
    // 28/28A also passes Magdolna utca where bus 23 runs — but the M4 chain
    // must win because SUBWAY outranks BUS.
    const chains = findTransferChains(ROUTES, INDEX, firstLegs(), 'Teleki László tér', 'Fővám tér');
    const chain = chains.find((c) => c.first.shortName === '28/28A')!;
    expect(chain.transferStopName).toBe('kalvin ter');
    expect(chain.secondLeg.some((s) => s.shortName === 'M4')).toBe(true);
    expect(chain.secondLeg.some((s) => s.shortName === '23')).toBe(false);
  });

  it('returns [] when no route connects to the destination', () => {
    const chains = findTransferChains(ROUTES, INDEX, firstLegs(), 'Teleki László tér', 'Széll Kálmán tér M');
    expect(chains).toEqual([]);
  });

  it('returns [] for empty names', () => {
    expect(findTransferChains(ROUTES, INDEX, firstLegs(), '', 'Fővám tér')).toEqual([]);
  });
});

describe('routesThrough', () => {
  it('lists routes that call at the stop with every direction headsign', () => {
    const out = routesThrough(ROUTES, INDEX, 'Teleki László tér');
    const names = out.map((r) => r.shortName);
    expect(names).toContain('28/28A');
    expect(names).toContain('99');
    const four28 = out.find((r) => r.shortName === '28/28A')!;
    expect(four28.headsign).toBe('Izraelita temető'); // first direction at the stop
    expect(four28.headsigns).toEqual(['Izraelita temető', 'Blaha Lujza tér M']);
  });

  it('a single-direction stop yields one headsign', () => {
    // BKK_9920's only variant calls at Teleki in one direction.
    const out = routesThrough(ROUTES, INDEX, 'Teleki László tér');
    const ninetyNine = out.find((r) => r.shortName === '99')!;
    expect(ninetyNine.headsigns).toEqual(['Kőbánya alsó vasútállomás']);
  });

  it('merges combined groups into one entry', () => {
    const out = routesThrough(ROUTES, INDEX, 'Kálvin tér');
    expect(out.some((r) => r.shortName === '4/6')).toBe(true);
  });

  it('returns [] for unknown stops', () => {
    expect(routesThrough(ROUTES, INDEX, 'Ismeretlen tér')).toEqual([]);
  });
});