import {
  cumulativeHaversineDistances,
  cutPolylineAtDistance,
  makeProjector,
  nextStopIndex,
  pointAtFraction,
  projectVehicleOntoPolyline,
  snapToPolyline,
  type LatLon,
} from './route-geometry';
import type { Arrival, FutarVehicle } from './types';

/**
 * The map window for one direction: the polyline + stops from the line start
 * up to (and including) the tracked stop — the part the user's vehicle still
 * traverses before reaching them. The tracker map draws only this instead of
 * the whole line, so the view stays focused on the approach. Null when the
 * tracked stop can't be resolved onto the geometry; callers then keep the
 * full route so the map never goes blank.
 */
export function clipRouteToTrackedStop<T extends { lat: number; lon: number }>(
  polyline: LatLon[],
  stops: T[],
  trackedIndex: number
): { polyline: LatLon[]; stops: T[] } | null {
  if (polyline.length < 2 || stops.length === 0) return null;
  const tracked = stops[trackedIndex];
  if (!tracked || (tracked.lat === 0 && tracked.lon === 0)) return null;
  const dists = cumulativeHaversineDistances(polyline);
  const total = dists[dists.length - 1];
  if (!total || total <= 0) return null;
  const snapped = snapToPolyline({ lat: tracked.lat, lon: tracked.lon }, polyline);
  if (!snapped) return null;
  // A tracked stop at (or essentially at) the line start has no approach to
  // clip — the caller keeps the full route.
  if (snapped.distance < 1) return null;
  const cut = cutPolylineAtDistance(polyline, dists, snapped.distance);
  if (cut.length < 2) return null;
  return {
    polyline: cut.map(([lat, lon]) => ({ lat, lon })),
    stops: stops.slice(0, trackedIndex + 1),
  };
}

export interface ApproachStop {
  id: string;
  name: string;
  lat: number;
  lon: number;
}

export interface VehicleApproach {
  polyline: LatLon[];
  stops: ApproachStop[];
}

/**
 * The remaining path a vehicle must travel to reach the tracked stop: the
 * polyline from the vehicle's projected position (or the line start when no
 * vehicle is known) up to the tracked stop, plus only the stops in between,
 * inclusive of the tracked stop. Null when the vehicle has already passed the
 * tracked stop or the geometry can't resolve it.
 */
export function approachFromVehicle(
  polyline: LatLon[],
  stops: ApproachStop[],
  trackedIndex: number,
  vehicle: { lat: number; lon: number } | null
): VehicleApproach | null {
  if (polyline.length < 2 || stops.length === 0) return null;
  if (trackedIndex < 0 || trackedIndex >= stops.length) return null;
  const tracked = stops[trackedIndex];
  if (!tracked || (tracked.lat === 0 && tracked.lon === 0)) return null;

  const dists = cumulativeHaversineDistances(polyline);
  const total = dists[dists.length - 1];
  if (!total || total <= 0) return null;

  const trackedSnap = snapToPolyline({ lat: tracked.lat, lon: tracked.lon }, polyline);
  if (!trackedSnap) return null;
  const trackedDist = trackedSnap.distance;

  let startDist = 0;
  if (vehicle) {
    const vehicleSnap = snapToPolyline(vehicle, polyline);
    if (vehicleSnap) startDist = vehicleSnap.distance;
  }
  if (startDist >= trackedDist) return null;

  const toTracked = cutPolylineAtDistance(polyline, dists, trackedDist);
  const toStart = cutPolylineAtDistance(polyline, dists, startDist);
  if (toTracked.length < 2) return null;

  const start = toStart[toStart.length - 1];
  const tail = toTracked.slice(Math.max(0, toStart.length - 1));
  const points: LatLon[] = [];
  for (const [lat, lon] of [start, ...tail]) {
    const prev = points[points.length - 1];
    if (prev && prev.lat === lat && prev.lon === lon) continue;
    points.push({ lat, lon });
  }
  if (points.length < 2) return null;

  const stopDists = stops
    .slice(0, trackedIndex + 1)
    .map((s) => snapToPolyline({ lat: s.lat, lon: s.lon }, polyline)?.distance ?? 0);
  const firstIndex = nextStopIndex(stopDists, startDist);

  return { polyline: points, stops: stops.slice(firstIndex, trackedIndex + 1) };
}

