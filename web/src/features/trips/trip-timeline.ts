import type { Arrival, FutarTripDetailsResponse, FutarTripStopTime } from '@/features/transit/types';
import { haversineMeters, type LatLon } from '@/features/transit/route-geometry';
import { calculateWalkMinutes } from '@/features/transit/nearby-stops';
import { normalizeStopIdToStation } from '@/features/transit/transit-utils';
import { formatBudapestHm } from '@/lib/date-utils';
import type { TripLeg } from './types';

/** Minimum time between one leg's arrival and the next leg's departure.
 *  Deliberately 2 min: a 0-min connection is not catchable, so the next
 *  departure is chosen instead. */
export const MIN_TRANSFER_MS = 120_000;
/** Straight-line → street distance correction for walking estimates. */
export const WALK_DETOUR_FACTOR = 1.3;

export interface TripTimeline {
  /** Enriched legs (transit + walk segments) or null when untimed. */
  legs: TripLeg[] | null;
  leaveAt: number | null;
  arriveAt: number | null;
  totalMinutes: number | null;
}

/** One catchable departure of the journey — the full chained timeline that
 *  results from taking it. */
export interface TripDepartureOption {
  timeline: TripTimeline;
  /** Epoch ms of the first transit leg's departure for this option. */
  departureMs: number;
  /** Whether that departure came from the live (predicted) feed. */
  isLive: boolean;
  /** True when the departure sits inside the origin-walk window: reachable
   *  only at a sprint, so it is surfaced as a flagged alternative instead of
   *  the recommendation. */
  tooTight?: boolean;
}

export interface TripTimelineInput {
  legs: TripLeg[];
  /** Parsed arrivals at each leg's departure stop (route+headsign filtered, sorted ascending). */
  departureArrivals: Map<number, Arrival[]>;
  /** trip-details responses keyed by tripId. */
  tripDetails: Map<string, FutarTripDetailsResponse>;
  /** Parsed arrivals at each leg's destination stop (same-tripId fallback). */
  destinationArrivals: Map<number, Arrival[]>;
  /** Catalog coordinates for a stop id, or null when unknown. */
  getStopCoords: (stopId: string) => LatLon | null;
  now?: number;
}

/** Budapest wall-clock HH:MM. */
export function formatTripClock(ms: number): string {
  return formatBudapestHm(ms);
}

/** Display time for a departure option: the journey's actual leave time,
 * including the origin walk when one is configured. */
export function formatTripOptionLeaveTime(option: Pick<TripDepartureOption, 'timeline'>): string | null {
  return option.timeline.leaveAt == null ? null : formatTripClock(option.timeline.leaveAt);
}

/** Modes whose platforms sit below street level — changing between them and
 *  a surface stop involves a real walk even at the same stop area. */
const UNDERGROUND_MODES = new Set(['SUBWAY', 'RAIL']);

/** Whether two consecutive legs share at least one route (same-line
 *  continuation, not a transfer). */
export function legsShareRoute(
  from: { routes: Array<{ routeId: string }> },
  to: { routes: Array<{ routeId: string }> }
): boolean {
  return from.routes.some((r) => to.routes.some((t) => t.routeId === r.routeId));
}

/** A walking segment, or null when not estimable. */
export function estimateWalkLeg(
  from: TripLeg,
  to: TripLeg,
  getStopCoords: (stopId: string) => LatLon | null
): TripLeg | null {
  if (to.departureStopId && to.departureStopId === from.destinationStopId) {
    // Same stop area — the catalog stores stop-areas (platforms fold into
    // one id), so a tram stop and the metro station under it share an id.
    // A walk exists when the transfer crosses levels (surface stop →
    // underground platform or the reverse), and between DIFFERENT metro
    // lines: their platforms sit in separate tunnels connected by a
    // pedestrian passage (M3 ↔ M4 at Kálvin tér is not a same-platform
    // change). A same-line continuation stays walk-free, as do same-level
    // tram/bus platform changes.
    const crossesLevels = UNDERGROUND_MODES.has(from.routeType) !== UNDERGROUND_MODES.has(to.routeType);
    const metroLineChange =
      from.routeType === 'SUBWAY' && to.routeType === 'SUBWAY' && !legsShareRoute(from, to);
    if (!crossesLevels && !metroLineChange) return null;
    // Fixed estimate: getting between platforms/the street is ~120 m / 2 min.
    const distanceMeters = 120;
    const durationMinutes = calculateWalkMinutes(distanceMeters);
    return {
      routes: [],
      routeType: 'WALK',
      routeLongName: 'Walk',
      departureStopId: '',
      departureStopName: from.destinationStopName,
      destinationStopId: '',
      destinationStopName: to.departureStopName,
      headsign: '',
      waitMinutes: 0,
      mode: 'WALK',
      distanceMeters,
      durationMinutes,
    };
  }
  const a = from.destinationStopId ? getStopCoords(from.destinationStopId) : null;
  const b = to.departureStopId ? getStopCoords(to.departureStopId) : null;
  if (!a || !b) return null;
  const distanceMeters = Math.round(haversineMeters(a, b) * WALK_DETOUR_FACTOR);
  const durationMinutes = calculateWalkMinutes(distanceMeters);
  return {
    routes: [],
    routeType: 'WALK',
    routeLongName: 'Walk',
    departureStopId: '',
    departureStopName: from.destinationStopName,
    destinationStopId: '',
    destinationStopName: to.departureStopName,
    headsign: '',
    waitMinutes: 0,
    mode: 'WALK',
    distanceMeters,
    durationMinutes,
  };
}

