import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import type { CatalogRoute, CatalogRouteVariant } from '@/features/transit/catalog-types';
import {
  suggestRoutes,
  routesThrough,
  servesPair,
  groupForRoute,
  headsignFor,
  headsignsForSuggestions,
  findTransferChains,
  type RouteSuggestion,
} from './route-suggest';

interface Catalog {
  routes: CatalogRoute[];
  variants: Record<string, CatalogRouteVariant[]>;
}

const readJson = <T>(path: string): T => JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8')) as T;

/**
 * Real BKK data pinned at two moments (see each file's `source`): the regular network, and the
 * November 2026 diversion where trams 28/28A stopped short of Teleki and 37A ended there. The
 * scenarios run on these slices so they test the engine, not whatever the feed says today.
 */
const NORMAL = readJson<Catalog>('./__fixtures__/catalog-normal.json');
const DIVERTED = readJson<Catalog>('./__fixtures__/catalog-diverted.json');

/** Today's generated catalog: only checked for invariants that hold through any diversion. */
const LIVE: Catalog = {
  routes: readJson<CatalogRoute[]>('../../../public/catalog/routes.json'),
  variants: readJson<Record<string, CatalogRouteVariant[]>>('../../../public/catalog/route-variants.json'),
};

const names = (out: RouteSuggestion[]) => out.map((s) => s.shortName);

describe('regular network (pinned)', () => {
  const { routes, variants } = NORMAL;

  it('Teleki → Corvinus (Fővám tér / Corvin-negyed): no direct route, honest answer', () => {
    for (const dest of ['Fővám tér', 'Corvin-negyed M']) {
      expect(suggestRoutes(routes, variants, 'Teleki László tér', dest)).toEqual([]);
    }
  });

  it('routes from Teleki are offered for the transfer (28/28A, 37/37A)', () => {
    const out = names(routesThrough(routes, variants, 'Teleki László tér'));
    expect(out).toContain('28/28A');
    expect(out).toContain('37/37A');
  });

  it('routes at Fővám tér exist for the second leg (M4, tram 2, 47/49…)', () => {
    expect(routesThrough(routes, variants, 'Fővám tér').length).toBeGreaterThan(2);
  });

  it('28/28A serves Teleki → II. János Pál pápa tér (the earlier false rejection)', () => {
    const out = suggestRoutes(routes, variants, 'Teleki László tér', 'II. János Pál pápa tér');
    expect(out.find((s) => s.shortName === '28/28A')).toBeDefined();
  });

  it('confirmRoute verification: 28/28A group serves the pair, 37/37A group does not reach Fővám', () => {
    expect(servesPair(variants, groupForRoute('BKK_3280'), 'Teleki László tér', 'II. János Pál pápa tér')).toBe(true);
    expect(headsignFor(variants, groupForRoute('BKK_3280'), 'Teleki László tér', 'II. János Pál pápa tér')).toBeTruthy();
    expect(servesPair(variants, groupForRoute('BKK_3370'), 'Teleki László tér', 'Fővám tér')).toBe(false);
  });

  it('route-suggest normalized names line up with the generated index', () => {
    // Catalog name with "M" suffix vs index name without it.
    const out = suggestRoutes(routes, variants, 'Deák Ferenc tér M', 'Keleti pályaudvar M');
    expect(out.some((s) => s.shortName === 'M2')).toBe(true);
  });

  it('either/or: 28/28A and 37/37A both work for Teleki → Blaha with the same direction headsign', () => {
    // Going Teleki → Fővám, pick BOTH first-leg alternatives; both must serve Teleki → Blaha toward the city.
    const alt = routesThrough(routes, variants, 'Teleki László tér').filter((s) => ['28/28A', '37/37A'].includes(s.shortName));
    expect(names(alt)).toEqual(['28/28A', '37/37A']);
    expect(headsignsForSuggestions(variants, alt, 'Teleki László tér', 'Blaha Lujza tér')).toEqual(['Blaha Lujza tér M', 'Blaha Lujza tér M']);
  });

  it('either/or direct case: multiple direct routes all resolve a headsign', () => {
    // Teleki → Orczy is served directly by 28/28A and bus 62.
    const out = suggestRoutes(routes, variants, 'Teleki László tér', 'Orczy tér');
    expect(out.length).toBeGreaterThan(1);
    expect(headsignsForSuggestions(variants, out, 'Teleki László tér', 'Orczy tér').every((h) => h !== null)).toBe(true);
  });

  it("the user's case: Teleki → Fővám suggests 28/28A / 37/37A → II. János Pál pápa tér → M4", () => {
    const firstLegs = routesThrough(routes, variants, 'Teleki László tér').filter((s) => ['28/28A', '37/37A'].includes(s.shortName));
    const chains = findTransferChains(routes, variants, firstLegs, 'Teleki László tér', 'Fővám tér');
    // Both routes merge onto the SAME transfer stop: II. János Pál pápa tér (M4).
    expect(chains.length).toBeGreaterThan(0);
    const iiJanos = chains.filter((c) => c.transferStopName.includes('janos'));
    expect(iiJanos.length).toBe(2);
    expect(iiJanos.every((c) => c.secondLeg.some((s) => s.shortName === 'M4'))).toBe(true);
    expect(iiJanos.every((c) => c.firstHeadsign === 'Blaha Lujza tér M')).toBe(true);
  });

  it('the full transfer chain to Corvinus resolves (28/28A → Blaha, 4/6 → Kálvin, M4 → Fővám)', () => {
    const leg1 = routesThrough(routes, variants, 'Teleki László tér').find((s) => s.shortName === '28/28A')!;
    expect(leg1.headsigns).toContain('Blaha Lujza tér M');
    expect(leg1.headsigns).toContain('Izraelita temető');
    // The leg built for Teleki → Blaha carries the Blaha-direction headsign, re-derived from the actual pair.
    expect(headsignFor(variants, leg1.routeIds, 'Teleki László tér', 'Blaha Lujza tér')).toBe('Blaha Lujza tér M');
    expect(routesThrough(routes, variants, 'Blaha Lujza tér').some((s) => s.shortName === '4/6')).toBe(true);
    expect(suggestRoutes(routes, variants, 'Kálvin tér', 'Fővám tér').some((s) => s.shortName === 'M4')).toBe(true);
  });
});

