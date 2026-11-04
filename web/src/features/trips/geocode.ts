import type { LatLon } from '@/features/transit/route-geometry';
import { timeoutSignal } from '@/lib/timeout-signal';

export interface PlaceResult {
  id: string;
  name: string;
  displayName: string;
  lat: number;
  lon: number;
  kind: string;
}

const PHOTON_ENDPOINT = 'https://photon.komoot.io/api/';
/** A hung geocoder must not leave a search spinning (or cached) forever. */
const GEOCODE_TIMEOUT_MS = 10_000;

/** Lowercase + strip diacritics — used for cache keys and stop matching. */
export function accentFold(s: string): string {
  return s.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');
}

/** Normalized query+bias -> in-flight/past promise, so repeat searches never
 *  re-hit the network while typing. Failed lookups (including timeouts) are
 *  evicted so the next search retries instead of replaying the error. */
const cache = new Map<string, Promise<PlaceResult[]>>();

/** Test hook. */
export function clearPlaceCache(): void {
  cache.clear();
}

/** Free-text place/address search via Photon (komoot), no API key. */
export function searchPlaces(query: string, bias?: LatLon): Promise<PlaceResult[]> {
  const q = query.trim();
  if (!q) return Promise.resolve([]);
  const key = `${accentFold(q)}|${bias ? `${bias.lat},${bias.lon}` : ''}`;
  const hit = cache.get(key);
  if (hit) return hit;
  const promise = fetchPlaces(q, bias);
  cache.set(key, promise);
  promise.catch(() => {
    if (cache.get(key) === promise) cache.delete(key);
  });
  return promise;
}

/** Reverse-geocode coordinates for the current-location trip address. */
export async function reverseGeocode(lat: number, lon: number): Promise<PlaceResult | null> {
  const url = new URL('https://photon.komoot.io/reverse');
  url.searchParams.set('lat', String(lat));
  url.searchParams.set('lon', String(lon));
  const res = await fetch(url.toString(), { signal: timeoutSignal(GEOCODE_TIMEOUT_MS) });
  if (!res.ok) throw new Error(`Reverse geocoding failed (HTTP ${res.status})`);
  const json = (await res.json()) as { features?: Array<Record<string, any>> };
  const feature = json.features?.[0];
  if (!feature) return null;
  const props = feature.properties ?? {};
  const name = typeof props.name === 'string' ? props.name : '';
  const street = typeof props.street === 'string' ? props.street : '';
  const housenumber = typeof props.housenumber === 'string' ? props.housenumber : '';
  const label = [name, street, housenumber].filter(Boolean).join(' ');
  const area = props.city || props.county || props.state || props.country || '';
  return {
    id: `${props.osm_type ?? 'reverse'}-${props.osm_id ?? 'location'}`,
    name: label || 'Current location',
    displayName: area && label ? `${label}, ${area}` : label || 'Current location',
    lat,
    lon,
    kind: typeof props.type === 'string' ? props.type : 'place',
  };
}

async function fetchPlaces(query: string, bias?: LatLon): Promise<PlaceResult[]> {
  const url = new URL(PHOTON_ENDPOINT);
  url.searchParams.set('q', query);
  url.searchParams.set('limit', '6');
  // NOTE: do NOT add `lang` — the public photon.komoot.io instance rejects
  // any request containing it with HTTP 400 (verified live).
  if (bias) {
    url.searchParams.set('lat', String(bias.lat));
    url.searchParams.set('lon', String(bias.lon));
  }
  const res = await fetch(url.toString(), { signal: timeoutSignal(GEOCODE_TIMEOUT_MS) });
  if (!res.ok) {
    throw new Error(`Place search failed (HTTP ${res.status})`);
  }
  const json = (await res.json()) as { features?: Array<Record<string, any>> };
  const features = json?.features ?? [];
  return features.map((f, i) => {
    const props = f?.properties ?? {};
    const coords = f?.geometry?.coordinates ?? [];
    // Photon puts house-number matches in street + housenumber with name null
    // (verified live: "nádor utca 8" -> { street: 'Nádor utca', housenumber: '8' }),
    // so the label composes whichever parts exist.
    const name = typeof props.name === 'string' ? props.name : '';
    const street = typeof props.street === 'string' ? props.street : '';
    const housenumber = typeof props.housenumber === 'string' ? props.housenumber : '';
    const label = [name, street, housenumber].filter(Boolean).join(' ');
    const area = props.city || props.county || props.state || props.country || '';
    const lat = Number(coords[1]);
    const lon = Number(coords[0]);
    return {
      id: `${props.osm_type ?? 'osm'}-${props.osm_id ?? i}`,
      name: label,
      displayName: area ? `${label}, ${area}` : label,
      lat: Number.isFinite(lat) ? lat : 0,
      lon: Number.isFinite(lon) ? lon : 0,
      kind: typeof props.type === 'string' ? props.type : '',
    };
  });
}