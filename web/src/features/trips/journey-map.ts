import { useEffect, useState } from 'react';
import { fetchCombinedRouteDetails } from '@/features/transit/futar-api';
import { decodePolyline, haversineMeters, type LatLon } from '@/features/transit/route-geometry';
import { normalizeStopIdToStation } from '@/features/transit/transit-utils';
import type { FutarRouteDetailsResponse } from '@/features/transit/types';
import type { RoutePreviewStop } from '@/features/transit/route-preview-map';
import { clipRouteToJourney, type ClipStop } from './route-clip';
import { normalizeStopName } from './route-variants';
import { legsShareRoute } from './trip-timeline';
import { searchPlaces } from './geocode';

/** One route riding on a journey leg — with the color its segment draws. */
export interface JourneyRoute {
  routeId: string;
  color: string;
  headsign?: string;
}

/** One transit leg of a journey: ride `routes` (either/or alternatives share
 *  the same from→to pair) from `fromName` to `toName`. */
export interface JourneyLeg {
  routeType: string;
  fromName: string;
  toName: string;
  routes: JourneyRoute[];
}

/** Origin/destination address of the trip — lat/lon draw the walk to/from it. */
export interface JourneyAddress {
  name: string;
  distanceMeters: number;
  lat?: number;
  lon?: number;
}

/** Everything the route preview map needs for a journey: the clipped route
 *  segments, per-segment colors, the stops in range, and the walk segments
 *  connecting them end-to-end (address → first stop, between legs, last stop
 *  → address). */
export interface JourneyMapData {
  polylines: LatLon[][];
  colors: string[];
  stops: RoutePreviewStop[];
  walks: LatLon[][];
  /** Fetch state from `useJourneyMapGeometry`: `failed` when no route
   *  geometry could be loaded, so callers drop their loading cover. */
  status?: 'idle' | 'loading' | 'ready' | 'failed';
}

/** Modes whose platforms sit below street level — a same-area change to/from
 *  them involves a real walk. Kept in sync with estimateWalkLeg in
 *  trip-timeline.ts. */
const UNDERGROUND_MODES = new Set(['SUBWAY', 'RAIL']);

/** Points closer than ~25 m apart count as the same platform/stop. */
const SAME_STOP_M = 25;

/** Dedupe boarding/alighting points so routes at one platform share a walk. */
function distinctPoints(pts: LatLon[]): LatLon[] {
  const out: LatLon[] = [];
  for (const p of pts) {
    if (!out.some((q) => haversineMeters(q, p) < SAME_STOP_M)) out.push(p);
  }
  return out;
}

/** Whether a walk exists between two consecutive journey legs: different
 *  stops always; the same stop area only when crossing surface/underground
 *  levels or changing between different metro lines (their platforms are in
 *  separate tunnels — M3 ↔ M4 at Kálvin tér is not a same-platform change). */
export function walkBetweenJourneyLegs(from: JourneyLeg, to: JourneyLeg): boolean {
  const sameStop =
    !!from.toName && !!to.fromName && normalizeStopName(from.toName) === normalizeStopName(to.fromName);
  if (!sameStop) return true;
  if (from.routeType === 'SUBWAY' && to.routeType === 'SUBWAY' && !legsShareRoute(from, to)) {
    return true;
  }
  return UNDERGROUND_MODES.has(from.routeType) !== UNDERGROUND_MODES.has(to.routeType);
}

/**
 * Build the map model for a journey: each leg's route(s) clipped to its own
 * boarding → destination segment (never the whole line, and never stretched —
 * every route keeps its own authentic endpoints), only the stops in those
 * ranges, and walk segments that CONNECT the clipped segments end to end.
 * Each leg contributes its DISTINCT boarding/alighting points (routes at the
 * same platform share one); the origin walk fans out to every boarding point
 * of the first leg, the destination walk fans in from every alighting point
 * of the last leg, and a transfer walk connects every alighting point of one
 * leg to every boarding point of the next. An either/or bus boarding 150 m
 * from the tram platform therefore meets the walk network with its own walk
 * line instead of a stretched route. A route whose stop names don't resolve
 * falls back to its full line so a leg never silently disappears.
 */
