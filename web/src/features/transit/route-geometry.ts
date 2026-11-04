import type { FutarRouteVariant, FutarStop, FutarTripDetailsResponse } from './types';

/** Geographic coordinate. */
export interface LatLon {
  lat: number;
  lon: number;
}

/**
 * Camera-fit key for a whole set of drawn geometry. Covers EVERY polyline's
 * endpoints + length AND every walk's endpoints, so a map refits whenever the
 * journey changes — a new leg added, a walk appearing, an address picked — not
 * just when the first polyline's start/length happens to change. Returns ''
 * when there is nothing to fit. (Keying on only the first polyline left the
 * camera stuck on the first leg after a transfer leg was added, so the rest
 * of the journey — and its transfer walks — stayed off-screen.)
 */
export function journeyFitKey(polylines: LatLon[][], walks: LatLon[][] = []): string {
  const parts: string[] = [];
  for (const pl of polylines) {
    if (pl.length < 2) continue;
    const a = pl[0];
    const b = pl[pl.length - 1];
    parts.push(`${a.lat.toFixed(5)},${a.lon.toFixed(5)}:${b.lat.toFixed(5)},${b.lon.toFixed(5)}:${pl.length}`);
  }
  for (const w of walks) {
    if (w.length < 2) continue;
    parts.push(`w${w[0].lat.toFixed(5)},${w[0].lon.toFixed(5)}-${w[1].lat.toFixed(5)},${w[1].lon.toFixed(5)}`);
  }
  return parts.join('|');
}

/** A point in a 2D (projected / SVG) coordinate space. */
export interface Point {
  x: number;
  y: number;
}

/**
 * Decode a Google-encoded polyline (the `polyline.points` field of Futár's
 * trip-details response) into lat/lon pairs. Standard algorithm, 1e-5 precision.
 */
export function decodePolyline(encoded: string): LatLon[] {
  const points: LatLon[] = [];
  let index = 0;
  let lat = 0;
  let lon = 0;
  while (index < encoded.length) {
    let result = 0;
    let shift = 0;
    let b: number;
    do {
      b = encoded.charCodeAt(index++) - 63;
      result |= (b & 0x1f) << shift;
      shift += 5;
    } while (b >= 0x20);
    lat += result & 1 ? ~(result >> 1) : result >> 1;
    result = 0;
    shift = 0;
    do {
      b = encoded.charCodeAt(index++) - 63;
      result |= (b & 0x1f) << shift;
      shift += 5;
    } while (b >= 0x20);
    lon += result & 1 ? ~(result >> 1) : result >> 1;
    // Round to 1e-5 (the API's own precision) — accumulating integer deltas can
    // otherwise leave float dust like -126.45300000000002 on the last point.
    points.push({ lat: Math.round(lat * 1e-5 * 1e5) / 1e5, lon: Math.round(lon * 1e-5 * 1e5) / 1e5 });
  }
  return points;
}

/** Normalize a headsign for matching: trim, lowercase, strip diacritics and
 *  collapse internal whitespace. */
function normalizeHeadsign(value: string): string {
  return value
    .trim()
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/\s+/g, ' ');
}

/**
 * Select entries matching `headsign`. Exact match wins, then a normalized
 * match; when neither exists (or no headsign was given) every entry is
 * returned. Shared by the polyline and stop-id selectors.
 */
function matchByHeadsign<T extends { headsign?: string }>(
  entries: T[],
  headsign?: string | null
): T[] {
  if (entries.length === 0) return [];

  const wanted = headsign?.trim();
  if (wanted) {
    const exact = entries.filter((entry) => (entry.headsign ?? '') === wanted);
    if (exact.length > 0) return exact;

    const normalized = normalizeHeadsign(wanted);
    const fuzzy = entries.filter(
      (entry) => normalizeHeadsign(entry.headsign ?? '') === normalized
    );
    if (fuzzy.length > 0) return fuzzy;
  }

  return entries;
}

/**
 * Narrow variants to the selected member route (combined lines tag each merged
 * variant with its source route id), then apply the shared headsign matching.
 * The route filter is ignored when no variant carries `routeId`, so untagged
 * responses keep the old headsign-only behavior.
 */
function matchingVariants<T extends { headsign?: string; routeId?: string }>(
  variants: T[],
  headsign?: string | null,
  routeId?: string | null
): T[] {
  const scoped = routeId ? variants.filter((variant) => variant.routeId === routeId) : [];
  return matchByHeadsign(scoped.length > 0 ? scoped : variants, headsign);
}