/** A synthetic walk segment between an address and the stop it snapped to. */
function addressWalkLeg(fromName: string, toName: string, distanceMeters: number): TripLeg {
  return {
    routes: [],
    routeType: 'WALK',
    routeLongName: 'Walk',
    departureStopId: '',
    departureStopName: fromName,
    destinationStopId: '',
    destinationStopName: toName,
    headsign: '',
    waitMinutes: 0,
    mode: 'WALK',
    distanceMeters,
    durationMinutes: calculateWalkMinutes(distanceMeters),
  };
}

/**
 * The legs to display when no live timeline resolves: the saved legs plus
 * synthesized walk segments from the origin/destination address metadata, so
 * walks stay visible on cards and in the detail dialog even when the live feed
 * is empty (overnight, offline). Returns the input untouched when there is
 * nothing to add or the saved legs already carry walk segments.
 */
export function legsWithAddressWalks(legs: TripLeg[]): TripLeg[] {
  if (legs.length === 0) return legs;
  const first = legs[0];
  const last = legs[legs.length - 1];
  const originWalk =
    first.mode !== 'WALK' && first.originAddress && first.departureStopName
      ? addressWalkLeg(first.originAddress.name, first.departureStopName, first.originAddress.distanceMeters)
      : null;
  const destinationWalk =
    last.mode !== 'WALK' && last.destinationAddress && last.destinationStopName
      ? addressWalkLeg(last.destinationStopName, last.destinationAddress.name, last.destinationAddress.distanceMeters)
      : null;
  if (!originWalk && !destinationWalk) return legs;
  return [
    ...(originWalk ? [originWalk] : []),
    ...legs,
    ...(destinationWalk ? [destinationWalk] : []),
  ];
}

/** When a trip reaches a stop, epoch ms: the live estimate first, then the
 *  schedule. Arrival before departure — a terminus has only `arrivalTime`. */
function stopTimeArrivalMs(st: FutarTripStopTime): number | null {
  const seconds =
    st.predictedArrivalTime || st.predictedDepartureTime || st.arrivalTime || st.departureTime || 0;
  return seconds > 0 ? seconds * 1000 : null;
}

/** The leg's arrival at its destination stop: trip-details stopTime first,
 *  same-tripId destination arrivals as fallback. A time before the boarding
 *  departure is never a valid arrival (bad data, or an earlier pass of a loop
 *  line), so it is skipped. Null when unresolvable. */
function resolveArrivalMs(
  leg: TripLeg,
  departure: Arrival,
  destinationArrivals: Arrival[],
  tripDetails: FutarTripDetailsResponse | undefined
): number | null {
  if (!leg.destinationStopId) return null;
  const departureMs = departure.arrivalMs;

  const stopTimes = tripDetails?.data?.entry?.stopTimes ?? [];
  if (stopTimes.length > 0) {
    const target = normalizeStopIdToStation(leg.destinationStopId);
    for (const st of stopTimes) {
      if (
        st.stopId === leg.destinationStopId ||
        normalizeStopIdToStation(st.stopId) === target
      ) {
        const ms = stopTimeArrivalMs(st);
        if (ms !== null && ms >= departureMs) return ms;
      }
    }
  }

  const sameTrip = destinationArrivals.find(
    (a) => a.tripId === departure.tripId && a.arrivalMs > 0 && a.arrivalMs >= departureMs
  );
  return sameTrip ? sameTrip.arrivalMs : null;
}

