import { describe, it, expect } from 'vitest';
import { buildDepartureOptions, buildTripTimeline, buildTripTimelines, estimateWalkLeg, formatTripClock, formatTripOptionLeaveTime, legsWithAddressWalks, originWalkMinutes, tripSummary, type TripTimelineInput } from './trip-timeline';
import type { TripLeg } from './types';
import type { Arrival, FutarTripDetailsResponse } from '@/features/transit/types';
import type { LatLon } from '@/features/transit/route-geometry';

// Anchored to a fixed Budapest wall-clock: 2026-09-10 10:00:00 +02:00 (CEST).
const T0 = new Date('2026-09-10T10:00:00+02:00').getTime();
const MIN = 60_000;

const leg = (partial: Partial<TripLeg>): TripLeg => ({
  routes: [{ routeId: 'BKK_3040', routeShortName: '4', routeColor: '003DA5', routeTextColor: 'FFFFFF' }],
  routeType: 'TRAM',
  routeLongName: '',
  departureStopId: '',
  departureStopName: '',
  destinationStopId: '',
  destinationStopName: '',
  headsign: '',
  waitMinutes: 5,
  ...partial,
});

const arrival = (
  tripId: string,
  arrivalMs: number,
  isPredicted = false,
  route?: { routeId: string; routeShortName: string }
): Arrival => ({
  arrivalMs,
  headSign: 'Újbuda-központ M',
  isPredicted,
  tripId,
  ...route,
});

/** trip-details response where the given stops depart at the given times. */
function tripDetails(tripId: string, stops: Array<[stopId: string, departureMs: number]>): FutarTripDetailsResponse {
  return {
    currentTime: T0,
    status: 'OK',
    data: {
      entry: {
        tripId,
        stopTimes: stops.map(([stopId, ms]) => ({
          stopId,
          departureTime: Math.round(ms / 1000), // epoch seconds
        })),
      },
      references: {},
    },
  };
}

// Catalog coordinates: B/C are ~65 m apart so the walk between them is ~2 min
// and leg 2's 10:10 departure lands comfortably after it.
const COORDS: Record<string, LatLon> = {
  A: { lat: 47.4979, lon: 19.0556 },
  B: { lat: 47.505, lon: 19.065 },
  C: { lat: 47.5055, lon: 19.0655 },
  D: { lat: 47.51, lon: 19.07 },
};
const getStopCoords = (id: string) => COORDS[id] ?? null;