/**
 * Decode the variant polylines matching `headsign` (and `routeId` when the
 * variants are tagged with one). Variants with fewer than two points are
 * dropped.
 */
export function selectVariantPolylines(
  variants: FutarRouteVariant[],
  headsign?: string | null,
  routeId?: string | null
): LatLon[][] {
  const decoded = variants
    .map((variant) => ({
      headsign: variant.headsign ?? '',
      routeId: variant.routeId,
      points: variant.polyline?.points ? decodePolyline(variant.polyline.points) : [],
    }))
    .filter((entry) => entry.points.length >= 2);

  return matchingVariants(decoded, headsign, routeId).map((entry) => entry.points);
}

/**
 * The stop ids of the variants matching `headsign` (and `routeId` when the
 * variants are tagged with one), concatenated in order with duplicates
 * collapsed (first occurrence wins). Used to place the selected route's own
 * stops on the map without the viewport stop cap.
 */
export function selectVariantStopIds(
  variants: FutarRouteVariant[],
  headsign?: string | null,
  routeId?: string | null
): string[] {
  const matched = matchingVariants(variants, headsign, routeId);
  const seen = new Set<string>();
  const ids: string[] = [];
  for (const variant of matched) {
    for (const id of variant.stopIds ?? []) {
      if (seen.has(id)) continue;
      seen.add(id);
      ids.push(id);
    }
  }
  return ids;
}

/**
 * Decode a trip-details response's own polyline (`entry.polyline.points`).
 * Empty when the response carries none — callers fall back to the
 * route-details variant selection.
 */
export function selectTripPolyline(trip: FutarTripDetailsResponse | undefined): LatLon[] {
  const points = trip?.data?.entry?.polyline?.points;
  return points ? decodePolyline(points) : [];
}

/** The trip's exact ordered stop ids from trip-details, deduped (first wins). */
export function selectTripStopIds(trip: FutarTripDetailsResponse | undefined): string[] {
  const seen = new Set<string>();
  const ids: string[] = [];
  for (const stopTime of trip?.data?.entry?.stopTimes ?? []) {
    const id = stopTime.stopId;
    if (!id || seen.has(id)) continue;
    seen.add(id);
    ids.push(id);
  }
  return ids;
}

/**
 * Resolve ordered stop ids against a `references.stops` map, dropping missing
 * and zero-coordinate entries.
 */
export function resolveStopRefs(
  stopIds: string[],
  refs: Record<string, FutarStop> | undefined
): Array<{ id: string; name: string; lat: number; lon: number }> {
  const out: Array<{ id: string; name: string; lat: number; lon: number }> = [];
  for (const id of stopIds) {
    const ref = refs?.[id];
    if (!ref || (ref.lat === 0 && ref.lon === 0)) continue;
    out.push({ id: ref.id, name: ref.name, lat: ref.lat, lon: ref.lon });
  }
  return out;
}

/**
 * Build a projector that maps lat/lon points into a `width` × `height` viewBox.
 * Equirectangular (linear in lon/lat — fine at city scale), uniform scale, 8%
 * padding, north up. The same transform is applied to everything (line, stops,
 * vehicles), so points outside the given bounds still land consistently.
 */
export function makeProjector(
  latLons: LatLon[],
  width: number,
  height: number
): (p: LatLon) => Point {
  const PAD = 0.08;
  if (latLons.length === 0) return () => ({ x: width / 2, y: height / 2 });

  let minLon = Infinity;
  let maxLon = -Infinity;
  let minLat = Infinity;
  let maxLat = -Infinity;
  for (const p of latLons) {
    if (p.lon < minLon) minLon = p.lon;
    if (p.lon > maxLon) maxLon = p.lon;
    if (p.lat < minLat) minLat = p.lat;
    if (p.lat > maxLat) maxLat = p.lat;
  }

  const spanLon = maxLon - minLon;
  const spanLat = maxLat - minLat;
  const usableW = width * (1 - 2 * PAD);
  const usableH = height * (1 - 2 * PAD);
  const scale =
    spanLon === 0 && spanLat === 0
      ? 1
      : Math.min(
          spanLon === 0 ? Infinity : usableW / spanLon,
          spanLat === 0 ? Infinity : usableH / spanLat
        );
  const cx = (minLon + maxLon) / 2;
  const cy = (minLat + maxLat) / 2;

  return (p: LatLon) => ({
    x: width / 2 + (p.lon - cx) * scale,
    y: height / 2 - (p.lat - cy) * scale,
  });
}

