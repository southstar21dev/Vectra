import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import type { CatalogRoute, CatalogRouteVariant } from '@/features/transit/catalog-types';

const readCatalogJson = <T>(name: string): T =>
  JSON.parse(readFileSync(new URL(`../../../public/catalog/${name}`, import.meta.url), 'utf8')) as T;

const CATALOG_ROUTES = readCatalogJson<CatalogRoute[]>('routes.json');
const ROUTE_VARIANTS = readCatalogJson<Record<string, CatalogRouteVariant[]>>('route-variants.json');
import {
  suggestRoutes,
  routesThrough,
  servesPair,
  groupForRoute,
  headsignFor,
  headsignsForSuggestions,
  findTransferChains,
} from './route-suggest';

describe('live index probe', () => {
  it('Teleki → Corvinus (Fővám tér / Corvin-negyed): no direct route, honest answer', () => {
    for (const dest of ['Fővám tér', 'Corvin-negyed M']) {
      const out = suggestRoutes(CATALOG_ROUTES, ROUTE_VARIANTS, 'Teleki László tér', dest);
      expect(out).toEqual([]);
    }
  });

  it('routes from Teleki are offered for the transfer (28/28A, 37/37A)', () => {
    const out = routesThrough(CATALOG_ROUTES, ROUTE_VARIANTS, 'Teleki László tér');
    const names = out.map((s) => s.shortName);
    expect(names).toContain('28/28A');
    expect(names).toContain('37/37A');
  });

  it('routes at Fővám tér exist for the second leg (M4, tram 2, 47/49…)', () => {
    const out = routesThrough(CATALOG_ROUTES, ROUTE_VARIANTS, 'Fővám tér');
    expect(out.length).toBeGreaterThan(2);
  });

  it('28/28A serves Teleki → II. János Pál pápa tér (the earlier false rejection)', () => {
    const out = suggestRoutes(CATALOG_ROUTES, ROUTE_VARIANTS, 'Teleki László tér', 'II. János Pál pápa tér');
    const four28 = out.find((s) => s.shortName === '28/28A');
    expect(four28).toBeDefined();
  });

  it('confirmRoute verification: 28/28A group serves the pair, 37/37A group does not reach Fővám', () => {
    expect(servesPair(ROUTE_VARIANTS, groupForRoute('BKK_3280'), 'Teleki László tér', 'II. János Pál pápa tér')).toBe(true);
    expect(headsignFor(ROUTE_VARIANTS, groupForRoute('BKK_3280'), 'Teleki László tér', 'II. János Pál pápa tér')).toBeTruthy();
    expect(servesPair(ROUTE_VARIANTS, groupForRoute('BKK_3370'), 'Teleki László tér', 'Fővám tér')).toBe(false);
  });

  it('route-suggest normalized names line up with the generated index', () => {
    // Catalog name with "M" suffix vs index name without it.
    const out = suggestRoutes(CATALOG_ROUTES, ROUTE_VARIANTS, 'Deák Ferenc tér M', 'Keleti pályaudvar M');
    expect(out.some((s) => s.shortName === 'M2')).toBe(true);
  });

  it('either/or: 28/28A and 37/37A both work for Teleki → Blaha with the same direction headsign', () => {
    // The user's case: going Teleki → Fővám, pick BOTH first-leg alternatives
    // 28/28A and 37/37A; both must serve Teleki → Blaha toward the city.
    const fromTeleki = routesThrough(CATALOG_ROUTES, ROUTE_VARIANTS, 'Teleki László tér');
    const alt = fromTeleki.filter((s) => ['28/28A', '37/37A'].includes(s.shortName));
    expect(alt.map((s) => s.shortName)).toEqual(['28/28A', '37/37A']);
    const hs = headsignsForSuggestions(ROUTE_VARIANTS, alt, 'Teleki László tér', 'Blaha Lujza tér');
    // Both head toward Blaha in the "Blaha Lujza tér M" direction — same headsign.
    expect(hs).toEqual(['Blaha Lujza tér M', 'Blaha Lujza tér M']);
  });

  it('either/or direct case: multiple direct routes all resolve a headsign', () => {
    // Real data: Teleki → Orczy is served directly by 28/28A and bus 62.
    const out = suggestRoutes(CATALOG_ROUTES, ROUTE_VARIANTS, 'Teleki László tér', 'Orczy tér');
    expect(out.length).toBeGreaterThan(1);
    const hs = headsignsForSuggestions(ROUTE_VARIANTS, out, 'Teleki László tér', 'Orczy tér');
    expect(hs.every((h) => h !== null)).toBe(true);
  });

  it('the user\'s case: Teleki → Fővám suggests 28/28A / 37/37A → II. János Pál pápa tér → M4', () => {
    const fromTeleki = routesThrough(CATALOG_ROUTES, ROUTE_VARIANTS, 'Teleki László tér');
    const firstLegs = fromTeleki.filter((s) => ['28/28A', '37/37A'].includes(s.shortName));
    const chains = findTransferChains(CATALOG_ROUTES, ROUTE_VARIANTS, firstLegs, 'Teleki László tér', 'Fővám tér');
    // Both routes merge onto the SAME transfer stop: II. János Pál pápa tér (M4).
    expect(chains.length).toBeGreaterThan(0);
    const iiJanos = chains.filter((c) => c.transferStopName.includes('janos'));
    expect(iiJanos.length).toBe(2);
    expect(iiJanos.every((c) => c.secondLeg.some((s) => s.shortName === 'M4'))).toBe(true);
    expect(iiJanos.every((c) => c.firstHeadsign === 'Blaha Lujza tér M')).toBe(true);
  });

  it('the full transfer chain to Corvinus resolves (28/28A → Blaha, 4/6 → Kálvin, M4 → Fővám)', () => {
    // Leg 1: the suggested transfer from Teleki — panel shows both directions.
    const fromTeleki = routesThrough(CATALOG_ROUTES, ROUTE_VARIANTS, 'Teleki László tér');
    const leg1 = fromTeleki.find((s) => s.shortName === '28/28A')!;
    expect(leg1.headsigns).toContain('Blaha Lujza tér M');
    expect(leg1.headsigns).toContain('Izraelita temető');
    // The leg built for Teleki → Blaha must carry the Blaha-direction headsign
    // (re-derived from the actual pair, not the panel's first direction).
    expect(headsignFor(ROUTE_VARIANTS, leg1.routeIds, 'Teleki László tér', 'Blaha Lujza tér')).toBe('Blaha Lujza tér M');

    // Leg 2 (chained): Blaha → Kálvin on 4/6 (transfer panel), then M4 Kálvin → Fővám direct.
    const fromBlaha = routesThrough(CATALOG_ROUTES, ROUTE_VARIANTS, 'Blaha Lujza tér');
    expect(fromBlaha.some((s) => s.shortName === '4/6')).toBe(true);
    const kalvinLeg = suggestRoutes(CATALOG_ROUTES, ROUTE_VARIANTS, 'Kálvin tér', 'Fővám tér');
    expect(kalvinLeg.some((s) => s.shortName === 'M4')).toBe(true);
  });
});