describe('buildTripTimeline', () => {
  it('picks the earliest departure across either/or routes and records which won', () => {
    const input: TripTimelineInput = {
      now: T0,
      legs: [
        leg({
          routes: [
            { routeId: 'BKK_3280', routeShortName: '28', routeColor: 'FFD800', routeTextColor: '000000', headsign: 'Blaha Lujza tér M' },
            { routeId: 'BKK_3370', routeShortName: '37', routeColor: 'FFD800', routeTextColor: '000000', headsign: 'Blaha Lujza tér M' },
          ],
          departureStopId: 'A',
          departureStopName: 'Stop A',
          destinationStopId: 'B',
          destinationStopName: 'Stop B',
          headsign: 'Blaha Lujza tér M',
        }),
      ],
      departureArrivals: new Map([
        [0, [
          arrival('T37', T0 + 5 * MIN, false, { routeId: 'BKK_3370', routeShortName: '37' }),
          arrival('T28', T0 + 8 * MIN, false, { routeId: 'BKK_3280', routeShortName: '28' }),
        ]],
      ]),
      destinationArrivals: new Map(),
      tripDetails: new Map([
        ['T37', tripDetails('T37', [['B', T0 + 9 * MIN]])],
        ['T28', tripDetails('T28', [['B', T0 + 12 * MIN]])],
      ]),
      getStopCoords,
    };
    const timeline = buildTripTimeline(input);
    expect(timeline.legs).not.toBeNull();
    const seg = timeline.legs![0];
    expect(seg.departureMs).toBe(T0 + 5 * MIN); // earliest (37) wins
    expect(seg.resolvedRouteId).toBe('BKK_3370');
    expect(seg.resolvedRouteShortName).toBe('37');
    expect(seg.departureTripId).toBe('T37'); // links to the actual vehicle to take
    expect(timeline.arriveAt).toBe(T0 + 9 * MIN); // chained to the winning trip
  });

  it('forces a later first-leg departure when firstLegIndex is set', () => {
    const input: TripTimelineInput = {
      now: T0,
      legs: [
        leg({
          departureStopId: 'A',
          departureStopName: 'Stop A',
          destinationStopId: 'B',
          destinationStopName: 'Stop B',
        }),
      ],
      departureArrivals: new Map([
        [0, [
          arrival('T37', T0 + 5 * MIN, false, { routeId: 'BKK_3370', routeShortName: '37' }),
          arrival('T28', T0 + 8 * MIN, false, { routeId: 'BKK_3280', routeShortName: '28' }),
          arrival('T20', T0 + 12 * MIN, false, { routeId: 'BKK_3270', routeShortName: '20' }),
        ]],
      ]),
      destinationArrivals: new Map(),
      tripDetails: new Map([
        ['T37', tripDetails('T37', [['B', T0 + 9 * MIN]])],
        ['T28', tripDetails('T28', [['B', T0 + 12 * MIN]])],
        ['T20', tripDetails('T20', [['B', T0 + 15 * MIN]])],
      ]),
      getStopCoords,
    };
    expect(buildTripTimeline(input).legs![0].departureTripId).toBe('T37');

    const second = buildTripTimeline(input, 1);
    expect(second.legs).not.toBeNull();
    expect(second.legs![0].departureMs).toBe(T0 + 8 * MIN);
    expect(second.legs![0].departureTripId).toBe('T28');
    expect(second.arriveAt).toBe(T0 + 12 * MIN);

    // Beyond the candidates → the timeline doesn't resolve.
    expect(buildTripTimeline(input, 3).legs).toBeNull();
  });

  it('dedupes departure options that resolve to the same trip or minute', () => {
    const input: TripTimelineInput = {
      now: T0,
      legs: [
        leg({
          departureStopId: 'A',
          departureStopName: 'Stop A',
          destinationStopId: 'B',
          destinationStopName: 'Stop B',
        }),
      ],
      // T37 appears twice in the feed (duplicate entries) AND a different trip
      // (37A) departs within the same minute — all collapse to one option.
      departureArrivals: new Map([
        [0, [
          arrival('T37', T0 + 5 * MIN, false, { routeId: 'BKK_3370', routeShortName: '37' }),
          arrival('T37', T0 + 5 * MIN, false, { routeId: 'BKK_3371', routeShortName: '37A' }),
          arrival('T37A', T0 + 5 * MIN + 30_000, false, { routeId: 'BKK_3371', routeShortName: '37A' }),
          arrival('T28', T0 + 8 * MIN, false, { routeId: 'BKK_3280', routeShortName: '28' }),
        ]],
      ]),
      destinationArrivals: new Map(),
      tripDetails: new Map([
        ['T37', tripDetails('T37', [['B', T0 + 9 * MIN]])],
        ['T37A', tripDetails('T37A', [['B', T0 + 9 * MIN]])],
        ['T28', tripDetails('T28', [['B', T0 + 12 * MIN]])],
      ]),
      getStopCoords,
    };
    const options = buildDepartureOptions(input);
    expect(options).toHaveLength(2);
    expect(options[0].timeline.legs![0].departureTripId).toBe('T37');
    expect(options[0].departureMs).toBe(T0 + 5 * MIN);
    expect(options[1].departureMs).toBe(T0 + 8 * MIN);
    expect(options[1].timeline.legs![0].departureTripId).toBe('T28');
  });

  it('skips departures whose chain cannot fully resolve', () => {
    const input: TripTimelineInput = {
      now: T0,
      legs: [
        leg({
          departureStopId: 'A',
          departureStopName: 'Stop A',
          destinationStopId: 'B',
          destinationStopName: 'Stop B',
        }),
      ],
      departureArrivals: new Map([
        [0, [
          arrival('T1', T0 + 5 * MIN),
          arrival('T2', T0 + 9 * MIN),
          arrival('T3', T0 + 13 * MIN),
        ]],
      ]),
      destinationArrivals: new Map(),
      tripDetails: new Map(), // no geometry for any trip → no complete option
      getStopCoords,
    };
    expect(buildDepartureOptions(input)).toHaveLength(0);
  });

  it('back-plans each option from its departure so leave and walk times are right', () => {
    const input: TripTimelineInput = {
      now: T0,
      legs: [
        leg({
          departureStopId: 'A',
          departureStopName: 'Stop A',
          destinationStopId: 'B',
          destinationStopName: 'Stop B',
          originAddress: { name: 'Home', distanceMeters: 300 }, // 5 min walk
        }),
      ],
      departureArrivals: new Map([
        [0, [arrival('T1', T0 + 8 * MIN), arrival('T2', T0 + 20 * MIN)]],
      ]),
      destinationArrivals: new Map(),
      tripDetails: new Map([
        ['T1', tripDetails('T1', [['B', T0 + 13 * MIN]])],
        ['T2', tripDetails('T2', [['B', T0 + 25 * MIN]])],
      ]),
      getStopCoords,
    };
    const options = buildDepartureOptions(input);
    expect(options).toHaveLength(2);

    const [first, second] = options;
    // Leave just in time: walk end = departure − MIN_TRANSFER_MS buffer.
    expect(first.timeline.leaveAt).toBe(T0 + 8 * MIN - 2 * MIN - 5 * MIN);
    expect(first.timeline.legs![0].arrivalMs).toBe(T0 + 8 * MIN - 2 * MIN);
    expect(second.timeline.leaveAt).toBe(T0 + 20 * MIN - 2 * MIN - 5 * MIN);
    expect(second.timeline.legs![0].arrivalMs).toBe(T0 + 20 * MIN - 2 * MIN);
    // The same journey for both: the wait doesn't inflate the later duration.
    expect(second.timeline.totalMinutes).toBe(first.timeline.totalMinutes);
    // Every option carries its origin walk.
    expect(first.timeline.legs!.some((l) => l.mode === 'WALK')).toBe(true);
    expect(second.timeline.legs!.some((l) => l.mode === 'WALK')).toBe(true);
  });

  it('enumerates options from the same catchable list as the timeline', () => {
    const input: TripTimelineInput = {
      now: T0,
      legs: [
        leg({
          departureStopId: 'A',
          departureStopName: 'Stop A',
          destinationStopId: 'B',
          destinationStopName: 'Stop B',
          originAddress: { name: 'Home', distanceMeters: 300 }, // 5 min walk + 2 min buffer
        }),
      ],
      departureArrivals: new Map([
        [0, [
          // Too soon to reach on foot — must not surface as an option.
          arrival('T1', T0 + 1 * MIN),
          arrival('T2', T0 + 3 * MIN),
          arrival('T3', T0 + 5 * MIN),
          arrival('T4', T0 + 20 * MIN),
          arrival('T5', T0 + 27 * MIN),
        ]],
      ]),
      destinationArrivals: new Map(),
      tripDetails: new Map([
        ['T1', tripDetails('T1', [['B', T0 + 6 * MIN]])],
        ['T2', tripDetails('T2', [['B', T0 + 8 * MIN]])],
        ['T3', tripDetails('T3', [['B', T0 + 10 * MIN]])],
        ['T4', tripDetails('T4', [['B', T0 + 25 * MIN]])],
        ['T5', tripDetails('T5', [['B', T0 + 32 * MIN]])],
      ]),
      getStopCoords,
    };
    const options = buildDepartureOptions(input);
    // Chronological, catchable-only, and each matches its own timeline.
    expect(options.map((o) => o.departureMs)).toEqual([T0 + 20 * MIN, T0 + 27 * MIN]);
    expect(options[0].timeline.legs![1].mode).toBe('TRANSIT');
    expect(options[0].timeline.legs![1].departureMs).toBe(T0 + 20 * MIN);
    expect(options[1].timeline.legs![1].departureMs).toBe(T0 + 27 * MIN);
  });

  it('chains two transit legs with a walking segment and real waits', () => {
    const input: TripTimelineInput = {
      now: T0,
      legs: [
        leg({ departureStopId: 'A', departureStopName: 'Stop A', destinationStopId: 'B', destinationStopName: 'Stop B' }),
        leg({ departureStopId: 'C', departureStopName: 'Stop C', destinationStopId: 'D', destinationStopName: 'Stop D' }),
      ],
      departureArrivals: new Map([
        [0, [arrival('T1', T0 + 2 * MIN)]], // leg 1 departs 10:02
        [1, [arrival('T2', T0 + 10 * MIN)]], // leg 2 departs 10:10
      ]),
      destinationArrivals: new Map(),
      tripDetails: new Map([
        ['T1', tripDetails('T1', [['B', T0 + 5 * MIN]])], // leg 1 arrives 10:05
        ['T2', tripDetails('T2', [['D', T0 + 15 * MIN]])], // leg 2 arrives 10:15
      ]),
      getStopCoords,
    };
    const timeline = buildTripTimeline(input);
    expect(timeline.legs).not.toBeNull();
    const segments = timeline.legs!;
    expect(segments.length).toBe(3); // transit, walk, transit
    expect(segments[0].mode).toBe('TRANSIT');
    expect(segments[0].departureMs).toBe(T0 + 2 * MIN);
    expect(segments[0].arrivalMs).toBe(T0 + 5 * MIN);
    expect(segments[1].mode).toBe('WALK');
    // B→C are ~65 m apart: distance > 50 m, walk duration ≥ 1 min
    expect(segments[1].distanceMeters).toBeGreaterThan(50);
    expect(segments[1].durationMinutes).toBeGreaterThanOrEqual(1);
    // walk starts at leg-1 arrival; next transit departs at/after walk end
    expect(segments[1].departureMs).toBe(T0 + 5 * MIN);
    expect(segments[1].arrivalMs).toBeGreaterThanOrEqual(T0 + 5 * MIN);
    expect(segments[2].departureMs).toBeGreaterThanOrEqual(segments[1].arrivalMs!);
    expect(segments[2].arrivalMs).toBe(T0 + 15 * MIN);
    expect(timeline.leaveAt).toBe(T0 + 2 * MIN);
    expect(timeline.arriveAt).toBe(T0 + 15 * MIN);
    expect(timeline.totalMinutes).toBe(13);
    // waitMinutes: transit-1 -> next transit departure (10:10) minus arrival (10:05)
    expect(segments[0].waitMinutes).toBe(5);
    // walk leg carries the platform wait: 10:10 − walk end
    expect(segments[1].waitMinutes).toBeGreaterThanOrEqual(1);
  });

  it('prepends a walk from the origin address to the first stop', () => {
    const input: TripTimelineInput = {
      now: T0,
      legs: [
        leg({
          departureStopId: 'A',
          departureStopName: 'Stop A',
          destinationStopId: 'B',
          destinationStopName: 'Stop B',
          originAddress: { name: 'Teleki László tér 19', distanceMeters: 300 },
        }),
      ],
      departureArrivals: new Map([[0, [arrival('T1', T0 + 8 * MIN)]]]),
      destinationArrivals: new Map(),
      tripDetails: new Map([['T1', tripDetails('T1', [['B', T0 + 11 * MIN]])]]),
      getStopCoords,
    };
    const timeline = buildTripTimeline(input);
    expect(timeline.legs).not.toBeNull();
    const segments = timeline.legs!;
    expect(segments.length).toBe(2); // walk, transit
    expect(segments[0].mode).toBe('WALK');
    expect(segments[0].departureStopName).toBe('Teleki László tér 19');
    expect(segments[0].destinationStopName).toBe('Stop A');
    expect(segments[0].distanceMeters).toBe(300);
    // Back-planned: the 5-min walk ends MIN_TRANSFER_MS before the 10:08 tram.
    expect(segments[0].arrivalMs).toBe(T0 + 6 * MIN);
    expect(segments[0].departureMs).toBe(T0 + 1 * MIN);
    // First transit departs after the walk + the transfer buffer
    expect(segments[1].departureMs).toBeGreaterThanOrEqual(segments[0].arrivalMs! + 2 * MIN);
    expect(timeline.leaveAt).toBe(T0 + 1 * MIN);
  });

  it('chains a two-leg metro journey with a transfer walk between different lines at one stop area', () => {
    const input: TripTimelineInput = {
      now: T0,
      legs: [
        leg({
          routeType: 'SUBWAY',
          routes: [{ routeId: 'BKK_M3', routeShortName: 'M3', routeColor: '003DA5', routeTextColor: 'FFFFFF' }],
          departureStopId: 'A',
          departureStopName: 'Népliget',
          destinationStopId: 'B',
          destinationStopName: 'Kálvin tér',
        }),
        leg({
          routeType: 'SUBWAY',
          routes: [{ routeId: 'BKK_M4', routeShortName: 'M4', routeColor: '00A650', routeTextColor: 'FFFFFF' }],
          departureStopId: 'B',
          departureStopName: 'Kálvin tér',
          destinationStopId: 'C',
          destinationStopName: 'Fővám tér',
        }),
      ],
      departureArrivals: new Map([
        [0, [arrival('T1', T0 + 2 * MIN)]],
        [1, [arrival('T2', T0 + 10 * MIN)]],
      ]),
      destinationArrivals: new Map(),
      tripDetails: new Map([
        ['T1', tripDetails('T1', [['B', T0 + 5 * MIN]])],
        ['T2', tripDetails('T2', [['C', T0 + 12 * MIN]])],
      ]),
      getStopCoords,
    };
    const timeline = buildTripTimeline(input);
    expect(timeline.legs).not.toBeNull();
    const segments = timeline.legs!;
    // transit → transfer walk → transit, even though both legs share the
    // Kálvin tér stop area.
    expect(segments.map((s) => s.mode)).toEqual(['TRANSIT', 'WALK', 'TRANSIT']);
    expect(segments[1].durationMinutes).toBeGreaterThanOrEqual(1);
    expect(segments[2].departureMs).toBe(T0 + 10 * MIN);
  });

  it('appends a walk from the last stop to the destination address', () => {
    const input: TripTimelineInput = {
      now: T0,
      legs: [
        leg({
          departureStopId: 'A',
          departureStopName: 'Stop A',
          destinationStopId: 'B',
          destinationStopName: 'Stop B',
          destinationAddress: { name: 'Corvinus University of Budapest', distanceMeters: 400 },
        }),
      ],
      departureArrivals: new Map([[0, [arrival('T1', T0 + 2 * MIN)]]]),
      destinationArrivals: new Map([[0, [arrival('T1', T0 + 6 * MIN)]]]),
      tripDetails: new Map(),
      getStopCoords,
    };
    const timeline = buildTripTimeline(input);
    expect(timeline.legs).not.toBeNull();
    const segments = timeline.legs!;
    expect(segments.length).toBe(2); // transit, walk
    expect(segments[1].mode).toBe('WALK');
    expect(segments[1].departureStopName).toBe('Stop B');
    expect(segments[1].destinationStopName).toBe('Corvinus University of Budapest');
    expect(segments[1].distanceMeters).toBe(400);
    expect(segments[1].departureMs).toBe(T0 + 6 * MIN); // starts at arrival
    expect(timeline.arriveAt).toBe(segments[1].arrivalMs);
  });

  it('skips a departure inside the transfer buffer — the next one is chosen instead', () => {
    const input: TripTimelineInput = {
      now: T0,
      legs: [
        leg({ departureStopId: 'A', departureStopName: 'Stop A', destinationStopId: 'B', destinationStopName: 'Stop B' }),
        leg({ departureStopId: 'B', departureStopName: 'Stop B', destinationStopId: 'C', destinationStopName: 'Stop C' }),
      ],
      departureArrivals: new Map([
        [0, [arrival('T1', T0 + 2 * MIN)]],
        // A departure 30s after arrival would round to 0 min — must be skipped.
        [1, [arrival('T2', T0 + 5 * MIN + 30_000), arrival('T3', T0 + 8 * MIN)]],
      ]),
      destinationArrivals: new Map(),
      tripDetails: new Map([
        ['T1', tripDetails('T1', [['B', T0 + 5 * MIN]])],
        ['T2', tripDetails('T2', [['C', T0 + 5 * MIN + 30_000]])],
        ['T3', tripDetails('T3', [['C', T0 + 9 * MIN]])],
      ]),
      getStopCoords,
    };
    const timeline = buildTripTimeline(input);
    expect(timeline.legs).not.toBeNull();
    const segments = timeline.legs!;
    expect(segments[1].departureMs).toBe(T0 + 8 * MIN); // T2 rejected, T3 chosen
    expect(segments[0].waitMinutes).toBeGreaterThanOrEqual(1);
    expect(segments[1].arrivalMs).toBe(T0 + 9 * MIN);
  });

  it('enforces a 2-minute minimum transfer between same-stop legs', () => {
    const input: TripTimelineInput = {
      now: T0,
      legs: [
        leg({ departureStopId: 'A', departureStopName: 'Stop A', destinationStopId: 'B', destinationStopName: 'Stop B' }),
        leg({ departureStopId: 'B', departureStopName: 'Stop B', destinationStopId: 'C', destinationStopName: 'Stop C' }),
      ],
      departureArrivals: new Map([
        [0, [arrival('T1', T0 + 2 * MIN)]],
        // Only a departure 30s after arrival — below the 1-minute minimum → chain must skip it.
        [1, [arrival('T2', T0 + 5 * MIN + 30_000)]],
      ]),
      destinationArrivals: new Map(),
      tripDetails: new Map([
        ['T1', tripDetails('T1', [['B', T0 + 5 * MIN]])],
        ['T2', tripDetails('T2', [['C', T0 + 6 * MIN + 30_000]])],
      ]),
      getStopCoords,
    };
    const timeline = buildTripTimeline(input);
    // Only 30s after the arrival — the min-transfer rule rejects it → no feasible chain.
    expect(timeline.legs).toBeNull();
  });

  it('resolves the arrival via the same-tripId destination-arrivals fallback when trip-details misses', () => {
    const input: TripTimelineInput = {
      now: T0,
      legs: [
        leg({ departureStopId: 'A', departureStopName: 'Stop A', destinationStopId: 'B', destinationStopName: 'Stop B' }),
      ],
      departureArrivals: new Map([[0, [arrival('T1', T0 + 2 * MIN)]]]),
      destinationArrivals: new Map([[0, [arrival('T1', T0 + 6 * MIN)]]]),
      tripDetails: new Map(), // trip-details miss → fallback used
      getStopCoords,
    };
    const timeline = buildTripTimeline(input);
    expect(timeline.legs).not.toBeNull();
    expect(timeline.legs![0].arrivalMs).toBe(T0 + 6 * MIN);
    expect(timeline.legs![0].isPredicted).toBe(false);
    expect(timeline.arriveAt).toBe(T0 + 6 * MIN);
  });

  it('returns null when trip-details and the fallback both miss', () => {
    const input: TripTimelineInput = {
      now: T0,
      legs: [
        leg({ departureStopId: 'A', departureStopName: 'Stop A', destinationStopId: 'B', destinationStopName: 'Stop B' }),
      ],
      departureArrivals: new Map([[0, [arrival('T1', T0 + 2 * MIN)]]]),
      destinationArrivals: new Map([[0, [arrival('T9', T0 + 6 * MIN)]]]), // different tripId
      tripDetails: new Map(),
      getStopCoords,
    };
    expect(buildTripTimeline(input).legs).toBeNull();
  });

  it('returns null for a leg with no upcoming departures', () => {
    const input: TripTimelineInput = {
      now: T0,
      legs: [leg({ departureStopId: 'A', departureStopName: 'Stop A', destinationStopId: 'B', destinationStopName: 'Stop B' })],
      departureArrivals: new Map(),
      destinationArrivals: new Map(),
      tripDetails: new Map(),
      getStopCoords,
    };
    expect(buildTripTimeline(input).legs).toBeNull();
  });

  it('returns null when a leg has no destination stop (legacy trips)', () => {
    const input: TripTimelineInput = {
      now: T0,
      legs: [leg({ departureStopId: 'A', departureStopName: 'Stop A' })],
      departureArrivals: new Map([[0, [arrival('T1', T0 + 2 * MIN)]]]),
      destinationArrivals: new Map(),
      tripDetails: new Map(),
      getStopCoords,
    };
    expect(buildTripTimeline(input).legs).toBeNull();
  });

  it('skips walk legs when coordinates are missing (falls back to the minimum transfer)', () => {
    const input: TripTimelineInput = {
      now: T0,
      legs: [
        leg({ departureStopId: 'A', departureStopName: 'Stop A', destinationStopId: 'UNKNOWN1', destinationStopName: 'Stop X' }),
        leg({ departureStopId: 'UNKNOWN2', departureStopName: 'Stop Y', destinationStopId: 'D', destinationStopName: 'Stop D' }),
      ],
      departureArrivals: new Map([
        [0, [arrival('T1', T0 + 2 * MIN)]],
        [1, [arrival('T2', T0 + 5 * MIN + 60_000)]],
      ]),
      destinationArrivals: new Map(),
      tripDetails: new Map([
        ['T1', tripDetails('T1', [['UNKNOWN1', T0 + 4 * MIN]])],
        ['T2', tripDetails('T2', [['D', T0 + 12 * MIN]])],
      ]),
      getStopCoords, // UNKNOWN1/UNKNOWN2 are not in COORDS
    };
    const timeline = buildTripTimeline(input);
    expect(timeline.legs).not.toBeNull();
    expect(timeline.legs!.length).toBe(2); // no walk inserted
    expect(timeline.legs![1].departureMs).toBe(T0 + 5 * MIN + 60_000); // min transfer respected
  });

  it('picks the earliest departure at/after the cursor, skipping earlier ones', () => {
    const input: TripTimelineInput = {
      now: T0 + 5 * MIN, // first leg departs at +2min — already past
      legs: [
        leg({ departureStopId: 'A', departureStopName: 'Stop A', destinationStopId: 'B', destinationStopName: 'Stop B' }),
      ],
      departureArrivals: new Map([[0, [arrival('T1', T0 + 2 * MIN), arrival('T2', T0 + 8 * MIN)]]]),
      destinationArrivals: new Map(),
      tripDetails: new Map([
        ['T1', tripDetails('T1', [['B', T0 + 5 * MIN]])],
        ['T2', tripDetails('T2', [['B', T0 + 9 * MIN]])],
      ]),
      getStopCoords,
    };
    const timeline = buildTripTimeline(input);
    expect(timeline.legs).not.toBeNull();
    expect(timeline.legs![0].departureMs).toBe(T0 + 8 * MIN); // T1 already left
    expect(timeline.legs![0].arrivalMs).toBe(T0 + 9 * MIN);
  });
});

