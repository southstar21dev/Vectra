import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query-keys';
import { findNearbyStops, getCatalogRouteMap, getCatalogStops, type NearbyStop } from './nearby-stops';
import type { CatalogRoute, CatalogStop } from './catalog-types';

/** The full BKK stop catalog (runtime JSON; fetched once per session). */
export function useCatalogStops(): { data?: CatalogStop[]; isLoading: boolean } {
  const query = useQuery({
    queryKey: queryKeys.transitCatalogStops,
    queryFn: getCatalogStops,
    staleTime: Infinity,
    retry: 1,
  });
  return { data: query.data, isLoading: query.isLoading };
}

/** The full BKK route catalog keyed by id (runtime JSON; fetched once per session). */
export function useCatalogRoutes(): { data?: Map<string, CatalogRoute>; isLoading: boolean } {
  const query = useQuery({
    queryKey: queryKeys.transitCatalogRoutes,
    queryFn: getCatalogRouteMap,
    staleTime: Infinity,
    retry: 1,
  });
  return { data: query.data, isLoading: query.isLoading };
}

/** Nearest stops to the anchor, computed client-side from the catalog. */
export function useNearbyStops(lat: number, lon: number): { stops: NearbyStop[]; loading: boolean } {
  const { data, isLoading } = useCatalogStops();
  const stops = useMemo(
    () => (data ? findNearbyStops(data, lat, lon) : []),
    [data, lat, lon]
  );
  return { stops, loading: isLoading };
}