export function buildJourneyGeometry(
  legs: JourneyLeg[],
  routeDetails: Map<string, FutarRouteDetailsResponse | null>,
  originAddress?: JourneyAddress | null,
  destinationAddress?: JourneyAddress | null
): JourneyMapData {
  const polylines: LatLon[][] = [];
  const colors: string[] = [];
  const seenStops = new Set<string>();
  const stops: RoutePreviewStop[] = [];
  // Per-leg DISTINCT boarding/alighting points (segment endpoints, deduped
  // across routes at the same platform) — the walk network attaches here so
  // the dashed paths meet every route segment exactly.
  const legBoards: LatLon[][] = [];
  const legAlights: LatLon[][] = [];

  for (const leg of legs) {
    const boards: LatLon[] = [];
    const alights: LatLon[] = [];
    for (const route of leg.routes) {
      const res = routeDetails.get(route.routeId);
      if (!res?.data) continue;
      const refs = res.data.references?.stops ?? {};
      let clipped = false;
      for (const v of res.data.entry?.variants ?? []) {
        const pts = v.polyline?.points ? decodePolyline(v.polyline.points) : [];
        if (pts.length < 2) continue;
        // Ordered stops along this direction — variant.stopIds are in route order.
        const ordered: ClipStop[] = (v.stopIds ?? []).map((id) => {
          const ref = refs[id];
          const areaId = ref?.parentStationId || normalizeStopIdToStation(id);
          return {
            id: areaId,
            name: refs[areaId]?.name ?? ref?.name ?? id,
            lat: ref?.lat ?? 0,
            lon: ref?.lon ?? 0,
          };
        });
        const clip = clipRouteToJourney(pts, ordered, leg.fromName, leg.toName);
        if (!clip) continue;
        clipped = true;
        if (clip.taken.length >= 2) {
          // Keep the route's OWN authentic endpoints — never stretch the
          // polyline to meet another route's stop (that reads as an extended
          // route line, not a walk). The walk network reaches each route via
          // the per-leg distinct points below.
          polylines.push(clip.taken);
          colors.push(route.color);
          boards.push(clip.taken[0]);
          alights.push(clip.taken[clip.taken.length - 1]);
        }
        // Only the stops between boarding and destination (inclusive).
        for (let i = clip.fromIndex; i <= clip.toIndex; i++) {
          const s = ordered[i];
          if (!s || seenStops.has(s.id)) continue;
          seenStops.add(s.id);
          stops.push({ id: s.id, name: s.name, lat: s.lat, lon: s.lon });
        }
      }
      // Fallback: stop names didn't match any variant — draw the whole line.
      // No anchor is set (the boarding stop is unknown), so walks to/from this
      // leg are skipped rather than drawn to a far-away terminus.
      if (!clipped) {
        for (const v of res.data.entry?.variants ?? []) {
          const pts = v.polyline?.points ? decodePolyline(v.polyline.points) : [];
          if (pts.length >= 2) {
            polylines.push(pts);
            colors.push(route.color);
          }
          for (const id of v.stopIds ?? []) {
            const ref = refs[id];
            const areaId = ref?.parentStationId || normalizeStopIdToStation(id);
            if (seenStops.has(areaId)) continue;
            seenStops.add(areaId);
            stops.push({
              id: areaId,
              name: refs[areaId]?.name ?? ref?.name ?? id,
              lat: ref?.lat ?? 0,
              lon: ref?.lon ?? 0,
            });
          }
        }
      }
    }
    // Merge routes at the same platform into one point (e.g. five trams share
    // the exact boarding vertex; the either/or bus stop is a distinct point).
    legBoards.push(distinctPoints(boards));
    legAlights.push(distinctPoints(alights));
  }

  // Walks connect the clipped segments end-to-end (Google-Maps style). Linking
  // at the segments' own endpoints keeps walks off the route lines; fanning to
  // every DISTINCT point means each either/or alternative is reached by a walk
  // of its own (never by stretching a route polyline).
  const walks: LatLon[][] = [];
  const addWalk = (a: LatLon | null, b: LatLon | null) => {
    if (a && b && (a.lat !== b.lat || a.lon !== b.lon)) walks.push([a, b]);
  };
  if (originAddress?.lat != null && originAddress.lon != null) {
    const originPt = { lat: originAddress.lat, lon: originAddress.lon };
    for (const b of legBoards[0] ?? []) addWalk(originPt, b);
  }
  for (let i = 0; i + 1 < legs.length; i++) {
    if (!walkBetweenJourneyLegs(legs[i], legs[i + 1])) continue;
    for (const a of legAlights[i] ?? []) {
      for (const b of legBoards[i + 1] ?? []) addWalk(a, b);
    }
  }
  if (destinationAddress?.lat != null && destinationAddress.lon != null) {
    const destPt = { lat: destinationAddress.lat, lon: destinationAddress.lon };
    for (const a of legAlights[legs.length - 1] ?? []) addWalk(a, destPt);
  }

  return { polylines, colors, stops, walks };
}