/** Walking duration from the saved origin address to its boarding stop. */
export function originWalkMinutes(input: TripTimelineInput): number {
  const address = input.legs[0]?.originAddress;
  if (!address) return 0;
  return address.walkingMinutes ?? calculateWalkMinutes(address.distanceMeters);
}

/** The origin-address walk plus the minimum transfer buffer — the head start a
 *  departure needs to be catchable on foot. Zero when the trip starts at the
 *  stop itself. */
function originApproachMs(input: TripTimelineInput): number {
  const walkMinutes = originWalkMinutes(input);
  return walkMinutes > 0 ? walkMinutes * 60_000 + MIN_TRANSFER_MS : 0;
}

/**
 * Upcoming first-leg departures split by reachability: `catchable` vehicles
 * leave enough time for the origin walk plus the buffer; `tooTight` ones are
 * still upcoming but reachable only at a sprint (they surface as flagged
 * alternatives). Both lists keep the arrivals' ascending time order.
 */
function firstLegWindows(input: TripTimelineInput): { catchable: Arrival[]; tooTight: Arrival[] } {
  const now = input.now ?? Date.now();
  const approachMs = originApproachMs(input);
  const upcoming = (input.departureArrivals.get(0) ?? []).filter((a) => a.arrivalMs >= now);
  if (approachMs === 0) return { catchable: upcoming, tooTight: [] };
  const cutoff = now + approachMs;
  return {
    catchable: upcoming.filter((a) => a.arrivalMs >= cutoff),
    tooTight: upcoming.filter((a) => a.arrivalMs < cutoff),
  };
}

/**
 * The catchable departures of the first transit leg: upcoming departures that
 * still leave enough time for the origin-address walk plus the minimum transfer
 * buffer. Both the primary timeline (`firstLegIndex` 0) and the alternative
 * options enumerate this exact list, so an option's chip always matches the
 * timeline it expands into.
 */
function firstLegCandidates(input: TripTimelineInput): Arrival[] {
  return firstLegWindows(input).catchable;
}

/**
 * Build one option per first-leg candidate: the full chained timeline that
 * results from taking it. Only fully resolved journeys are surfaced — an option
 * missing its arrival or walk segments would mislead, so it is skipped and the
 * next departure is tried. Dedupes vehicles departing within the same minute
 * and stops once `maxOptions` are found or `maxScan` candidates are exhausted.
 */
function buildOptionsFromList(
  input: TripTimelineInput,
  candidates: Arrival[],
  maxOptions: number,
  maxScan: number,
  tooTight: boolean
): TripDepartureOption[] {
  const out: TripDepartureOption[] = [];
  const seenDepartures = new Set<number>();
  for (let k = 0; k < maxScan && k < candidates.length && out.length < maxOptions; k++) {
    const timeline = buildTimelineFromDeparture(input, candidates[k]);
    const firstTransit = timeline.legs?.find((l) => l.mode !== 'WALK');
    if (!firstTransit?.departureMs) continue;
    const minuteBucket = Math.floor(firstTransit.departureMs / 60_000);
    if (seenDepartures.has(minuteBucket)) continue;
    seenDepartures.add(minuteBucket);
    out.push({
      timeline,
      departureMs: firstTransit.departureMs,
      isLive: !!firstTransit.isPredicted,
      ...(tooTight ? { tooTight: true } : {}),
    });
  }
  return out;
}

/**
 * Alternative sensical departures of a journey: the catchable first-leg
 * departures in time order, each expanded into the full chained timeline that
 * results from taking it. See buildOptionsFromList for the resolution and
 * dedupe rules.
 */
export function buildDepartureOptions(
  input: TripTimelineInput,
  maxOptions = 5,
  maxScan = 14
): TripDepartureOption[] {
  const frozen: TripTimelineInput = { ...input, now: input.now ?? Date.now() };
  return buildOptionsFromList(frozen, firstLegWindows(frozen).catchable, maxOptions, maxScan, false);
}

/**
 * Chain the trip's legs into a real timeline using live arrivals + scheduled
 * trip durations. Returns `legs: null` when ANY transit leg can't resolve —
 * the UI then degrades to the existing countdowns. `firstLegIndex` picks a
 * specific catchable departure of the first transit leg — 0 = the earliest;
 * higher values build alternative timelines for "I'll catch a later one"
 * options. Returns a null timeline when that departure doesn't resolve.
 */
