import { describe, it, expect, vi, afterEach } from 'vitest';
import { accentFold, searchPlaces, clearPlaceCache, type PlaceResult } from './geocode';

const PHOTON_BODY = {
  features: [
    {
      properties: { name: 'Keleti pályaudvar', osm_type: 'N', osm_id: 1, type: 'station', city: 'Budapest' },
      geometry: { coordinates: [19.0814, 47.5005] },
    },
    {
      properties: { name: 'Kelemen utca', osm_type: 'N', osm_id: 2, type: 'street', city: 'Budapest' },
      geometry: { coordinates: [19.0, 47.49] },
    },
    {
      // Photon returns nameless house-number hits — the label must come from
      // street + housenumber (verified live: "nador utca 8" has name: null).
      properties: { name: null, street: 'Nádor utca', housenumber: '8', osm_type: 'N', osm_id: 3, type: 'house', city: 'Budapest' },
      geometry: { coordinates: [19.047, 47.497] },
    },
    {
      // Named POI with a house number — label composes name + street + number.
      properties: { name: 'Krausz palota', street: 'Andrássy út', housenumber: '12', osm_type: 'N', osm_id: 4, type: 'building', city: 'Budapest' },
      geometry: { coordinates: [19.063, 47.51] },
    },
  ],
};

function stubFetch(impl: (url: string) => Promise<{ ok: boolean; status?: number; json: () => Promise<unknown> }>) {
  vi.stubGlobal('fetch', vi.fn(impl));
}

afterEach(() => {
  vi.unstubAllGlobals();
  clearPlaceCache();
});

describe('accentFold', () => {
  it('lowercases and strips diacritics', () => {
    expect(accentFold('Keleti pályaudvar')).toBe('keleti palyaudvar');
    expect(accentFold('MÓRICZ')).toBe('moricz');
  });
});

describe('searchPlaces', () => {
  it('maps Photon features to PlaceResults', async () => {
    stubFetch(async () => ({ ok: true, status: 200, json: async () => PHOTON_BODY }));
    const results = await searchPlaces('keleti');
    expect(results).toEqual<PlaceResult[]>([
      { id: 'N-1', name: 'Keleti pályaudvar', displayName: 'Keleti pályaudvar, Budapest', lat: 47.5005, lon: 19.0814, kind: 'station' },
      { id: 'N-2', name: 'Kelemen utca', displayName: 'Kelemen utca, Budapest', lat: 47.49, lon: 19.0, kind: 'street' },
      { id: 'N-3', name: 'Nádor utca 8', displayName: 'Nádor utca 8, Budapest', lat: 47.497, lon: 19.047, kind: 'house' },
      { id: 'N-4', name: 'Krausz palota Andrássy út 12', displayName: 'Krausz palota Andrássy út 12, Budapest', lat: 47.51, lon: 19.063, kind: 'building' },
    ]);
  });

  it('requests limit 6 and location bias, but never lang (the public instance 400s on it)', async () => {
    let captured = '';
    stubFetch(async (url: string) => {
      captured = url;
      return { ok: true, status: 200, json: async () => ({ features: [] }) };
    });
    await searchPlaces('keleti', { lat: 47.5, lon: 19.05 });
    expect(captured).toContain('limit=6');
    expect(captured).toContain('lat=47.5');
    expect(captured).toContain('lon=19.05');
    expect(captured).not.toContain('lang=');
  });

  it('caches identical queries (single network call)', async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ features: [] }) }));
    vi.stubGlobal('fetch', fetchMock);
    await searchPlaces('Keleti pályaudvar');
    await searchPlaces('keleti palyaudvar'); // same accent-folded key
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('returns [] for an empty query without calling the network', async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ features: [] }) }));
    vi.stubGlobal('fetch', fetchMock);
    expect(await searchPlaces('   ')).toEqual([]);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('throws on a non-OK response', async () => {
    stubFetch(async () => ({ ok: false, status: 503, json: async () => ({}) }));
    await expect(searchPlaces('keleti')).rejects.toThrow(/503/);
  });

  it('evicts a failed lookup so the next search retries', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: false, status: 503, json: async () => ({}) })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ features: [] }) });
    vi.stubGlobal('fetch', fetchMock);
    await expect(searchPlaces('keleti')).rejects.toThrow(/503/);
    await expect(searchPlaces('keleti')).resolves.toEqual([]);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('bounds the request with an abort signal', async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ features: [] }) }));
    vi.stubGlobal('fetch', fetchMock);
    await searchPlaces('keleti');
    const init = (fetchMock.mock.calls[0] as unknown[])[1] as RequestInit | undefined;
    expect(init?.signal).toBeInstanceOf(AbortSignal);
  });
});