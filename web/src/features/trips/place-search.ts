import type { CatalogStop } from '@/features/transit/catalog-types';
import type { LatLon } from '@/features/transit/route-geometry';
import { haversineMeters } from '@/features/transit/route-geometry';
import { formatDistanceMeters } from '@/features/transit/nearby-stops';
import { accentFold, searchPlaces } from './geocode';

/** Furthest an address can be from a stop and still snap to it. */
export const ADDRESS_SNAP_MAX_METERS = 2000;

export interface PlaceSearchResult {
  id: string;
  label: string;
  sublabel: string;
  kind: 'stop' | 'place';
  lat: number;
  lon: number;
  /** Stops: their own id. Places: the snapped stop's id (when within radius). */
  stopId?: string;
  snappedStopName?: string;
  snappedDistanceMeters?: number;
}

/** Closest catalog stop to a point, or null beyond `ADDRESS_SNAP_MAX_METERS`. */
export function nearestStop(
  lat: number,
  lon: number,
  stops: CatalogStop[],
  maxMeters: number = ADDRESS_SNAP_MAX_METERS
): CatalogStop | null {
  let best: CatalogStop | null = null;
  let bestDist = Infinity;
  for (const s of stops) {
    const d = haversineMeters({ lat, lon }, s);
    if (d < bestDist) {
      bestDist = d;
      best = s;
    }
  }
  return best && bestDist <= maxMeters ? best : null;
}

/**
 * Merged origin/destination typeahead: offline BKK stops (accent-folded
 * substring, exact matches first) + Photon places snapped to their nearest
 * stop. Photon failures degrade to stop-only results. Stops are injected so
 * tests never touch the network for the catalog half.
 */
export async function searchPlacesAndStops(
  query: string,
  stops: CatalogStop[],
  bias?: LatLon
): Promise<PlaceSearchResult[]> {
  const q = query.trim();
  if (!q) return [];
  const folded = accentFold(q);

  // Match stops whose name contains the query OR the query contains the stop
  // name — so "teleki laszlo ter 19" still surfaces "Teleki László tér".
  // Exact matches move to the front BEFORE the cap, or a busy query ("deak")
  // could fill the 8 slots with longer names and drop the exact stop.
  const matching = stops.filter((s) => {
    const n = accentFold(s.name);
    return n.includes(folded) || folded.includes(n);
  });
  const isExact = (s: CatalogStop) => accentFold(s.name) === folded;
  const stopHits: PlaceSearchResult[] = [...matching.filter(isExact), ...matching.filter((s) => !isExact(s))]
    .slice(0, 8)
    .map((s) => ({
      id: `stop-${s.id}`,
      label: s.name,
      sublabel: 'BKK stop',
      kind: 'stop' as const,
      lat: s.lat,
      lon: s.lon,
      stopId: s.id,
    }));

  let places: PlaceSearchResult[] = [];
  try {
    places = (await searchPlaces(q, bias)).slice(0, 6).flatMap((p) => {
      const snapped = nearestStop(p.lat, p.lon, stops);
      // The catalog is sourced from BKK Futár, so a nearby catalog stop is a
      // more reliable service-area check than a hard-coded city/country box.
      // It also keeps valid BKK agglomeration stops while excluding places in
      // other cities or countries that Photon may return for an ambiguous query.
      if (!snapped) return [];
      const snappedDistanceMeters = Math.round(haversineMeters({ lat: p.lat, lon: p.lon }, snapped));
      return [{
        id: `place-${p.id}`,
        label: p.name,
        sublabel: `≈ ${formatDistanceMeters(snappedDistanceMeters)} from ${snapped.name}`,
        kind: 'place' as const,
        lat: p.lat,
        lon: p.lon,
        stopId: snapped.id,
        snappedStopName: snapped.name,
        snappedDistanceMeters,
      }];
    });
  } catch {
    places = []; // Photon down -> stops still work
  }

  return [...stopHits, ...places];
}