/**
 * Addresses saved by older trips may only carry a name + distance (the
 * lat/lon were added later). Resolve the missing coordinates by geocoding the
 * name (photon, cached), so legacy trips still draw their origin/destination
 * walks instead of silently dropping them. Returns the address unchanged when
 * coords already exist or geocoding fails.
 */
export async function resolveMissingAddressCoords(
  address: JourneyAddress | null | undefined
): Promise<JourneyAddress | null | undefined> {
  if (!address) return address;
  if (address.lat != null && address.lon != null) return address;
  try {
    const [hit] = await searchPlaces(address.name);
    if (hit) return { ...address, lat: hit.lat, lon: hit.lon };
  } catch {
    // Photon unavailable — leave coords missing (the walk is skipped).
  }
  return address;
}

/**
 * Fetch + clip geometry for a journey while `active` — one batched
 * route-details request per distinct route, plus geocoding for legacy
 * addresses missing coordinates. Shared by the new-trip builder and the
 * saved-trip detail modal so both render the exact same map.
 */
export function useJourneyMapGeometry(
  active: boolean,
  legs: JourneyLeg[],
  originAddress?: JourneyAddress | null,
  destinationAddress?: JourneyAddress | null
): JourneyMapData {
  const [data, setData] = useState<JourneyMapData>({ polylines: [], colors: [], stops: [], walks: [], status: 'idle' });

  useEffect(() => {
    if (!active || legs.length === 0) {
      setData({ polylines: [], colors: [], stops: [], walks: [], status: 'idle' });
      return;
    }
    let alive = true;
    setData((prev) => (prev.status === 'loading' ? prev : { ...prev, status: 'loading' }));
    const routeIds = Array.from(new Set(legs.flatMap((l) => l.routes.map((r) => r.routeId))));
    void Promise.all([
      ...routeIds.map((id) => fetchCombinedRouteDetails(id).catch(() => null)),
      resolveMissingAddressCoords(originAddress),
      resolveMissingAddressCoords(destinationAddress),
    ]).then((results) => {
      if (!alive) return;
      const resps = results.slice(0, routeIds.length) as Array<FutarRouteDetailsResponse | null>;
      const routeDetails = new Map<string, FutarRouteDetailsResponse | null>();
      routeIds.forEach((id, i) => routeDetails.set(id, resps[i]));
      const origin = results[routeIds.length] as JourneyAddress | null | undefined;
      const destination = results[routeIds.length + 1] as JourneyAddress | null | undefined;
      const geometry = buildJourneyGeometry(legs, routeDetails, origin, destination);
      // Every route-details fetch failing (offline, API down) leaves nothing to
      // draw; report it instead of leaving the caller's spinner up forever.
      setData({ ...geometry, status: geometry.polylines.length > 0 ? 'ready' : 'failed' });
    });
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active, legs, originAddress, destinationAddress]);

  return data;
}