describe('buildTripTimelines', () => {
  it('falls through to the next resolved departure when the soonest chain cannot resolve', () => {
    const input: TripTimelineInput = {
      now: T0,
      legs: [
        leg({
          departureStopId: 'A',
          departureStopName: 'Stop A',
          destinationStopId: 'B',
          destinationStopName: 'Stop B',
        }),
        leg({
          departureStopId: 'B',
          departureStopName: 'Stop B',
          destinationStopId: 'C',
          destinationStopName: 'Stop C',
        }),
      ],
      departureArrivals: new Map([
        [0, [arrival('T1', T0 + 5 * MIN), arrival('T2', T0 + 12 * MIN)]],
        // T4 is a short-turn vehicle: it departs but has no resolvable arrival,
        // so the T1 connection dies while the T2 → T5 connection works.
        [1, [arrival('T4', T0 + 11 * MIN), arrival('T5', T0 + 24 * MIN)]],
      ]),
      destinationArrivals: new Map(),
      tripDetails: new Map([
        ['T1', tripDetails('T1', [['B', T0 + 8 * MIN]])],
        ['T2', tripDetails('T2', [['B', T0 + 20 * MIN]])],
        ['T5', tripDetails('T5', [['C', T0 + 30 * MIN]])],
      ]),
      getStopCoords,
    };
    const { timeline, options } = buildTripTimelines(input);
    expect(buildTripTimeline(input, 0).legs).toBeNull(); // the soonest chain is dead
    expect(timeline.legs).not.toBeNull(); // the trip still renders a timeline
    expect(timeline.leaveAt).toBe(T0 + 12 * MIN);
    expect(timeline.arriveAt).toBe(T0 + 30 * MIN);
    expect(options.length).toBeGreaterThan(0); // later suggested times survive
    expect(options[0].departureMs).toBe(T0 + 12 * MIN);
    expect(options[0].timeline).toBe(timeline);
  });

  it('keeps the earliest departure as the primary timeline and first option', () => {
    const input: TripTimelineInput = {
      now: T0,
      legs: [
        leg({ departureStopId: 'A', departureStopName: 'Stop A', destinationStopId: 'B', destinationStopName: 'Stop B' }),
      ],
      departureArrivals: new Map([[0, [arrival('T1', T0 + 5 * MIN), arrival('T2', T0 + 9 * MIN)]]]),
      destinationArrivals: new Map(),
      tripDetails: new Map([
        ['T1', tripDetails('T1', [['B', T0 + 8 * MIN]])],
        ['T2', tripDetails('T2', [['B', T0 + 12 * MIN]])],
      ]),
      getStopCoords,
    };
    const { timeline, options } = buildTripTimelines(input);
    expect(timeline.leaveAt).toBe(T0 + 5 * MIN);
    expect(options[0].timeline).toBe(timeline);
    expect(options.map((o) => o.departureMs)).toEqual([T0 + 5 * MIN, T0 + 9 * MIN]);
  });

  it('still falls back to persisted leg times when nothing live resolves', () => {
    const saved = leg({
      departureStopId: 'A',
      departureStopName: 'Stop A',
      destinationStopId: 'B',
      destinationStopName: 'Stop B',
      departureMs: T0 + 3 * MIN,
      arrivalMs: T0 + 9 * MIN,
    });
    const input: TripTimelineInput = {
      now: T0,
      legs: [saved],
      departureArrivals: new Map(),
      destinationArrivals: new Map(),
      tripDetails: new Map(),
      getStopCoords,
    };
    const { timeline, options } = buildTripTimelines(input);
    expect(options).toHaveLength(0);
    expect(timeline.leaveAt).toBe(T0 + 3 * MIN);
    expect(timeline.arriveAt).toBe(T0 + 9 * MIN);
  });
});