/**
 * The next arrival's tripId per direction group (max three — some routes have
 * short-turn variants that create a third headsign). Direction trackers yield
 * a single tripId. Arrivals without a tripId are skipped.
 */
export function trackedTripIds(arrivals: Arrival[]): string[] {
  const seenDirections = new Set<string>();
  const out: string[] = [];
  for (const a of arrivals) {
    if (!a.tripId || seenDirections.has(a.headSign)) continue;
    seenDirections.add(a.headSign);
    out.push(a.tripId);
    if (out.length === 3) break;
  }
  return out;
}

/** Live vehicles whose tripId is one of the tracked trips (position markers). */
export function matchingVehicles(vehicles: FutarVehicle[], tripIds: string[]): FutarVehicle[] {
  const set = new Set(tripIds);
  return vehicles.filter((v) => v.tripId && set.has(v.tripId));
}

/**
 * Index of the tracked stop in an ordered stop-id list. Matches the exact
 * platform id first, then falls back to the station-level id (parentStationId)
 * so trackers stored as stop-areas still highlight on platform-id stop lists.
 * Returns -1 when the stop can't be found, so no unrelated stop (the line's
 * first) is highlighted as tracked; every consumer handles a negative index.
 */
export function resolveTrackedStopIndex(
  stopIds: string[],
  stopRefs: Record<string, { parentStationId?: string } | undefined>,
  trackedStopId: string | null
): number {
  if (!trackedStopId || stopIds.length === 0) return -1;
  const exact = stopIds.indexOf(trackedStopId);
  if (exact >= 0) return exact;
  const viaParent = stopIds.findIndex((id) => stopRefs[id]?.parentStationId === trackedStopId);
  return viaParent;
}

export interface RouteProgress {
  /** Index of the next stop the vehicle has NOT yet reached (-1 when none). */
  vehicleIndex: number;
  /** Stops remaining to the tracked stop (incl. the next); null when no vehicle. */
  stopsLeft: number | null;
  /** Vehicle position along the route, 0..1; null when no vehicle or geometry. */
  vehicleFraction: number | null;
  /** Tracked stop position along the route, 0..1 (index-based without geometry). */
  trackedFraction: number;
  /** Real-time fractional position within the current stop-to-stop segment, 0..1. */
  segmentFraction: number | null;
}

/**
 * Progress of a vehicle along ONE direction's ordered stops + polyline — the
 * data behind the per-direction progress strips. Fractions use projected path
 * distances when geometry exists (so a vehicle between stops sits between
 * their fractions), index-based spacing as the fallback. Pure, so an
 * all-directions tracker computes one progress per direction.
 */
export function routeProgress(
  stops: Array<{ lat: number; lon: number }>,
  polyline: LatLon[],
  vehicle: { location: { lat: number; lon: number } } | null,
  trackedIndex: number
): RouteProgress {
  const total = stops.length;
  const clamp = (x: number) => Math.max(0, Math.min(1, x));
  const trackedFraction =
    total === 0 ? 0.5 : trackedIndex >= 0 ? clamp(trackedIndex / Math.max(1, total - 1)) : 0.5;
  if (!vehicle || total === 0 || polyline.length < 2) {
    return { vehicleIndex: -1, stopsLeft: null, vehicleFraction: null, trackedFraction, segmentFraction: null };
  }
  const projector = makeProjector(polyline, 400, 220);
  const pathPts = polyline.map(projector);
  const stopDists = stops.map((s) => {
    const proj = projectVehicleOntoPolyline(projector({ lat: s.lat, lon: s.lon }), pathPts);
    return proj?.distance ?? 0;
  });
  const totalDist = stopDists[stopDists.length - 1] || 1;
  const proj = projectVehicleOntoPolyline(projector(vehicle.location), pathPts);
  if (!proj) return { vehicleIndex: -1, stopsLeft: null, vehicleFraction: null, trackedFraction, segmentFraction: null };
  const vehicleIndex = nextStopIndex(stopDists, proj.distance);
  const stopsLeft =
    trackedIndex >= 0 && trackedIndex >= vehicleIndex ? trackedIndex - vehicleIndex + 1 : null;

  // Real-time progress between the previous stop and the approaching stop
  const prevDist = vehicleIndex > 0 ? stopDists[vehicleIndex - 1] : 0;
  const nextDist = stopDists[vehicleIndex] ?? prevDist + 1;
  const segSpan = Math.max(1, nextDist - prevDist);
  const segmentFraction = clamp((proj.distance - prevDist) / segSpan);

  return {
    vehicleIndex,
    stopsLeft,
    vehicleFraction: clamp(proj.distance / totalDist),
    trackedFraction: clamp(stopDists[trackedIndex >= 0 ? trackedIndex : 0] / totalDist),
    segmentFraction,
  };
}