describe('during a diversion (pinned)', () => {
  const { routes, variants } = DIVERTED;

  it('a line cut short of a stop is no longer offered there', () => {
    // 28/28A ends at Keleti, short of Teleki: suggesting it would send the user to a tram that never comes.
    const out = names(routesThrough(routes, variants, 'Teleki László tér'));
    expect(out).not.toContain('28/28A');
    expect(servesPair(variants, groupForRoute('BKK_3280'), 'Teleki László tér', 'II. János Pál pápa tér')).toBe(false);
  });

  it('the replacement service is offered under its own name instead', () => {
    // VP28 shuttles Teleki ↔ Blaha; it is not part of the 28/28A group, so it shows as plain "28".
    const out = suggestRoutes(routes, variants, 'Teleki László tér', 'Blaha Lujza tér');
    const replacement = out.find((s) => s.routeId === 'BKK_VP28');
    expect(replacement?.shortName).toBe('28');
    expect(replacement?.headsign).toBe('Blaha Lujza tér M');
  });

  it('a line that now ends at the stop only runs away from it', () => {
    // 37A terminates at Teleki: it still serves Teleki → Sörgyár, but nothing onward toward Blaha.
    expect(servesPair(variants, ['BKK_3371'], 'Teleki László tér', 'Sörgyár')).toBe(true);
    expect(servesPair(variants, ['BKK_3371'], 'Teleki László tér', 'Blaha Lujza tér')).toBe(false);
  });

  it("the user's case still finds a way to Fővám, without the diverted legs", () => {
    const firstLegs = routesThrough(routes, variants, 'Teleki László tér');
    const chains = findTransferChains(routes, variants, firstLegs, 'Teleki László tér', 'Fővám tér');
    expect(chains.length).toBeGreaterThan(0);
    // Every chain's first leg really runs Teleki → transfer stop on today's variants.
    for (const c of chains) {
      expect(servesPair(variants, c.first.routeIds, 'Teleki László tér', c.transferStopName)).toBe(true);
    }
    expect(chains.some((c) => c.first.shortName === '28/28A')).toBe(false);
  });
});

describe('live catalog invariants', () => {
  const { routes, variants } = LIVE;
  // Pairs across the city; whatever BKK runs today, every answer must be one it really runs.
  const PAIRS: Array<[string, string]> = [
    ['Teleki László tér', 'Blaha Lujza tér'],
    ['Teleki László tér', 'Orczy tér'],
    ['Deák Ferenc tér M', 'Keleti pályaudvar M'],
    ['Kálvin tér', 'Fővám tér'],
    ['Blaha Lujza tér', 'Kálvin tér'],
  ];

  it('the catalog loads with routes and variants for them', () => {
    expect(routes.length).toBeGreaterThan(100);
    expect(routes.filter((r) => variants[r.id]?.length).length).toBeGreaterThan(100);
  });

  it('every direct suggestion serves its pair and has a headsign', () => {
    for (const [from, to] of PAIRS) {
      for (const s of suggestRoutes(routes, variants, from, to)) {
        expect(servesPair(variants, s.routeIds, from, to), `${s.shortName} ${from} → ${to}`).toBe(true);
        expect(s.headsign, `${s.shortName} ${from} → ${to}`).toBeTruthy();
      }
    }
  });

  it('every transfer chain is made of legs that really run', () => {
    for (const [from, to] of [['Teleki László tér', 'Fővám tér'], ['Teleki László tér', 'Corvin-negyed M']]) {
      const chains = findTransferChains(routes, variants, routesThrough(routes, variants, from), from, to);
      for (const c of chains) {
        expect(servesPair(variants, c.first.routeIds, from, c.transferStopName), `${c.first.shortName} ${from} → ${c.transferStopName}`).toBe(true);
        for (const s of c.secondLeg) {
          expect(servesPair(variants, s.routeIds, c.transferStopName, to), `${s.shortName} ${c.transferStopName} → ${to}`).toBe(true);
        }
      }
    }
  });
});