describe('too-tight departures', () => {
  it('uses the actual origin walk distance instead of a fixed walk duration', () => {
    const input: TripTimelineInput = {
      legs: [leg({ originAddress: { name: 'Home', distanceMeters: 720 } })],
      departureArrivals: new Map(),
      destinationArrivals: new Map(),
      tripDetails: new Map(),
      getStopCoords,
    };

    expect(originWalkMinutes(input)).toBe(10);

    const routedInput: TripTimelineInput = {
      ...input,
      legs: [leg({ originAddress: { name: 'Home', distanceMeters: 100, walkingMinutes: 13 } })],
    };
    expect(originWalkMinutes(routedInput)).toBe(13);
  });

  it('surfaces unreachable-on-foot departures as flagged tooTight options while the timeline stays catchable', () => {
    const input: TripTimelineInput = {
      now: T0,
      legs: [
        leg({
          departureStopId: 'A',
          departureStopName: 'Stop A',
          destinationStopId: 'B',
          destinationStopName: 'Stop B',
          originAddress: { name: 'Home', distanceMeters: 300 }, // 5 min walk + 2 min buffer
        }),
      ],
      departureArrivals: new Map([
        [0, [
          arrival('T1', T0 + 1 * MIN),
          arrival('T1b', T0 + 1 * MIN + 30_000), // same minute bucket as T1
          arrival('T2', T0 + 3 * MIN),
          arrival('T3', T0 + 5 * MIN), // still inside the tight window, dropped by the cap
          arrival('T4', T0 + 20 * MIN),
          arrival('T5', T0 + 27 * MIN),
        ]],
      ]),
      destinationArrivals: new Map(),
      tripDetails: new Map([
        ['T1', tripDetails('T1', [['B', T0 + 6 * MIN]])],
        ['T1b', tripDetails('T1b', [['B', T0 + 7 * MIN]])],
        ['T2', tripDetails('T2', [['B', T0 + 8 * MIN]])],
        ['T3', tripDetails('T3', [['B', T0 + 10 * MIN]])],
        ['T4', tripDetails('T4', [['B', T0 + 25 * MIN]])],
        ['T5', tripDetails('T5', [['B', T0 + 32 * MIN]])],
      ]),
      getStopCoords,
    };
    const { timeline, options, tooTightOptions } = buildTripTimelines(input);
    // Minute-deduped, capped at two, earliest first.
    expect(tooTightOptions.map((o) => o.departureMs)).toEqual([T0 + 1 * MIN, T0 + 3 * MIN]);
    expect(tooTightOptions.every((o) => o.tooTight === true)).toBe(true);
    // Each tight option carries the full chained journey for that vehicle, and
    // its back-planned walk honestly starts in the past.
    const firstTransit = tooTightOptions[0].timeline.legs!.find((l) => l.mode !== 'WALK')!;
    expect(firstTransit.departureMs).toBe(T0 + 1 * MIN);
    expect(tooTightOptions[0].timeline.leaveAt).toBe(T0 - 6 * MIN);
    // The primary recommendation and the normal options ignore them — the
    // hero is still the catchable tram (its leave time is the back-planned
    // walk start: 20 min departure − 5 min walk − 2 min buffer).
    expect(options[0].departureMs).toBe(T0 + 20 * MIN);
    expect(timeline.leaveAt).toBe(T0 + 13 * MIN);
    expect(options.map((o) => o.departureMs)).toEqual([T0 + 20 * MIN, T0 + 27 * MIN]);
    expect(options[0].tooTight).toBeUndefined();
  });

  it('has no too-tight options when the trip starts at the stop', () => {
    const input: TripTimelineInput = {
      now: T0,
      legs: [
        leg({ departureStopId: 'A', departureStopName: 'Stop A', destinationStopId: 'B', destinationStopName: 'Stop B' }),
      ],
      departureArrivals: new Map([[0, [arrival('T1', T0 + 1 * MIN), arrival('T2', T0 + 10 * MIN)]]]),
      destinationArrivals: new Map(),
      tripDetails: new Map([
        ['T1', tripDetails('T1', [['B', T0 + 4 * MIN]])],
        ['T2', tripDetails('T2', [['B', T0 + 13 * MIN]])],
      ]),
      getStopCoords,
    };
    const { tooTightOptions, options } = buildTripTimelines(input);
    expect(tooTightOptions).toEqual([]);
    expect(options.map((o) => o.departureMs)).toEqual([T0 + 1 * MIN, T0 + 10 * MIN]);
  });

  it('skips a too-tight departure whose chain cannot resolve', () => {
    const input: TripTimelineInput = {
      now: T0,
      legs: [
        leg({
          departureStopId: 'A',
          departureStopName: 'Stop A',
          destinationStopId: 'B',
          destinationStopName: 'Stop B',
          originAddress: { name: 'Home', distanceMeters: 300 },
        }),
      ],
      departureArrivals: new Map([
        [0, [arrival('T1', T0 + 1 * MIN), arrival('T2', T0 + 3 * MIN), arrival('T3', T0 + 5 * MIN)]],
      ]),
      destinationArrivals: new Map(),
      // T1 has no trip-details: its arrival is unresolvable, so it must not
      // surface — the cap counts only chains that fully resolve.
      tripDetails: new Map([
        ['T2', tripDetails('T2', [['B', T0 + 8 * MIN]])],
        ['T3', tripDetails('T3', [['B', T0 + 10 * MIN]])],
      ]),
      getStopCoords,
    };
    const { tooTightOptions } = buildTripTimelines(input);
    expect(tooTightOptions.map((o) => o.departureMs)).toEqual([T0 + 3 * MIN, T0 + 5 * MIN]);
  });
});

