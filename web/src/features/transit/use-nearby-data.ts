import { useMemo } from 'react';
import {
  BUDAPEST_CENTER,
  buildRadarDepartures,
  findNearbyStops,
  parseNearbyBoards,
  resolveAnchor,
  type NearbyDeparture,
} from './nearby-stops';
import { useCatalogRoutes, useCatalogStops } from './use-nearby';
import { useDeviceLocation } from '@/lib/location';
import { useBatchStopArrivals, useNow } from './use-transit';

/** Where the nearby views stand on your location. */
export type NearbyLocationStatus = 'tracking' | 'locating' | 'failed' | 'off';

/** Your position and the catalog stops around it — no departures, so nothing
 *  polls. The map strips use this on its own; `useNearbyData` builds on it. */
export function useNearbyStops() {
  const { state: geoState, refreshLocation } = useDeviceLocation();
  const catalogStops = useCatalogStops();

  const isTracking = geoState.status === 'tracking';
  const anchor = isTracking ? resolveAnchor(geoState.lat, geoState.lon) : null;
  const effectiveLat = anchor?.lat ?? BUDAPEST_CENTER.lat;
  const effectiveLon = anchor?.lon ?? BUDAPEST_CENTER.lon;

  const stops = useMemo(() => {
    if (!catalogStops.data) return [];
    return findNearbyStops(catalogStops.data, effectiveLat, effectiveLon, {
      radiusMeters: 500,
      limit: 16,
    });
  }, [catalogStops.data, effectiveLat, effectiveLon]);

  const anchorLat = anchor?.lat ?? null;
  const anchorLon = anchor?.lon ?? null;
  const position = useMemo(
    () => (anchorLat != null && anchorLon != null ? { lat: anchorLat, lon: anchorLon } : null),
    [anchorLat, anchorLon]
  );

  const status: NearbyLocationStatus =
    isTracking && position !== null
      ? 'tracking'
      : geoState.status === 'locating'
        ? 'locating'
        : geoState.status === 'error'
          ? 'failed'
          : 'off';

  return { stops, position, geoState, status, refreshLocation };
}

/** Compact nearby-departures feed for the dashboard transit card. `modeFilter`
 *  narrows the departures to the chosen transit modes (empty = all). */
export function useNearbyData(modeFilter?: readonly string[]) {
  const nearby = useNearbyStops();
  const { stops } = nearby;
  const now = useNow(1000);
  const catalogRoutes = useCatalogRoutes();

  const candidateStopIds = useMemo(() => stops.map((s) => s.id), [stops]);
  const stopArrivalsResults = useBatchStopArrivals(candidateStopIds);

  const stopDeparturesMap = useMemo(() => {
    const map = new Map<string, NearbyDeparture[]>();
    for (const stop of stops) {
      const data = stopArrivalsResults[stop.id]?.data;
      map.set(stop.id, parseNearbyBoards(data, catalogRoutes.data ?? null, now));
    }
    return map;
  }, [stops, stopArrivalsResults, catalogRoutes.data, now]);

  const radarItems = useMemo(
    () => buildRadarDepartures({ stops, stopDeparturesMap, now, modeFilter, maxItems: 24 }),
    [stops, stopDeparturesMap, now, modeFilter]
  );

  return { ...nearby, radarItems };
}