export interface StripLabels {
  left: string;
  middle: string | null;
  right: string;
}

/**
 * The three names under a direction's progress strip: line start (left),
 * tracked stop (middle) and line end (right). The tracked stop owns its end's
 * label when it IS a terminus so names never duplicate; single-stop lines
 * render one label.
 */
export function stripLabels(stops: Array<{ name: string }>, trackedIndex: number): StripLabels {
  const first = stops[0]?.name ?? '';
  const last = stops[stops.length - 1]?.name ?? '';
  const tracked = stops[trackedIndex]?.name ?? first;
  if (trackedIndex < 0) return { left: first, middle: null, right: last };
  if (stops.length <= 1) return { left: `${first} · tracked`, middle: null, right: '' };
  if (trackedIndex === 0) return { left: `${tracked} · tracked`, middle: null, right: last };
  if (trackedIndex === stops.length - 1) return { left: first, middle: null, right: `${tracked} · tracked` };
  return { left: first, middle: `${tracked} · tracked`, right: last };
}

export interface ApproximateVehicleInput {
  stopTimes: Array<{ departureTime?: number; arrivalTime?: number; shapeDistTraveled?: number }>;
  polyline: LatLon[];
  nowMs: number;
  /** Predicted − scheduled at the tracked stop; metros have none, so 0. */
  delayMs?: number;
}

/**
 * Where the vehicle on a trip SHOULD be right now per the timetable: find the
 * schedule segment containing the (delay-adjusted) current time, interpolate
 * within it, convert to a distance fraction of the trip's shape, then place it
 * on the polyline. Metros broadcast no live GPS, so this is the BudapestGO-style
 * approximation. Returns null when the data can't support it.
 */
export function approximateVehiclePosition(input: ApproximateVehicleInput): { lat: number; lon: number } | null {
  const { stopTimes, polyline, nowMs, delayMs = 0 } = input;
  // The trip's ORIGIN stopTime carries only a departure time and the TERMINUS
  // only an arrival time (verified in the live feed) — so each stop's clock
  // time is departureTime ?? arrivalTime, and only stops that actually have
  // both a clock time and a shape distance participate.
  const timed = stopTimes
    .map((s) => ({ departureTime: s.departureTime ?? s.arrivalTime, shapeDistTraveled: s.shapeDistTraveled }))
    .filter((s) => s.departureTime != null && s.shapeDistTraveled != null) as Array<{
    departureTime: number;
    shapeDistTraveled: number;
  }>;
  if (timed.length < 2 || polyline.length < 2) return null;

  const nowSec = nowMs / 1000 - delayMs / 1000;
  const first = timed[0];
  const last = timed[timed.length - 1];
  if (nowSec <= first.departureTime) return pointAtFraction(polyline, 0);
  if (nowSec >= last.departureTime) return pointAtFraction(polyline, 1);

  let i = 0;
  while (i < timed.length - 2 && timed[i + 1].departureTime <= nowSec) i++;
  const a = timed[i];
  const b = timed[i + 1];
  const span = b.departureTime - a.departureTime;
  const t = span <= 0 ? 0 : Math.min(1, Math.max(0, (nowSec - a.departureTime) / span));
  const dist = a.shapeDistTraveled + t * (b.shapeDistTraveled - a.shapeDistTraveled);
  // Normalize against the trip's TRUE end (the terminus stop, now included via
  // its arrival time) instead of the second-to-last stop, so the dot reaches
  // the end of the polyline exactly when the trip does.
  const total = last.shapeDistTraveled - first.shapeDistTraveled;
  const fraction = total <= 0 ? 0 : Math.min(1, Math.max(0, (dist - first.shapeDistTraveled) / total));
  return pointAtFraction(polyline, fraction);
}

/**
 * The direction line under a progress strip: the headsign only when it differs
 * from the line's end label (otherwise the end label already carries it).
 */
export function stripDirection(headsign: string | null | undefined, endLabel: string): string | null {
  if (!headsign) return null;
  const norm = (s: string) => s.trim().toLowerCase();
  return norm(headsign) === norm(endLabel) ? null : headsign;
}


