import { getGeoPosition, readStoredLocation, type GeoPosition } from '@/lib/location';
import { haversineMeters, type LatLon } from '@/features/transit/route-geometry';
import { calculateWalkMinutes } from '@/features/transit/nearby-stops';
import type { CatalogStop } from '@/features/transit/catalog-types';
import type { TripAddress } from './types';
import { nearestStop } from './place-search';
import { reverseGeocode } from './geocode';
import { fetchWalkingRoute } from './walking-route';

const STORED_LOCATION_MAX_AGE_MS = 5 * 60_000;

type StoredPosition = {
  status: string;
  lat: number;
  lon: number;
  lastUpdatedMs: number;
};

/** Prefer a fresh fix, then reuse the same recent watcher fix used by nearby features. */
export function chooseTripAddressPosition(
  fresh: GeoPosition | null,
  stored: StoredPosition | null,
  now = Date.now()
): GeoPosition {
  if (fresh) return fresh;
  if (
    stored?.status === 'tracking' &&
    Number.isFinite(stored.lat) &&
    Number.isFinite(stored.lon) &&
    now - stored.lastUpdatedMs <= STORED_LOCATION_MAX_AGE_MS
  ) {
    return { latitude: stored.lat, longitude: stored.lon };
  }
  throw new Error('Could not determine your current location. Try again or check browser location permissions.');
}

/** Resolve the device location into an address attached to the nearest BKK stop. */
export async function resolveCurrentTripAddress(stops: CatalogStop[]): Promise<TripAddress> {
  let fresh: GeoPosition | null = null;
  try {
    fresh = await getGeoPosition();
  } catch {
    // The shared nearby-location watcher may already have a usable fix even
    // when a new browser one-shot request times out.
  }
  const position = chooseTripAddressPosition(fresh, readStoredLocation() as StoredPosition | null);
  const point: LatLon = { lat: position.latitude, lon: position.longitude };
  const stop = nearestStop(position.latitude, position.longitude, stops);
  if (!stop) {
    throw new Error('No BKK stop was found within 2 km of your location.');
  }

  const [reverseResult, routeResult] = await Promise.allSettled([
    reverseGeocode(position.latitude, position.longitude),
    fetchWalkingRoute(point, stop),
  ]);

  const straightLineMeters = Math.max(1, Math.round(haversineMeters(point, stop)));
  const route = routeResult.status === 'fulfilled' ? routeResult.value : null;
  const address = reverseResult.status === 'fulfilled' ? reverseResult.value : null;

  return {
    name: address?.displayName || address?.name || 'Current location',
    distanceMeters: route?.distanceMeters ?? straightLineMeters,
    walkingMinutes: route?.durationMinutes ?? calculateWalkMinutes(straightLineMeters),
    lat: position.latitude,
    lon: position.longitude,
    stopId: stop.id,
    stopName: stop.name,
  };
}
