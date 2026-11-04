import type { CatalogRoute, CatalogRouteVariant, CatalogStop } from './catalog-types';

/**
 * Runtime loaders for the daily-refreshed BKK catalog JSON (public/catalog/*.json).
 *
 * The catalog deliberately lives outside the JS bundle: shipping it as hashed
 * chunks changed the service-worker precache manifest on every daily refresh,
 * which forced an update reload (and a second boot splash) on installed PWAs.
 * Fetching it at runtime keeps app builds and data refreshes independent; the
 * service worker caches /catalog/ network-first with an offline fallback.
 */
const CATALOG_BASE = `${import.meta.env.BASE_URL}catalog/`;

async function fetchCatalog<T>(file: string): Promise<T> {
  const res = await fetch(`${CATALOG_BASE}${file}`);
  if (!res.ok) throw new Error(`Failed to load catalog ${file} (HTTP ${res.status})`);
  return (await res.json()) as T;
}

/** One load per dataset per session; a failed load clears so it can be retried. */
function lazy<T>(loader: () => Promise<T>): () => Promise<T> {
  let promise: Promise<T> | null = null;
  return () => {
    promise ??= loader().catch((err: unknown) => {
      promise = null;
      throw err;
    });
    return promise;
  };
}

export const loadCatalogRoutes = lazy(() => fetchCatalog<CatalogRoute[]>('routes.json'));

export const loadCatalogStops = lazy(() => fetchCatalog<CatalogStop[]>('stops.json'));

export const loadCatalogRouteVariants = lazy(() =>
  fetchCatalog<Record<string, CatalogRouteVariant[]>>('route-variants.json')
);

export const loadCatalogRouteMap = lazy(async () => {
  const map = new Map<string, CatalogRoute>();
  for (const r of await loadCatalogRoutes()) map.set(r.id, r);
  return map;
});