describe('estimateWalkLeg', () => {
  it('returns null for the same stop', () => {
    const from = leg({ destinationStopId: 'B', destinationStopName: 'Stop B' });
    const to = leg({ departureStopId: 'B', departureStopName: 'Stop B' });
    expect(estimateWalkLeg(from, to, getStopCoords)).toBeNull();
  });

  it('estimates a walk for a same-area surface → underground transfer (tram → metro)', () => {
    const from = leg({ routeType: 'TRAM', destinationStopId: 'B', destinationStopName: 'Stop B' });
    const to = leg({ routeType: 'SUBWAY', departureStopId: 'B', departureStopName: 'Stop B' });
    const walk = estimateWalkLeg(from, to, getStopCoords)!;
    expect(walk).not.toBeNull();
    expect(walk.mode).toBe('WALK');
    expect(walk.departureStopName).toBe('Stop B');
    expect(walk.destinationStopName).toBe('Stop B');
    expect(walk.durationMinutes).toBeGreaterThanOrEqual(1);
  });

  it('estimates a walk for a same-area underground → surface transfer (metro → tram)', () => {
    const from = leg({ routeType: 'SUBWAY', destinationStopId: 'B', destinationStopName: 'Stop B' });
    const to = leg({ routeType: 'TRAM', departureStopId: 'B', departureStopName: 'Stop B' });
    expect(estimateWalkLeg(from, to, getStopCoords)).not.toBeNull();
  });

  it('returns null for a same-area surface → surface transfer (tram → bus, same platform)', () => {
    const from = leg({ routeType: 'TRAM', destinationStopId: 'B', destinationStopName: 'Stop B' });
    const to = leg({ routeType: 'BUS', departureStopId: 'B', departureStopName: 'Stop B' });
    expect(estimateWalkLeg(from, to, getStopCoords)).toBeNull();
  });

  it('returns null when continuing on the same metro line at the same stop area', () => {
    const routes = [{ routeId: 'BKK_M3', routeShortName: 'M3', routeColor: '003DA5', routeTextColor: 'FFFFFF' }];
    const from = leg({ routeType: 'SUBWAY', routes, destinationStopId: 'B', destinationStopName: 'Kálvin tér' });
    const to = leg({ routeType: 'SUBWAY', routes, departureStopId: 'B', departureStopName: 'Kálvin tér' });
    expect(estimateWalkLeg(from, to, getStopCoords)).toBeNull();
  });

  it('estimates a walk between different metro lines at the same stop area', () => {
    const from = leg({
      routeType: 'SUBWAY',
      routes: [{ routeId: 'BKK_M3', routeShortName: 'M3', routeColor: '003DA5', routeTextColor: 'FFFFFF' }],
      destinationStopId: 'B',
      destinationStopName: 'Kálvin tér',
    });
    const to = leg({
      routeType: 'SUBWAY',
      routes: [{ routeId: 'BKK_M4', routeShortName: 'M4', routeColor: '00A650', routeTextColor: 'FFFFFF' }],
      departureStopId: 'B',
      departureStopName: 'Kálvin tér',
    });
    const walk = estimateWalkLeg(from, to, getStopCoords);
    expect(walk).not.toBeNull();
    expect(walk!.mode).toBe('WALK');
    expect(walk!.distanceMeters).toBeGreaterThan(0);
    expect(walk!.durationMinutes).toBeGreaterThanOrEqual(1);
    expect(walk!.departureStopName).toBe('Kálvin tér');
    expect(walk!.destinationStopName).toBe('Kálvin tér');
  });

  it('returns null when either stop is missing from the catalog', () => {
    const from = leg({ destinationStopId: 'B', destinationStopName: 'Stop B' });
    const to = leg({ departureStopId: 'UNKNOWN', departureStopName: 'Stop X' });
    expect(estimateWalkLeg(from, to, getStopCoords)).toBeNull();
  });

  it('estimates distance and duration between differing stops', () => {
    const from = leg({ destinationStopId: 'A', destinationStopName: 'Stop A' });
    const to = leg({ departureStopId: 'B', departureStopName: 'Stop B' });
    const walk = estimateWalkLeg(from, to, getStopCoords)!;
    expect(walk.mode).toBe('WALK');
    expect(walk.routes).toEqual([]);
    expect(walk.distanceMeters).toBeGreaterThan(0);
    expect(walk.durationMinutes).toBeGreaterThanOrEqual(1);
    expect(walk.departureStopName).toBe('Stop A');
    expect(walk.destinationStopName).toBe('Stop B');
  });
});

