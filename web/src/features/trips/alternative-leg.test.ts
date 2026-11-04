import { describe, it, expect } from 'vitest';
import { buildAlternativeLeg } from './alternative-leg';
import type { CatalogRouteVariant } from '@/features/transit/catalog-types';
import type { RouteSuggestion } from './route-suggest';

const INDEX: Record<string, CatalogRouteVariant[]> = {
  BKK_3280: [
    { h: 'Blaha Lujza tér M', s: ['orczy ter', 'teleki laszlo ter', 'blaha lujza ter', 'kalvin ter'] },
  ],
  BKK_3370: [
    { h: 'Blaha Lujza tér M', s: ['orczy ter', 'teleki laszlo ter', 'blaha lujza ter'] },
  ],
  BKK_M4: [
    { h: 'Kelenföld vasútállomás M', s: ['kalvin ter', 'fovam ter'] },
  ],
};

const SUGGESTIONS: RouteSuggestion[] = [
  {
    routeId: 'BKK_3280',
    routeIds: ['BKK_3280', 'BKK_3281'],
    shortName: '28/28A',
    displayName: 'Blaha Lujza tér M | Izraelita temető',
    type: 'TRAM',
    color: 'FFD800',
    textColor: '000000',
    headsign: 'Blaha Lujza tér M',
  },
  {
    routeId: 'BKK_3370',
    routeIds: ['BKK_3370'],
    shortName: '37',
    displayName: 'Blaha Lujza tér M | Sörgyár',
    type: 'TRAM',
    color: 'FFD800',
    textColor: '000000',
    headsign: 'Blaha Lujza tér M',
  },
];

const memberMeta = (id: string) => {
  const map: Record<string, { shortName: string; color: string; textColor: string }> = {
    BKK_3280: { shortName: '28', color: 'FFD800', textColor: '000000' },
    BKK_3281: { shortName: '28A', color: 'FFD800', textColor: '000000' },
    BKK_3370: { shortName: '37', color: 'FFD800', textColor: '000000' },
    BKK_M4: { shortName: 'M4', color: '009645', textColor: 'FFFFFF' },
  };
  return map[id] ?? null;
};

describe('buildAlternativeLeg', () => {
  it('builds a leg from several either/or routes, each with its own headsign', () => {
    const leg = buildAlternativeLeg({
      index: INDEX,
      suggestions: SUGGESTIONS,
      departure: { id: 'BKK_F1', name: 'Teleki László tér' },
      destination: { id: 'BKK_F2', name: 'Blaha Lujza tér' },
      memberMeta,
    });
    expect(leg).not.toBeNull();
    expect(leg!.routes.map((r) => r.routeShortName)).toEqual(['28', '28A', '37']);
    expect(leg!.routes.every((r) => r.headsign === 'Blaha Lujza tér M')).toBe(true);
    expect(leg!.departureStopName).toBe('Teleki László tér');
    expect(leg!.destinationStopName).toBe('Blaha Lujza tér');
    expect(leg!.headsign).toBe('Blaha Lujza tér M');
  });

  it('stores differing per-route headsigns when routes point different directions', () => {
    const m4: RouteSuggestion = {
      routeId: 'BKK_M4',
      routeIds: ['BKK_M4'],
      shortName: 'M4',
      displayName: 'M4',
      type: 'SUBWAY',
      color: '009645',
      textColor: 'FFFFFF',
      headsign: 'Kelenföld vasútállomás M',
    };
    const leg = buildAlternativeLeg({
      index: INDEX,
      suggestions: [m4],
      departure: { id: 'A', name: 'Kálvin tér' },
      destination: { id: 'B', name: 'Fővám tér' },
      memberMeta,
    });
    expect(leg!.routes[0].headsign).toBe('Kelenföld vasútállomás M');
    expect(leg!.headsign).toBe('Kelenföld vasútállomás M');
  });

  it('returns null when a route does not serve the pair (validation)', () => {
    const leg = buildAlternativeLeg({
      index: INDEX,
      suggestions: [SUGGESTIONS[0]],
      departure: { id: 'A', name: 'Teleki László tér' },
      destination: { id: 'B', name: 'Fővám tér' }, // 28/28A never reaches Fővám
      memberMeta,
    });
    expect(leg).toBeNull();
  });

  it('falls back to suggestion headsigns when the index is unavailable', () => {
    const leg = buildAlternativeLeg({
      index: null,
      suggestions: [SUGGESTIONS[1]],
      departure: { id: 'A', name: 'Teleki László tér' },
      destination: { id: 'B', name: 'Blaha Lujza tér' },
      memberMeta,
    });
    expect(leg).not.toBeNull();
    expect(leg!.routes[0].headsign).toBe('Blaha Lujza tér M');
  });

  it('returns null for an empty suggestion list', () => {
    expect(
      buildAlternativeLeg({
        index: INDEX,
        suggestions: [],
        departure: { id: 'A', name: 'X' },
        destination: { id: 'B', name: 'Y' },
        memberMeta,
      })
    ).toBeNull();
  });
});