export function buildTripTimeline(input: TripTimelineInput, firstLegIndex = 0): TripTimeline {
  if (input.legs.length === 0) {
    return { legs: [], leaveAt: null, arriveAt: null, totalMinutes: null };
  }
  const firstDeparture = firstLegCandidates(input)[firstLegIndex];
  if (!firstDeparture) {
    return { legs: null, leaveAt: null, arriveAt: null, totalMinutes: null };
  }
  return buildTimelineFromDeparture(input, firstDeparture);
}

/** Chain the trip's legs (walks included) from a specific first-leg departure. */
function buildTimelineFromDeparture(input: TripTimelineInput, firstDeparture: Arrival): TripTimeline {
  const { legs, departureArrivals, destinationArrivals, tripDetails, getStopCoords } = input;
  const out: TripLeg[] = [];
  if (legs.length === 0) {
    return { legs: [], leaveAt: null, arriveAt: null, totalMinutes: null };
  }
  let cursor = firstDeparture.arrivalMs;

  // Walk from the origin address to the first stop (when the trip starts at
  // an address that snapped to a stop). Back-planned from the chosen departure
  // so the user leaves just in time — the walk ends MIN_TRANSFER_MS before
  // boarding and later options leave later instead of waiting at the stop.
  const originAddress = legs[0].originAddress;
  if (originAddress && legs[0].departureStopName) {
    const durationMinutes = originWalkMinutes(input);
    const walkEnd = firstDeparture.arrivalMs - MIN_TRANSFER_MS;
    out.push({
      routes: [],
      routeType: 'WALK',
      routeLongName: 'Walk',
      departureStopId: '',
      departureStopName: originAddress.name,
      destinationStopId: '',
      destinationStopName: legs[0].departureStopName,
      headsign: '',
      waitMinutes: 0,
      mode: 'WALK',
      distanceMeters: originAddress.distanceMeters,
      durationMinutes,
      departureMs: walkEnd - durationMinutes * 60_000,
      arrivalMs: walkEnd,
    });
  }

  for (let i = 0; i < legs.length; i++) {
    const leg = legs[i];
    let departure: Arrival;
    if (i === 0) {
      departure = firstDeparture;
    } else {
      const candidates = (departureArrivals.get(i) ?? []).filter((a) => a.arrivalMs >= cursor);
      if (candidates.length === 0) {
        return { legs: null, leaveAt: null, arriveAt: null, totalMinutes: null };
      }
      departure = candidates[0];
    }
    const arrivalMs = resolveArrivalMs(
      leg,
      departure,
      destinationArrivals.get(i) ?? [],
      tripDetails.get(departure.tripId)
    );
    if (arrivalMs === null) {
      return { legs: null, leaveAt: null, arriveAt: null, totalMinutes: null };
    }
    out.push({
      ...leg,
      mode: 'TRANSIT',
      departureMs: departure.arrivalMs,
      arrivalMs,
      isPredicted: departure.isPredicted,
      // Which alternative route was the earliest to depart (either/or legs).
      resolvedRouteId: departure.routeId || undefined,
      resolvedRouteShortName: departure.routeShortName || undefined,
      resolvedHeadsign: departure.headSign || departure.tripHeadsign || undefined,
      // The actual trip being caught — links to vehicles-for-route so the map
      // can show THE vehicle for this leg, not every vehicle on the line.
      departureTripId: departure.tripId || undefined,
    });

    if (i < legs.length - 1) {
      const walk = estimateWalkLeg(leg, legs[i + 1], getStopCoords);
      if (walk) {
        const durationMs = (walk.durationMinutes ?? 1) * 60_000;
        out.push({ ...walk, departureMs: arrivalMs, arrivalMs: arrivalMs + durationMs });
        cursor = arrivalMs + durationMs + MIN_TRANSFER_MS;
      } else {
        cursor = arrivalMs + MIN_TRANSFER_MS;
      }
    }
  }

  // Walk from the last stop to the destination address (when the trip ends
  // at an address that snapped to a stop).
  const destinationAddress = legs[legs.length - 1]?.destinationAddress;
  if (destinationAddress && legs[legs.length - 1].destinationStopName) {
    const lastTransit = out[out.length - 1];
    if (lastTransit?.arrivalMs != null) {
      const durationMinutes = calculateWalkMinutes(destinationAddress.distanceMeters);
      out.push({
        routes: [],
        routeType: 'WALK',
        routeLongName: 'Walk',
        departureStopId: '',
        departureStopName: legs[legs.length - 1].destinationStopName,
        destinationStopId: '',
        destinationStopName: destinationAddress.name,
        headsign: '',
        waitMinutes: 0,
        mode: 'WALK',
        distanceMeters: destinationAddress.distanceMeters,
        durationMinutes,
        departureMs: lastTransit.arrivalMs,
        arrivalMs: lastTransit.arrivalMs + durationMinutes * 60_000,
      });
    }
  }

  // Real transfer waits: for each segment, the gap to the next transit
  // departure. Never show 0 min — a sub-minute gap is rounded up to 1 so the
  // "next departure" rule reads naturally.
  for (let i = 0; i < out.length; i++) {
    const seg = out[i];
    const nextTransit = out.slice(i + 1).find((s) => s.mode !== 'WALK');
    seg.waitMinutes =
      nextTransit && seg.arrivalMs != null && nextTransit.departureMs != null
        ? Math.max(1, Math.round((nextTransit.departureMs - seg.arrivalMs) / 60_000))
        : 0;
  }

  // leaveAt = the very first segment (the origin-address walk when present),
  // arriveAt = the very last (the destination-address walk when present) — so
  // "Leave 08:12 · Arrive …" covers the whole journey including walks.
  const first = out[0];
  const last = out[out.length - 1];
  return {
    legs: out,
    leaveAt: first?.departureMs ?? null,
    arriveAt: last?.arrivalMs ?? null,
    totalMinutes:
      first && last && first.departureMs != null && last.arrivalMs != null
        ? Math.max(0, Math.round((last.arrivalMs - first.departureMs) / 60_000))
        : null,
  };
}

