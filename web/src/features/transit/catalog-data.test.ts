import { beforeEach, describe, expect, it, vi } from 'vitest';

const loadModule = async () => {
  vi.resetModules();
  return import('./catalog-data');
};

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

const ROUTE = {
  id: 'BKK_5400',
  shortName: 'M4',
  description: 'Kelenföld vasútállomás M | Keleti pályaudvar M',
  type: 'SUBWAY',
  color: '12A13C',
  textColor: 'FFFFFF',
};

beforeEach(() => {
  vi.unstubAllGlobals();
});

describe('catalog runtime loaders', () => {
  it('fetches routes.json and memoizes the result', async () => {
    const fetchMock = vi.fn(async () => jsonResponse([ROUTE]));
    vi.stubGlobal('fetch', fetchMock);
    const { loadCatalogRoutes } = await loadModule();

    const first = await loadCatalogRoutes();
    const second = await loadCatalogRoutes();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith('/catalog/routes.json');
    expect(second).toBe(first);
    expect(first).toEqual([ROUTE]);
  });

  it('fetches stops.json', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse([{ id: 'BKK_CSF01108', name: 'Astoria', lat: 47.494479, lon: 19.060643 }])
    );
    vi.stubGlobal('fetch', fetchMock);
    const { loadCatalogStops } = await loadModule();

    await expect(loadCatalogStops()).resolves.toHaveLength(1);
    expect(fetchMock).toHaveBeenCalledWith('/catalog/stops.json');
  });

  it('fetches route-variants.json', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse({ BKK_3280: [{ h: 'Blaha Lujza tér M', s: ['teleki laszlo ter'] }] })
    );
    vi.stubGlobal('fetch', fetchMock);
    const { loadCatalogRouteVariants } = await loadModule();

    await expect(loadCatalogRouteVariants()).resolves.toHaveProperty('BKK_3280');
    expect(fetchMock).toHaveBeenCalledWith('/catalog/route-variants.json');
  });

  it('maps routes into an id-keyed Map', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse([ROUTE])));
    const { loadCatalogRouteMap } = await loadModule();

    const map = await loadCatalogRouteMap();
    expect(map.get('BKK_5400')?.shortName).toBe('M4');
  });

  it('clears a failed load so the next call retries', async () => {
    let calls = 0;
    const fetchMock = vi.fn(async () => {
      calls += 1;
      return calls === 1 ? new Response('nope', { status: 503 }) : jsonResponse([]);
    });
    vi.stubGlobal('fetch', fetchMock);
    const { loadCatalogRoutes } = await loadModule();

    await expect(loadCatalogRoutes()).rejects.toThrow('HTTP 503');
    await expect(loadCatalogRoutes()).resolves.toEqual([]);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