/**
 * Project `p` onto the polyline: the closest point on the nearest segment, plus
 * the cumulative distance along the polyline to that point. Returns null for an
 * empty polyline. `point` is clamped to the ends when `p` lies beyond them.
 */
export function projectVehicleOntoPolyline(
  p: Point,
  polyline: Point[]
): { point: Point; distance: number } | null {
  if (polyline.length === 0) return null;
  if (polyline.length === 1) return { point: polyline[0], distance: 0 };

  let best: { point: Point; distanceSq: number; segStart: number } | null = null;

  for (let i = 0; i < polyline.length - 1; i++) {
    const a = polyline[i];
    const b = polyline[i + 1];
    const abx = b.x - a.x;
    const aby = b.y - a.y;
    const lenSq = abx * abx + aby * aby;
    let t = lenSq === 0 ? 0 : ((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq;
    t = Math.max(0, Math.min(1, t));
    const px = a.x + t * abx;
    const py = a.y + t * aby;
    const distSq = (p.x - px) ** 2 + (p.y - py) ** 2;
    if (!best || distSq < best.distanceSq) {
      best = { point: { x: px, y: py }, distanceSq: distSq, segStart: i };
    }
  }

  if (!best) return null;

  let distance = 0;
  for (let i = 0; i < best.segStart; i++) {
    const a = polyline[i];
    const b = polyline[i + 1];
    distance += Math.hypot(b.x - a.x, b.y - a.y);
  }
  const a = polyline[best.segStart];
  distance += Math.hypot(best.point.x - a.x, best.point.y - a.y);

  return { point: best.point, distance };
}

/** Cumulative polyline length at each vertex (first is 0). */
export function cumulativeDistances(points: Point[]): number[] {
  const out: number[] = [0];
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1];
    const b = points[i];
    out.push(out[i - 1] + Math.hypot(b.x - a.x, b.y - a.y));
  }
  return out;
}

/** Index of the first stop the vehicle has NOT yet reached; clamps to the last. */
export function nextStopIndex(stopDistances: number[], vehicleDistance: number): number {
  const i = stopDistances.findIndex((d) => d >= vehicleDistance);
  return i === -1 ? stopDistances.length - 1 : i;
}