describe('formatTripClock / tripSummary', () => {
  it('formats alternative chips from the journey leave time, including too-tight options', () => {
    const option = {
      timeline: {
        legs: [],
        leaveAt: T0 - 6 * MIN,
        arriveAt: T0 + 4 * MIN,
        totalMinutes: 10,
      },
    };

    expect(formatTripOptionLeaveTime(option)).toBe('09:54');
  });

  it('formats Budapest HH:MM', () => {
    expect(formatTripClock(new Date('2026-09-10T10:05:00+02:00').getTime())).toBe('10:05');
  });

  it('summarizes a timed timeline', () => {
    const timeline = buildTripTimeline({
      now: T0,
      legs: [leg({ departureStopId: 'A', departureStopName: 'Stop A', destinationStopId: 'B', destinationStopName: 'Stop B' })],
      departureArrivals: new Map([[0, [arrival('T1', T0 + 2 * MIN)]]]),
      destinationArrivals: new Map([[0, [arrival('T1', T0 + 6 * MIN)]]]),
      tripDetails: new Map(),
      getStopCoords,
    });
    expect(tripSummary(timeline)).toEqual({
      leaveAt: '10:02',
      arriveAt: '10:06',
      durationMin: 4,
    });
  });

  it('returns null for an untimed (null) timeline', () => {
    const timeline = buildTripTimeline({
      now: T0,
      legs: [leg({ departureStopId: 'A', departureStopName: 'Stop A', destinationStopId: 'B', destinationStopName: 'Stop B' })],
      departureArrivals: new Map(),
      destinationArrivals: new Map(),
      tripDetails: new Map(),
      getStopCoords,
    });
    expect(tripSummary(timeline)).toBeNull();
  });
});

