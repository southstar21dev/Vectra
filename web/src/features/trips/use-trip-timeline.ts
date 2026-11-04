import { useEffect, useMemo, useState } from 'react';
import { useQueries } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query-keys';
import { useMultiStopArrivals } from '@/features/transit/use-transit';
import { fetchTripDetails, DEEP_ARRIVALS_LIMIT } from '@/features/transit/futar-api';
import { isArrivalsStale, parseStopArrivals } from '@/features/transit/transit-utils';
import { useOnlineStatus } from '@/hooks/use-online-status';
import { getCatalogStops } from '@/features/transit/nearby-stops';
import type { Arrival, FutarArrivalsResponse, FutarTripDetailsResponse } from '@/features/transit/types';
import type { LatLon } from '@/features/transit/route-geometry';
import {
  buildTripTimelines,
  type TripDepartureOption,
  type TripTimeline,
} from './trip-timeline';
import type { TripLeg, TripPlan } from './types';

/** How many upcoming departures per leg get a trip-details lookup — options
 *  beyond this window can't verify their arrival and are skipped. */
const CANDIDATE_DEPARTURES_PER_LEG = 8;

/**
 * trip-details cadence for the timeline. The schedule (stop list, scheduled
 * times) is fixed per trip and every trip card on screen asks for the same
 * handful of trips, so the entries are cached per tripId and refreshed slowly;
 * the minute-level live signal comes from the 20s arrivals feed.
 */
export const TIMELINE_TRIP_DETAILS_STALE_MS = 2 * 60_000;
export const TIMELINE_TRIP_DETAILS_REFETCH_MS = 3 * 60_000;

/**
 * Trip ids worth a trip-details lookup: the first few departures of every leg.
 * A later leg can only be boarded after the first leg's earliest departure, so
 * its earlier vehicles are skipped instead of spending lookup slots on trips
 * no option can ever chain into.
 */
export function selectCandidateTripIds(departuresPerLeg: Arrival[][], perLeg = CANDIDATE_DEPARTURES_PER_LEG): string[] {
  const ids = new Set<string>();
  const earliestStart = departuresPerLeg[0]?.[0]?.arrivalMs ?? null;
  departuresPerLeg.forEach((deps, i) => {
    const reachable = i > 0 && earliestStart !== null ? deps.filter((a) => a.arrivalMs >= earliestStart) : deps;
    reachable.slice(0, perLeg).forEach((a) => {
      if (a.tripId) ids.add(a.tripId);
    });
  });
  return Array.from(ids);
}

export interface FirstLegDepartureResult {
  /** The earliest next departure across the leg's route alternatives. */
  departure: { minutes: number; isLive: boolean } | null;
  /** The arrivals feed for this stop is still warming up (no data yet). */
  loading: boolean;
  /** Offline, or the snapshot is too old to stand behind — nothing derived
   *  from it may be labelled live (`departure.isLive` is already false). */
  stale: boolean;
}

/**
 * The first leg's next departure across the leg's route alternatives — the
 * cheap "when does this trip leave" readout (no chained timeline). Shared by
 * TripCard and the transit page's commute hero. Minutes round UP (ceil): for
 * catching a vehicle, erring high is the honest direction. `loading` lets the
 * caller distinguish "feed warming up" from "nothing running".
 */
export function useFirstLegDeparture(leg: TripLeg | null, now: number): FirstLegDepartureResult {
  const stopId = leg?.departureStopId || leg?.destinationStopId || null;
  // The deep window shares the cache entry useTripTimeline already polls for
  // this stop, so a trip card fetches its first stop once, not twice.
  const stopIds = useMemo(() => [stopId], [stopId]);
  const entry = useMultiStopArrivals(stopIds, { limit: DEEP_ARRIVALS_LIMIT })[stopId ?? ''];
  const arrivals = entry?.data;
  const loading = !arrivals && !!entry?.isLoading;
  const offline = !useOnlineStatus();
  const stale = !!arrivals && isArrivalsStale(entry?.dataUpdatedAt, now, offline);

  const departure = useMemo(() => {
    if (!arrivals || !stopId || !leg) return null;
    let best: { arrivalMs: number; isPredicted: boolean } | null = null;
    for (const r of leg.routes) {
      const parsed = parseStopArrivals(arrivals, r.routeId, (r.headsign ?? leg.headsign) || null);
      const next = parsed[0];
      if (!next) continue;
      if (!best || next.arrivalMs < best.arrivalMs) {
        best = { arrivalMs: next.arrivalMs, isPredicted: next.isPredicted };
      }
    }
    if (!best) return null;
    const minutes = Math.max(0, Math.ceil((best.arrivalMs - now) / 60000));
    return { minutes, isLive: best.isPredicted && !stale };
  }, [arrivals, leg, stopId, now, stale]);

  return { departure, loading, stale };
}

export interface TripTimelineResult {
  /** The earliest fully-resolved timeline (falling back to persisted leg times). */
  timeline: TripTimeline;
  /** Up to 5 fully resolved departures in time order, each with its complete
   *  chained timeline (walks included). Empty when nothing chains. */
  options: TripDepartureOption[];
  /** Up to 2 departures inside the origin-walk window (reachable only at a
   *  sprint), flagged `tooTight` — shown as alternatives, never the primary. */
  tooTightOptions: TripDepartureOption[];
}

