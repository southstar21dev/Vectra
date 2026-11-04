import type { LatLon } from '@/features/transit/route-geometry';
import { normalizeStopName } from './route-variants';

/** A stop reference with coordinates, as resolved from route-details refs. */
export interface ClipStop {
  id: string;
  name: string;
  lat: number;
  lon: number;
}

/** Result of clipping a variant polyline to the journey being planned. */
export interface RouteClip {
  /** Line start → boarding stop. Drawn thin/grey (you're not riding this yet). */
  approach: LatLon[];
  /** Boarding stop → destination. Drawn thick/colored (the ride). */
  taken: LatLon[];
  /** Indexes into `stops` of the boarding and destination stops (inclusive range). */
  fromIndex: number;
  toIndex: number;
}

/**
 * Slice one direction's polyline to the planned journey. A variant only counts
 * when it serves the departure stop BEFORE the destination stop (the correct
 * direction); the returned `taken` segment runs from the boarding stop's
 * nearest polyline vertex to the destination's, and `approach` runs from the
 * line start to the boarding stop. Returns null when the pair isn't served in
 * this direction (or a stop has no coordinates / can't be snapped).
 */
export function clipRouteToJourney(
  polyline: LatLon[],
  stops: ClipStop[],
  fromName: string,
  toName: string
): RouteClip | null {
  if (polyline.length < 2 || stops.length === 0) return null;
  const normFrom = normalizeStopName(fromName);
  const normTo = normalizeStopName(toName);
  if (!normFrom || !normTo) return null;

  const fromIndex = stops.findIndex((s) => normalizeStopName(s.name) === normFrom);
  if (fromIndex < 0) return null;
  const toIndex = stops.findIndex((s, i) => i > fromIndex && normalizeStopName(s.name) === normTo);
  if (toIndex < 0) return null;

  const fromStop = stops[fromIndex];
  const toStop = stops[toIndex];
  if ((fromStop.lat === 0 && fromStop.lon === 0) || (toStop.lat === 0 && toStop.lon === 0)) {
    return null;
  }

  const fromPt = nearestVertexIndex(polyline, fromStop);
  const toPt = nearestVertexIndex(polyline, toStop);
  if (fromPt < 0 || toPt <= fromPt) return null;

  return {
    approach: polyline.slice(0, fromPt + 1),
    taken: polyline.slice(fromPt, toPt + 1),
    fromIndex,
    toIndex,
  };
}

/** Index of the polyline vertex closest to the stop (squared lat/lon distance). */
function nearestVertexIndex(polyline: LatLon[], stop: ClipStop): number {
  let best = -1;
  let bestSq = Infinity;
  for (let i = 0; i < polyline.length; i++) {
    const dLat = polyline[i].lat - stop.lat;
    const dLon = polyline[i].lon - stop.lon;
    const sq = dLat * dLat + dLon * dLon;
    if (sq < bestSq) {
      bestSq = sq;
      best = i;
    }
  }
  return best;
}