describe('legsWithAddressWalks', () => {
  it('synthesizes origin and destination walks from address metadata', () => {
    const legs = [
      leg({
        departureStopId: 'A',
        departureStopName: 'Stop A',
        destinationStopId: 'B',
        destinationStopName: 'Stop B',
        originAddress: { name: 'Home', distanceMeters: 300 },
        destinationAddress: { name: 'Work', distanceMeters: 400 },
      }),
    ];
    const out = legsWithAddressWalks(legs);
    expect(out.map((l) => l.mode ?? 'TRANSIT')).toEqual(['WALK', 'TRANSIT', 'WALK']);
    expect(out[0].departureStopName).toBe('Home');
    expect(out[0].destinationStopName).toBe('Stop A');
    expect(out[0].distanceMeters).toBe(300);
    expect(out[0].durationMinutes).toBeGreaterThanOrEqual(1);
    expect(out[2].departureStopName).toBe('Stop B');
    expect(out[2].destinationStopName).toBe('Work');
    expect(out[2].distanceMeters).toBe(400);
  });

  it('keeps saved legs that already start or end with a walk', () => {
    const walk = leg({ mode: 'WALK', routeType: 'WALK', routes: [] });
    const transit = leg({ originAddress: { name: 'Home', distanceMeters: 300 } });
    const saved = [walk, transit, walk];
    expect(legsWithAddressWalks(saved)).toBe(saved);
  });

  it('returns the input untouched when there are no address walks', () => {
    const saved = [leg({ departureStopId: 'A', departureStopName: 'Stop A' })];
    expect(legsWithAddressWalks(saved)).toBe(saved);
  });
});
describe('buildTripTimeline arrival resolution', () => {
  const oneLeg = (details: FutarTripDetailsResponse, destination: Arrival[] = []): TripTimelineInput => ({
    now: T0,
    legs: [
      leg({
        departureStopId: 'A',
        departureStopName: 'Stop A',
        destinationStopId: 'B',
        destinationStopName: 'Stop B',
      }),
    ],
    departureArrivals: new Map([[0, [arrival('T1', T0 + 5 * MIN, true)]]]),
    destinationArrivals: new Map([[0, destination]]),
    tripDetails: new Map([['T1', details]]),
    getStopCoords,
  });
  const withStopTimes = (stopTimes: NonNullable<NonNullable<FutarTripDetailsResponse['data']>['entry']>['stopTimes']) =>
    ({ status: 'OK', data: { entry: { tripId: 'T1', stopTimes } } }) as FutarTripDetailsResponse;
  const sec = (ms: number) => Math.round(ms / 1000);

  it('prefers the predicted arrival over the scheduled times', () => {
    const details = withStopTimes([
      { stopId: 'B', arrivalTime: sec(T0 + 9 * MIN), departureTime: sec(T0 + 10 * MIN), predictedArrivalTime: sec(T0 + 11 * MIN) },
    ]);
    expect(buildTripTimeline(oneLeg(details)).arriveAt).toBe(T0 + 11 * MIN);
  });

  it('uses the scheduled arrival (not departure) when there is no prediction', () => {
    const details = withStopTimes([
      { stopId: 'B', arrivalTime: sec(T0 + 9 * MIN), departureTime: sec(T0 + 10 * MIN) },
    ]);
    expect(buildTripTimeline(oneLeg(details)).arriveAt).toBe(T0 + 9 * MIN);
  });

  it('resolves a terminus that only carries arrivalTime', () => {
    const details = withStopTimes([{ stopId: 'B', arrivalTime: sec(T0 + 14 * MIN) }]);
    expect(buildTripTimeline(oneLeg(details)).arriveAt).toBe(T0 + 14 * MIN);
  });

  it('rejects an arrival before the boarding departure and falls back', () => {
    const details = withStopTimes([{ stopId: 'B', arrivalTime: sec(T0 + 2 * MIN) }]);
    expect(buildTripTimeline(oneLeg(details)).legs).toBeNull();
    const fallback = buildTripTimeline(oneLeg(details, [arrival('T1', T0 + 12 * MIN, true)]));
    expect(fallback.arriveAt).toBe(T0 + 12 * MIN);
  });
});