/**
 * Timeline built from persisted leg times (e.g. a sample/planned trip whose
 * legs carry `departureMs`/`arrivalMs`) — used as the fallback when no live
 * departures resolve. Requires EVERY leg to carry both times; otherwise null
 * (legacy manual trips keep the countdown UI).
 */
export function timelineFromTimes(legs: TripLeg[]): TripTimeline {
  if (legs.length === 0) return { legs: null, leaveAt: null, arriveAt: null, totalMinutes: null };
  let leaveAt: number | null = null;
  let arriveAt: number | null = null;
  for (const l of legs) {
    if (l.departureMs == null || l.arrivalMs == null) {
      return { legs: null, leaveAt: null, arriveAt: null, totalMinutes: null };
    }
    if (leaveAt === null || l.departureMs < leaveAt) leaveAt = l.departureMs;
    if (arriveAt === null || l.arrivalMs > arriveAt) arriveAt = l.arrivalMs;
  }
  return {
    legs,
    leaveAt,
    arriveAt,
    totalMinutes:
      leaveAt !== null && arriveAt !== null
        ? Math.max(0, Math.round((arriveAt - leaveAt) / 60_000))
        : null,
  };
}

/**
 * The trip's display timelines: the fully resolved departure options plus the
 * primary timeline they anchor. The primary is the earliest *resolvable*
 * departure — when the soonest vehicle's chain can't resolve (short-turn
 * variant, trip-details miss), the next departure is promoted instead of
 * blanking the whole trip to the countdown UI. Falls back to persisted leg
 * times when nothing resolves live. Too-tight departures are returned
 * separately so they can be shown without ever becoming the primary.
 */
export function buildTripTimelines(input: TripTimelineInput): {
  timeline: TripTimeline;
  options: TripDepartureOption[];
  tooTightOptions: TripDepartureOption[];
} {
  const frozen: TripTimelineInput = { ...input, now: input.now ?? Date.now() };
  const windows = firstLegWindows(frozen);
  const options = buildOptionsFromList(frozen, windows.catchable, 5, 14, false);
  const tooTightOptions = buildOptionsFromList(frozen, windows.tooTight, 2, 6, true);
  return {
    timeline: options[0]?.timeline ?? timelineFromTimes(input.legs),
    options,
    tooTightOptions,
  };
}

/** Compact card headline: "Leave 08:12 · Arrive 08:47 · 35 min". Null when untimed. */
export function tripSummary(timeline: TripTimeline): {
  leaveAt: string;
  arriveAt: string;
  durationMin: number;
} | null {
  if (
    timeline.legs === null ||
    timeline.leaveAt === null ||
    timeline.arriveAt === null ||
    timeline.totalMinutes === null
  ) {
    return null;
  }
  return {
    leaveAt: formatTripClock(timeline.leaveAt),
    arriveAt: formatTripClock(timeline.arriveAt),
    durationMin: timeline.totalMinutes,
  };
}