/** Haversine distance in meters between two lat/lon points (pure, no deps). */
export function haversineMeters(a: LatLon, b: LatLon): number {
  const R = 6_371_000;
  const rad = (d: number) => (d * Math.PI) / 180;
  const dLat = rad(b.lat - a.lat);
  const dLon = rad(b.lon - a.lon);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

/**
 * The point at `fraction` (0..1, clamped) of the polyline's total geographic
 * length, interpolated along the containing segment. Null for an empty
 * polyline. Pure — used to place schedule-approximated vehicles on the line.
 */
export function pointAtFraction(polyline: LatLon[], fraction: number): LatLon | null {
  if (polyline.length === 0) return null;
  const f = Math.max(0, Math.min(1, fraction));
  if (polyline.length === 1 || f <= 0) return polyline[0];
  if (f >= 1) return polyline[polyline.length - 1];

  const dists: number[] = [0];
  for (let i = 1; i < polyline.length; i++) {
    dists.push(dists[i - 1] + haversineMeters(polyline[i - 1], polyline[i]));
  }
  const target = f * dists[dists.length - 1];
  for (let i = 0; i < polyline.length - 1; i++) {
    if (target <= dists[i + 1] || i === polyline.length - 2) {
      const seg = dists[i + 1] - dists[i];
      const t = seg <= 0 ? 0 : (target - dists[i]) / seg;
      return {
        lat: polyline[i].lat + (polyline[i + 1].lat - polyline[i].lat) * t,
        lon: polyline[i].lon + (polyline[i + 1].lon - polyline[i].lon) * t,
      };
    }
  }
  return polyline[polyline.length - 1];
}

/**
 * Cumulative geographic (haversine) length in meters at each vertex of a
 * polyline — first vertex is 0. Shared by the on-route snap and the
 * along-the-line animation so both measure the path identically.
 */
export function cumulativeHaversineDistances(polyline: LatLon[]): number[] {
  const out: number[] = [0];
  for (let i = 1; i < polyline.length; i++) {
    out.push(out[i - 1] + haversineMeters(polyline[i - 1], polyline[i]));
  }
  return out;
}

/**
 * The point at cumulative distance `d` meters along the polyline (clamped to
 * its ends), interpolated within the containing segment. `dists` must be
 * `cumulativeHaversineDistances(polyline)`. Pure — used to move the live
 * tracker dot smoothly along the route between GPS updates.
 */
export function pointAtDistance(polyline: LatLon[], dists: number[], d: number): LatLon {
  if (polyline.length === 0) return { lat: 0, lon: 0 };
  if (polyline.length === 1) return polyline[0];
  const total = dists[dists.length - 1] || 1;
  const target = Math.max(0, Math.min(total, d));
  if (target <= 0) return polyline[0];
  if (target >= total) return polyline[polyline.length - 1];
  for (let i = 0; i < polyline.length - 1; i++) {
    if (target <= dists[i + 1] || i === polyline.length - 2) {
      const segLen = dists[i + 1] - dists[i];
      const t = segLen <= 0 ? 0 : (target - dists[i]) / segLen;
      return {
        lat: polyline[i].lat + (polyline[i + 1].lat - polyline[i].lat) * t,
        lon: polyline[i].lon + (polyline[i + 1].lon - polyline[i].lon) * t,
      };
    }
  }
  return polyline[polyline.length - 1];
}

/**
 * The polyline prefix from its start up to cumulative distance `d` meters
 * (clamped to the ends), with the cut point interpolated inside its segment.
 * Returns [lat, lon] pairs (the map layer convention). Used to draw the
 * traveled segment and to clip the tracker map to the approach up to the
 * tracked stop. `dists` must be `cumulativeHaversineDistances(polyline)`.
 */
export function cutPolylineAtDistance(polyline: LatLon[], dists: number[], d: number): [number, number][] {
  if (polyline.length === 0) return [];
  const total = dists[dists.length - 1] || 1;
  const target = Math.max(0, Math.min(total, d));
  const out: [number, number][] = [[polyline[0].lat, polyline[0].lon]];
  for (let i = 0; i < polyline.length - 1; i++) {
    if (target <= dists[i + 1] || i === polyline.length - 2) {
      const segLen = dists[i + 1] - dists[i];
      const t = segLen <= 0 ? 0 : (target - dists[i]) / segLen;
      out.push([
        polyline[i].lat + (polyline[i + 1].lat - polyline[i].lat) * t,
        polyline[i].lon + (polyline[i + 1].lon - polyline[i].lon) * t,
      ]);
      return out;
    }
    out.push([polyline[i + 1].lat, polyline[i + 1].lon]);
  }
  return out;
}

/**
 * Snap `p` onto the polyline: the nearest point on the closest segment, plus
 * its cumulative distance (meters) along the polyline. Clamped to the ends
 * when `p` lies beyond them; null for an empty polyline. This is what keeps
 * the live tracker dot glued to the route despite GPS noise. (Segment search
 * in raw lat/lon space matches the equirectangular projector's result — that
 * transform is a uniform affine map, which preserves nearest-segment
 * projection.)
 */
/** Difference between two headings in degrees (0..180). */
export function angleDifference(a: number, b: number): number {
  const diff = Math.abs(a - b) % 360;
  return diff > 180 ? 360 - diff : diff;
}

export function snapToPolyline(
  p: LatLon,
  polyline: LatLon[],
  preferredBearing?: number
): { point: LatLon; distance: number } | null {
  if (polyline.length === 0) return null;
  if (polyline.length === 1) return { point: polyline[0], distance: 0 };

  let best: { seg: number; t: number; distSq: number } | null = null;
  let bestMatchingBearing: { seg: number; t: number; distSq: number } | null = null;

  for (let i = 0; i < polyline.length - 1; i++) {
    const a = polyline[i];
    const b = polyline[i + 1];
    const dLat = b.lat - a.lat;
    const dLon = b.lon - a.lon;
    const lenSq = dLat * dLat + dLon * dLon;
    let t = lenSq === 0 ? 0 : ((p.lat - a.lat) * dLat + (p.lon - a.lon) * dLon) / lenSq;
    t = Math.max(0, Math.min(1, t));
    const lat = a.lat + t * dLat;
    const lon = a.lon + t * dLon;
    const distSq = (p.lat - lat) ** 2 + (p.lon - lon) ** 2;
    if (!best || distSq < best.distSq) best = { seg: i, t, distSq };

    if (
      preferredBearing != null &&
      Number.isFinite(preferredBearing) &&
      preferredBearing >= 0 &&
      lenSq > 1e-12
    ) {
      const segBearing = bearingDegrees(a, b);
      if (angleDifference(segBearing, preferredBearing) <= 90) {
        if (!bestMatchingBearing || distSq < bestMatchingBearing.distSq) {
          bestMatchingBearing = { seg: i, t, distSq };
        }
      }
    }
  }

  // Favor segments heading in the same direction to avoid snapping to oncoming lanes
  const chosen =
    bestMatchingBearing && best && bestMatchingBearing.distSq <= Math.max(best.distSq * 4, 1e-6)
      ? bestMatchingBearing
      : best;

  if (!chosen) return null;

  const a = polyline[chosen.seg];
  const b = polyline[chosen.seg + 1];
  const point = { lat: a.lat + chosen.t * (b.lat - a.lat), lon: a.lon + chosen.t * (b.lon - a.lon) };
  let distance = 0;
  for (let i = 0; i < chosen.seg; i++) {
    distance += haversineMeters(polyline[i], polyline[i + 1]);
  }
  distance += haversineMeters(a, point);
  return { point, distance };
}

/** A vehicle snapped onto a route polyline, facing along the direction of travel. */
export interface PlacedVehicle {
  id: string;
  lat: number;
  lon: number;
  /** Bearing in degrees: 0 = north, clockwise. */
  bearing: number;
}

/** Initial bearing from `from` to `to` in degrees (0 = north, clockwise). */
export function bearingDegrees(from: LatLon, to: LatLon): number {
  const toRad = (d: number) => (d * Math.PI) / 180;
  const y = Math.sin(toRad(to.lon - from.lon)) * Math.cos(toRad(to.lat));
  const x =
    Math.cos(toRad(from.lat)) * Math.sin(toRad(to.lat)) -
    Math.sin(toRad(from.lat)) * Math.cos(toRad(to.lat)) * Math.cos(toRad(to.lon - from.lon));
  return ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;
}

/**
 * Calculates the forward tangent bearing in degrees (0 = north, clockwise)
 * along a polyline at a specified cumulative distance.
 */
export function bearingAlongPolyline(
  polyline: LatLon[],
  dists: number[],
  distance: number,
  lookAheadMeters = 20
): number {
  if (polyline.length < 2 || dists.length < 2) return 0;
  const total = dists[dists.length - 1] || 1;
  const current = Math.max(0, Math.min(total, distance));
  const ahead = Math.min(total, current + lookAheadMeters);
  const behind = Math.max(0, current - lookAheadMeters);
  const forward = ahead - current >= 0.5;
  const currentPt = pointAtDistance(polyline, dists, current);
  const targetPt = pointAtDistance(polyline, dists, forward ? ahead : behind);
  return forward
    ? bearingDegrees(currentPt, targetPt)
    : bearingDegrees(targetPt, currentPt);
}

/**
 * Snap each vehicle onto the polyline and point it along the direction of
 * travel. If telemetry bearing is provided, it is preserved and used to select
 * the matching road direction; otherwise it calculates tangent bearing along the line.
 */
export function placeVehiclesOnPolyline(
  vehicles: Array<{ id: string; lat: number; lon: number; bearing?: number }>,
  polyline: LatLon[],
  lookAheadMeters = 30
): PlacedVehicle[] {
  if (polyline.length < 2) {
    return vehicles.map((v) => ({ id: v.id, lat: v.lat, lon: v.lon, bearing: v.bearing ?? 0 }));
  }
  const dists = cumulativeHaversineDistances(polyline);

  return vehicles.map((v) => {
    const snapped = snapToPolyline({ lat: v.lat, lon: v.lon }, polyline, v.bearing);
    if (!snapped) return { id: v.id, lat: v.lat, lon: v.lon, bearing: v.bearing ?? 0 };
    const polyBearing = bearingAlongPolyline(polyline, dists, snapped.distance, lookAheadMeters);
    const hasTelemetry = v.bearing != null && Number.isFinite(v.bearing) && v.bearing >= 0;
    const bearing = hasTelemetry
      ? (angleDifference(polyBearing, v.bearing!) <= 45 ? polyBearing : v.bearing!)
      : polyBearing;
    return {
      id: v.id,
      lat: snapped.point.lat,
      lon: snapped.point.lon,
      bearing,
    };
  });
}

