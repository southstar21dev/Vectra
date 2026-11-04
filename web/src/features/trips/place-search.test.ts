import { describe, it, expect, vi, afterEach } from 'vitest';
import { searchPlacesAndStops, nearestStop, ADDRESS_SNAP_MAX_METERS } from './place-search';
import { clearPlaceCache } from './geocode';
import type { CatalogStop } from '@/features/transit/catalog-types';

const STOPS: CatalogStop[] = [
  { id: 'BKK_CS011732', name: 'Deák Ferenc tér', lat: 47.4979, lon: 19.0556 },
  { id: 'BKK_CSF01131', name: 'Keleti pályaudvar', lat: 47.5005, lon: 19.0814 },
  { id: 'BKK_CSF01129', name: 'Keleti pályaudvar M', lat: 47.5006, lon: 19.0815 },
  { id: 'BKK_CSF01116', name: 'Blaha Lujza tér', lat: 47.49665, lon: 19.070596 },
  { id: 'BKK_CSF01330', name: 'Teleki László tér', lat: 47.49016, lon: 19.0845 },
];

afterEach(() => {
  vi.unstubAllGlobals();
  clearPlaceCache();
});

function stubPhoton(features: Array<Record<string, any>> = []) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ features }) }))
  );
}

describe('searchPlacesAndStops', () => {
  it('ranks exact stop-name matches first, then stops, then places', async () => {
    stubPhoton([
      { properties: { name: 'Keleti kávézó', osm_type: 'N', osm_id: 9, type: 'cafe', city: 'Budapest' }, geometry: { coordinates: [19.06, 47.5] } },
    ]);
    const results = await searchPlacesAndStops('keleti', STOPS);
    expect(results[0].kind).toBe('stop');
    expect(results[0].label).toBe('Keleti pályaudvar'); // exact accent-folded match
    expect(results[1].kind).toBe('stop');
    expect(results[1].label).toBe('Keleti pályaudvar M');
    expect(results[results.length - 1].kind).toBe('place');
    expect(results[results.length - 1].label).toBe('Keleti kávézó');
  });

  it('keeps an exact match even when more than 8 stops contain the query', async () => {
    stubPhoton();
    const many: CatalogStop[] = [
      ...Array.from({ length: 10 }, (_, i) => ({ id: `S${i}`, name: `Deák Ferenc tér ${i}`, lat: 47.4979, lon: 19.0556 })),
      { id: 'EXACT', name: 'Deák Ferenc tér', lat: 47.4979, lon: 19.0556 },
    ];
    const results = await searchPlacesAndStops('deak ferenc ter', many);
    expect(results).toHaveLength(8);
    expect(results[0].stopId).toBe('EXACT');
  });

  it('marks stops with their stopId', async () => {
    stubPhoton();
    const results = await searchPlacesAndStops('deak', STOPS);
    const stop = results.find((r) => r.kind === 'stop');
    expect(stop?.stopId).toBe('BKK_CS011732');
    expect(stop?.sublabel).toBe('BKK stop');
  });

  it('matches a stop when the query is the stop name plus extra text (e.g. an address suffix)', async () => {
    stubPhoton();
    const results = await searchPlacesAndStops('teleki laszlo ter 19', STOPS);
    const stop = results.find((r) => r.kind === 'stop');
    expect(stop?.label).toBe('Teleki László tér');
    expect(stop?.stopId).toBe('BKK_CSF01330');
  });

  it('snaps places to the nearest stop within the radius', async () => {
    // ~250 m south of Blaha Lujza tér
    stubPhoton([
      { properties: { name: 'Népszínház utca 4', osm_type: 'N', osm_id: 7, type: 'address', city: 'Budapest' }, geometry: { coordinates: [19.0705, 47.4948] } },
    ]);
    const results = await searchPlacesAndStops('nepszinhaz', STOPS);
    const place = results.find((r) => r.kind === 'place')!;
    expect(place.stopId).toBe('BKK_CSF01116');
    expect(place.sublabel).toContain('Blaha Lujza tér');
    expect(place.sublabel).toContain('m'); // distance shown
  });

  it('hides places outside the BKK stop service area', async () => {
    // ~8 km away — outside the snap radius, so it is not a valid trip address.
    stubPhoton([
      { properties: { name: 'Hűvösvölgy', osm_type: 'N', osm_id: 8, type: 'suburb', city: 'Budapest' }, geometry: { coordinates: [18.96, 47.54] } },
    ]);
    const results = await searchPlacesAndStops('huvosvolgy', STOPS);
    expect(results.some((r) => r.kind === 'place')).toBe(false);
  });

  it('falls back to stops only when Photon fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 503, json: async () => ({}) })));
    const results = await searchPlacesAndStops('keleti', STOPS);
    expect(results.every((r) => r.kind === 'stop')).toBe(true);
    expect(results.length).toBeGreaterThan(0);
  });

  it('returns [] for an empty query', async () => {
    expect(await searchPlacesAndStops('   ', STOPS)).toEqual([]);
  });
});

describe('nearestStop', () => {
  it('returns the closest stop', () => {
    // directly at Blaha Lujza tér's coordinates
    const stop = nearestStop(47.49665, 19.070596, STOPS);
    expect(stop?.id).toBe('BKK_CSF01116');
  });

  it('returns null beyond the snap radius', () => {
    const stop = nearestStop(47.55, 19.2, STOPS);
    expect(stop).toBeNull();
  });

  it('uses the exported radius constant', () => {
    expect(ADDRESS_SNAP_MAX_METERS).toBeGreaterThan(500);
  });
});