/** Stop id → coordinates, lazily loaded from the runtime catalog JSON once. */
export function useCatalogStopCoords(): Map<string, LatLon> {
  const [map, setMap] = useState<Map<string, LatLon>>(() => new Map());
  useEffect(() => {
    let alive = true;
    getCatalogStops()
      .then((stops) => {
        if (!alive) return;
        const m = new Map<string, LatLon>();
        for (const s of stops) m.set(s.id, { lat: s.lat, lon: s.lon });
        setMap(m);
      })
      .catch(() => {
        // Coordinates are optional; the timeline renders without map pins.
      });
    return () => {
      alive = false;
    };
  }, []);
  return map;
}

/** Merge a leg's parsed arrivals across its routes, sorted by time. Each
 *  route filters by its OWN headsign when set (either/or alternatives can
 *  point different directions), falling back to the leg headsign. */
function parseLegArrivals(
  data: FutarArrivalsResponse | undefined,
  legRoutes: Array<{ routeId: string; headsign?: string }>,
  headsign: string
): Arrival[] {
  if (!data) return [];
  const merged: Arrival[] = [];
  for (const r of legRoutes) {
    const hs = r.headsign ?? headsign;
    merged.push(...parseStopArrivals(data, r.routeId, hs || null));
  }
  return merged.sort((a, b) => a.arrivalMs - b.arrivalMs);
}

/** Live chained timeline + departure options for a saved trip. */
export function useTripTimeline(trip: TripPlan | null): TripTimelineResult {
  const legs = useMemo(() => trip?.legs ?? [], [trip]);

  // One batched arrivals query per distinct stop (departure + destination of
  // every leg) — the same 20s cadence the transit cards use. The deep window
  // keeps later chained vehicles visible at the destination stops, which is
  // what lets every departure option resolve.
  const stopIds = useMemo(() => {
    const ids: string[] = [];
    for (const leg of legs) {
      if (leg.departureStopId) ids.push(leg.departureStopId);
      if (leg.destinationStopId) ids.push(leg.destinationStopId);
    }
    return ids;
  }, [legs]);
  const arrivalsMap = useMultiStopArrivals(stopIds, { limit: DEEP_ARRIVALS_LIMIT });

  const { departureArrivals, destinationArrivals, tripIdKey } = useMemo(() => {
    const departureArrivals = new Map<number, Arrival[]>();
    const destinationArrivals = new Map<number, Arrival[]>();
    legs.forEach((leg, i) => {
      const depData = leg.departureStopId ? arrivalsMap[leg.departureStopId]?.data : undefined;
      departureArrivals.set(i, parseLegArrivals(depData, leg.routes, leg.headsign));
      const destData = leg.destinationStopId ? arrivalsMap[leg.destinationStopId]?.data : undefined;
      destinationArrivals.set(i, parseLegArrivals(destData, leg.routes, leg.headsign));
    });
    const ids = selectCandidateTripIds(legs.map((_, i) => departureArrivals.get(i) ?? []));
    return { departureArrivals, destinationArrivals, tripIdKey: ids.join('|') };
  }, [legs, arrivalsMap]);

  // trip-details for the few candidate trips, cached per tripId (shared by
  // every card and the detail dialog) and refreshed slowly. The string key
  // keeps the query list stable across arrivals polls that change nothing.
  const tripIdList = useMemo(() => (tripIdKey ? tripIdKey.split('|') : []), [tripIdKey]);
  const tripDetailsQueries = useQueries({
    queries: tripIdList.map((tripId) => ({
      queryKey: queryKeys.transitTripDetails(tripId),
      queryFn: (): Promise<FutarTripDetailsResponse> => fetchTripDetails(tripId),
      staleTime: TIMELINE_TRIP_DETAILS_STALE_MS,
      refetchInterval: TIMELINE_TRIP_DETAILS_REFETCH_MS,
      refetchOnWindowFocus: false,
      retry: 2,
    })),
  });
  const tripDetails = useMemo(() => {
    const map = new Map<string, FutarTripDetailsResponse>();
    tripIdList.forEach((id, index) => {
      const data = tripDetailsQueries[index]?.data;
      if (data) map.set(id, data);
    });
    return map;
  }, [tripIdList, tripDetailsQueries]);

  const stopCoords = useCatalogStopCoords();

  // The options list doubles as the primary timeline's source: the earliest
  // fully-resolved departure becomes the hero, so one unresolvable vehicle
  // (short-turn, missing trip-details) no longer blanks the whole trip.
  const { timeline, options, tooTightOptions } = useMemo(
    () =>
      buildTripTimelines({
        legs,
        departureArrivals,
        destinationArrivals,
        tripDetails,
        getStopCoords: (stopId) => stopCoords.get(stopId) ?? null,
      }),
    [legs, departureArrivals, destinationArrivals, tripDetails, stopCoords]
  );

  return { timeline, options, tooTightOptions };
}