import { describe, expect, it } from 'vitest';
import {
  angleDifference,
  bearingAlongPolyline,
  bearingDegrees,
  cumulativeDistances,
  cumulativeHaversineDistances,
  decodePolyline,
  haversineMeters,
  makeProjector,
  nextStopIndex,
  placeVehiclesOnPolyline,
  pointAtDistance,
  pointAtFraction,
  projectVehicleOntoPolyline,
  resolveStopRefs,
  selectTripPolyline,
  selectTripStopIds,
  selectVariantPolylines,
  selectVariantStopIds,
  snapToPolyline,
  type LatLon,
  type Point,
} from './route-geometry';
import type { FutarRouteVariant, FutarStop, FutarTripDetailsResponse } from './types';

describe('decodePolyline', () => {
  it('decodes the canonical Google example', () => {
    // From the Google Maps polyline encoding docs:
    // (38.5, -120.2), (40.7, -120.95), (43.252, -126.453)
    const result = decodePolyline('_p~iF~ps|U_ulLnnqC_mqNvxq`@');
    expect(result).toEqual([
      { lat: 38.5, lon: -120.2 },
      { lat: 40.7, lon: -120.95 },
      { lat: 43.252, lon: -126.453 },
    ]);
  });

  it('decodes a single point', () => {
    expect(decodePolyline('_p~iF~ps|U')).toEqual([{ lat: 38.5, lon: -120.2 }]);
  });
});

describe('makeProjector', () => {
  it('maps lon to x and keeps north (higher lat) at smaller y', () => {
    const project = makeProjector(
      [
        { lat: 47.4, lon: 19.0 },
        { lat: 47.6, lon: 19.2 },
      ],
      100,
      100
    );
    const sw = project({ lat: 47.4, lon: 19.0 });
    const ne = project({ lat: 47.6, lon: 19.2 });
    expect(ne.x).toBeGreaterThan(sw.x);
    expect(ne.y).toBeLessThan(sw.y);
  });

  it('centers a single point', () => {
    const project = makeProjector([{ lat: 47.5, lon: 19.1 }], 200, 100);
    const p = project({ lat: 47.5, lon: 19.1 });
    expect(p.x).toBe(100);
    expect(p.y).toBe(50);
  });

  it('applies uniform padding', () => {
    const project = makeProjector(
      [
        { lat: 47.4, lon: 19.0 },
        { lat: 47.6, lon: 19.2 },
      ],
      100,
      100
    );
    const sw = project({ lat: 47.4, lon: 19.0 });
    const ne = project({ lat: 47.6, lon: 19.2 });
    expect(sw.x).toBeGreaterThanOrEqual(8);
    expect(sw.y).toBeLessThanOrEqual(92);
    expect(ne.x).toBeLessThanOrEqual(92);
    expect(ne.y).toBeGreaterThanOrEqual(8);
  });
});

describe('projectVehicleOntoPolyline', () => {
  it('projects onto the nearest segment and reports distance', () => {
    const line: Point[] = [
      { x: 0, y: 0 },
      { x: 10, y: 0 },
    ];
    const result = projectVehicleOntoPolyline({ x: 5, y: 4 }, line);
    expect(result).not.toBeNull();
    expect(result!.point).toEqual({ x: 5, y: 0 });
    expect(result!.distance).toBe(5);
  });

  it('clamps beyond the ends of the line', () => {
    const line: Point[] = [
      { x: 0, y: 0 },
      { x: 10, y: 0 },
    ];
    expect(projectVehicleOntoPolyline({ x: 20, y: 0 }, line)!.point).toEqual({ x: 10, y: 0 });
    expect(projectVehicleOntoPolyline({ x: -5, y: 0 }, line)!.point).toEqual({ x: 0, y: 0 });
  });

  it('returns null for an empty polyline', () => {
    expect(projectVehicleOntoPolyline({ x: 0, y: 0 }, [])).toBeNull();
  });
});

describe('cumulativeDistances', () => {
  it('accumulates segment lengths', () => {
    expect(
      cumulativeDistances([
        { x: 0, y: 0 },
        { x: 3, y: 4 },
        { x: 3, y: 4 },
        { x: 13, y: 4 },
      ])
    ).toEqual([0, 5, 5, 15]);
  });
});

describe('nextStopIndex', () => {
  it('returns the first stop the vehicle has not yet reached', () => {
    expect(nextStopIndex([0, 5, 10, 15], 6)).toBe(2);
  });

  it('clamps to the last stop when the vehicle is past everything', () => {
    expect(nextStopIndex([0, 5, 10], 99)).toBe(2);
  });
});

describe('pointAtFraction', () => {
  const line: LatLon[] = [
    { lat: 47.5, lon: 19.04 },
    { lat: 47.5, lon: 19.05 }, // ~700m east
    { lat: 47.5, lon: 19.06 },
  ];

  it('returns null for an empty polyline', () => {
    expect(pointAtFraction([], 0.5)).toBeNull();
  });

  it('returns the single point for a one-point polyline', () => {
    expect(pointAtFraction([line[0]], 0.5)).toEqual(line[0]);
  });

  it('returns the first/last point at fractions 0 and 1', () => {
    expect(pointAtFraction(line, 0)).toEqual(line[0]);
    expect(pointAtFraction(line, 1)).toEqual(line[line.length - 1]);
  });

  it('clamps out-of-range fractions', () => {
    expect(pointAtFraction(line, -1)).toEqual(line[0]);
    expect(pointAtFraction(line, 2)).toEqual(line[line.length - 1]);
  });

  it('interpolates a midpoint along equal segments', () => {
    const mid = pointAtFraction(line, 0.5);
    expect(mid).not.toBeNull();
    expect(mid!.lon).toBeCloseTo(19.05, 5);
    expect(mid!.lat).toBeCloseTo(47.5, 5);
  });
});

describe('snapToPolyline', () => {
  const eastLine: LatLon[] = [
    { lat: 47.5, lon: 19.0 },
    { lat: 47.5, lon: 19.01 },
  ];

  it('keeps a point that sits exactly on the line in place', () => {
    const r = snapToPolyline({ lat: 47.5, lon: 19.005 }, eastLine);
    expect(r).not.toBeNull();
    expect(r!.point.lat).toBeCloseTo(47.5, 5);
    expect(r!.point.lon).toBeCloseTo(19.005, 5);
  });

  it('projects an off-line point onto the nearest segment', () => {
    // ~111m north of the line — GPS noise — snaps straight back onto it.
    const r = snapToPolyline({ lat: 47.501, lon: 19.004 }, eastLine);
    expect(r!.point.lat).toBeCloseTo(47.5, 5);
    expect(r!.point.lon).toBeCloseTo(19.004, 5);
  });

  it('clamps beyond the ends of the line', () => {
    expect(snapToPolyline({ lat: 47.5, lon: 19.05 }, eastLine)!.point).toEqual(eastLine[eastLine.length - 1]);
    expect(snapToPolyline({ lat: 47.5, lon: 18.9 }, eastLine)!.point).toEqual(eastLine[0]);
  });

  it('returns null for an empty polyline and the point for a single-point line', () => {
    expect(snapToPolyline({ lat: 1, lon: 2 }, [])).toBeNull();
    expect(snapToPolyline({ lat: 1, lon: 2 }, [{ lat: 1, lon: 2 }])).toEqual({
      point: { lat: 1, lon: 2 },
      distance: 0,
    });
  });

  it('accumulates distance along the polyline to the snapped point', () => {
    // L-shaped line: east 1°, then north 1°. Snap 25% up the north leg.
    const line: LatLon[] = [
      { lat: 47.5, lon: 19.0 },
      { lat: 47.5, lon: 19.01 },
      { lat: 47.51, lon: 19.01 },
    ];
    const r = snapToPolyline({ lat: 47.5025, lon: 19.01 }, line)!;
    const east = haversineMeters(line[0], line[1]);
    expect(r.distance).toBeCloseTo(east + 0.25 * haversineMeters(line[1], line[2]), 1);
    expect(r.point.lat).toBeCloseTo(47.5025, 5);
    expect(r.point.lon).toBeCloseTo(19.01, 5);
  });
});

describe('cumulativeHaversineDistances + pointAtDistance', () => {
  const line: LatLon[] = [
    { lat: 47.5, lon: 19.0 },
    { lat: 47.5, lon: 19.01 }, // east leg
    { lat: 47.51, lon: 19.01 }, // north leg
  ];

  it('accumulates per-segment geographic lengths', () => {
    const dists = cumulativeHaversineDistances(line);
    expect(dists[0]).toBe(0);
    expect(dists[1]).toBeCloseTo(haversineMeters(line[0], line[1]), 1);
    expect(dists[2]).toBeCloseTo(dists[1] + haversineMeters(line[1], line[2]), 1);
  });

  it('interpolates the point at a distance along the polyline', () => {
    const dists = cumulativeHaversineDistances(line);
    const pt = pointAtDistance(line, dists, dists[1] / 2);
    expect(pt.lat).toBeCloseTo(47.5, 5);
    expect(pt.lon).toBeCloseTo(19.005, 5);
  });

  it('moves onto the second leg once the first is exhausted', () => {
    const dists = cumulativeHaversineDistances(line);
    const pt = pointAtDistance(line, dists, dists[1] + 0.5 * (dists[2] - dists[1]));
    expect(pt.lat).toBeCloseTo(47.505, 5);
    expect(pt.lon).toBeCloseTo(19.01, 5);
  });

  it('clamps to the ends', () => {
    const dists = cumulativeHaversineDistances(line);
    expect(pointAtDistance(line, dists, -100)).toEqual(line[0]);
    expect(pointAtDistance(line, dists, 1e9)).toEqual(line[line.length - 1]);
  });
});

describe('selectVariantPolylines', () => {
  // Canonical Google example polyline: 3 points.
  const ENCODED = '_p~iF~ps|U_ulLnnqC_mqNvxq`@';
  const POINTS = [
    { lat: 38.5, lon: -120.2 },
    { lat: 40.7, lon: -120.95 },
    { lat: 43.252, lon: -126.453 },
  ];

  function variant(headsign: string, points?: string): FutarRouteVariant {
    return { headsign, polyline: points === undefined ? { points: ENCODED } : { points } };
  }

  it('returns only the exact headsign match', () => {
    const result = selectVariantPolylines([variant('A'), variant('B')], 'B');
    expect(result).toEqual([POINTS]);
  });

  it('matches case/diacritic/whitespace-insensitively', () => {
    const result = selectVariantPolylines(
      [variant('Örs vezér tere'), variant('Széll Kálmán tér M')],
      'ors  vezer   tere'
    );
    expect(result).toEqual([POINTS]);
  });

  it('falls back to every decodable variant when nothing matches or no headsign is given', () => {
    const variants = [variant('A'), variant('B')];
    expect(selectVariantPolylines(variants, 'Unknown')).toEqual([POINTS, POINTS]);
    expect(selectVariantPolylines(variants, null)).toEqual([POINTS, POINTS]);
  });

  it('skips variants without enough points', () => {
    const result = selectVariantPolylines([variant('A'), variant('B', '_p~iF~ps|U')], 'A');
    expect(result).toEqual([POINTS]);
  });

  it('restricts same-headsign variants to the selected member route', () => {
    // Combined lines (4/6): both members expose the same terminus headsign;
    // the selected departure's routeId must pick only its member's variant.
    const variants: FutarRouteVariant[] = [
      { headsign: 'Móricz Zsigmond körtér M', routeId: 'BKK_4', polyline: { points: ENCODED } },
      { headsign: 'Móricz Zsigmond körtér M', routeId: 'BKK_6', polyline: { points: '_p~iF~ps|U_ulLnnqC' } },
    ];
    expect(selectVariantPolylines(variants, 'Móricz Zsigmond körtér M', 'BKK_6')).toEqual([
      [
        { lat: 38.5, lon: -120.2 },
        { lat: 40.7, lon: -120.95 },
      ],
    ]);
  });

  it('ignores the routeId when no variant carries it (backward compatible)', () => {
    const variants = [variant('A'), variant('B')];
    expect(selectVariantPolylines(variants, 'A', 'BKK_99')).toEqual([POINTS]);
    expect(selectVariantPolylines(variants, null, 'BKK_99')).toEqual([POINTS, POINTS]);
  });

  it('keeps existing headsign-only behavior when no routeId is given', () => {
    const variants = [variant('A'), variant('B')];
    expect(selectVariantPolylines(variants, 'B')).toEqual([POINTS]);
  });
});

describe('selectVariantStopIds', () => {
  function variant(headsign: string, stopIds: string[]): FutarRouteVariant {
    return { headsign, stopIds };
  }

  it('returns the stops of the exact headsign match', () => {
    const result = selectVariantStopIds(
      [variant('A', ['a1', 'a2']), variant('B', ['b1'])],
      'B'
    );
    expect(result).toEqual(['b1']);
  });

  it('matches case/diacritic/whitespace-insensitively', () => {
    const result = selectVariantStopIds(
      [variant('Örs vezér tere', ['x1']), variant('Széll Kálmán tér M', ['y1'])],
      'ors  vezer   tere'
    );
    expect(result).toEqual(['x1']);
  });

  it('falls back to all variants when nothing matches or no headsign is given', () => {
    const variants = [variant('A', ['a1']), variant('B', ['b1'])];
    expect(selectVariantStopIds(variants, 'Unknown')).toEqual(['a1', 'b1']);
    expect(selectVariantStopIds(variants, null)).toEqual(['a1', 'b1']);
  });

  it('concatenates matched variants, collapsing duplicates in first-seen order', () => {
    const variants = [variant('A', ['s1', 's2']), variant('A', ['s2', 's3', 's1'])];
    expect(selectVariantStopIds(variants, 'A')).toEqual(['s1', 's2', 's3']);
  });

  it('returns no ids for empty input or variants without stops', () => {
    expect(selectVariantStopIds([], 'A')).toEqual([]);
    expect(selectVariantStopIds([variant('A', [])], 'A')).toEqual([]);
  });

  it('restricts same-headsign variants to the selected member route', () => {
    const variants: FutarRouteVariant[] = [
      { headsign: 'Móricz Zsigmond körtér M', routeId: 'BKK_4', stopIds: ['a1', 'a2'] },
      { headsign: 'Móricz Zsigmond körtér M', routeId: 'BKK_6', stopIds: ['b1'] },
    ];
    expect(selectVariantStopIds(variants, 'Móricz Zsigmond körtér M', 'BKK_6')).toEqual(['b1']);
  });

  it('ignores the routeId when no variant carries it', () => {
    const variants: FutarRouteVariant[] = [
      { headsign: 'A', routeId: 'BKK_4', stopIds: ['a1'] },
      { headsign: 'A', routeId: 'BKK_6', stopIds: ['b1'] },
    ];
    expect(selectVariantStopIds(variants, 'A', 'BKK_99')).toEqual(['a1', 'b1']);
  });
});

describe('selectTripPolyline', () => {
  // Canonical Google example polyline: 3 points.
  const ENCODED = '_p~iF~ps|U_ulLnnqC_mqNvxq`@';
  const POINTS = [
    { lat: 38.5, lon: -120.2 },
    { lat: 40.7, lon: -120.95 },
    { lat: 43.252, lon: -126.453 },
  ];

  it("decodes the trip entry's own polyline", () => {
    const trip: FutarTripDetailsResponse = {
      data: { entry: { tripId: 'trip-1', polyline: { points: ENCODED } } },
    };
    expect(selectTripPolyline(trip)).toEqual(POINTS);
  });

  it('returns an empty array when the trip has no polyline', () => {
    expect(selectTripPolyline(undefined)).toEqual([]);
    expect(selectTripPolyline({ data: { entry: { tripId: 'trip-1' } } })).toEqual([]);
    expect(
      selectTripPolyline({ data: { entry: { tripId: 'trip-1', polyline: { points: '' } } } })
    ).toEqual([]);
  });
});

describe('selectTripStopIds', () => {
  it('keeps stopTimes order, collapsing duplicate and blank ids', () => {
    const trip: FutarTripDetailsResponse = {
      data: {
        entry: {
          tripId: 'trip-1',
          stopTimes: [
            { stopId: 'a' },
            { stopId: 'b' },
            { stopId: 'a' },
            { stopId: '' },
            { stopId: 'c' },
          ],
        },
      },
    };
    expect(selectTripStopIds(trip)).toEqual(['a', 'b', 'c']);
  });

  it('returns an empty array without stopTimes', () => {
    expect(selectTripStopIds(undefined)).toEqual([]);
    expect(selectTripStopIds({ data: { entry: { tripId: 'trip-1' } } })).toEqual([]);
  });
});

describe('resolveStopRefs', () => {
  const refs: Record<string, FutarStop> = {
    a: { id: 'a', name: 'Alpha', lat: 47.5, lon: 19.0 },
    b: { id: 'b', name: 'Zero', lat: 0, lon: 0 },
    c: { id: 'c', name: 'Gamma', lat: 47.51, lon: 19.01 },
  };

  it('maps ids to refs in order, dropping missing and zero-coordinate entries', () => {
    expect(resolveStopRefs(['c', 'missing', 'b', 'a'], refs)).toEqual([
      { id: 'c', name: 'Gamma', lat: 47.51, lon: 19.01 },
      { id: 'a', name: 'Alpha', lat: 47.5, lon: 19.0 },
    ]);
  });

  it('returns an empty array without references', () => {
    expect(resolveStopRefs(['a'], undefined)).toEqual([]);
  });
});

describe('placeVehiclesOnPolyline', () => {
  it('snaps a vehicle onto the line and points it north', () => {
    const line: LatLon[] = [
      { lat: 47.49, lon: 19.04 },
      { lat: 47.5, lon: 19.04 },
    ];
    const [placed] = placeVehiclesOnPolyline([{ id: 'v1', lat: 47.495, lon: 19.0405 }], line);
    expect(placed.id).toBe('v1');
    expect(placed.lon).toBeCloseTo(19.04, 4);
    expect(placed.bearing).toBeCloseTo(0, 0);
  });

  it('points an eastbound vehicle east', () => {
    const line: LatLon[] = [
      { lat: 47.49, lon: 19.04 },
      { lat: 47.49, lon: 19.05 },
    ];
    const [placed] = placeVehiclesOnPolyline([{ id: 'v1', lat: 47.49, lon: 19.041 }], line);
    expect(placed.bearing).toBeCloseTo(90, 0);
  });

  it('keeps raw coordinates and bearing 0 without a usable polyline', () => {
    const placed = placeVehiclesOnPolyline([{ id: 'v1', lat: 47.49, lon: 19.04 }], []);
    expect(placed).toEqual([{ id: 'v1', lat: 47.49, lon: 19.04, bearing: 0 }]);
  });

  it('keeps the direction of travel for a vehicle at the end of the line', () => {
    const line: LatLon[] = [
      { lat: 47.49, lon: 19.04 },
      { lat: 47.5, lon: 19.04 },
    ];
    const [placed] = placeVehiclesOnPolyline([{ id: 'v1', lat: 47.5, lon: 19.04 }], line);
    expect(placed.bearing).toBeCloseTo(0, 0);
  });

  it('points a past-the-end vehicle along the line', () => {
    const line: LatLon[] = [
      { lat: 47.49, lon: 19.04 },
      { lat: 47.5, lon: 19.04 },
    ];
    const [placed] = placeVehiclesOnPolyline([{ id: 'v1', lat: 47.51, lon: 19.04 }], line);
    expect(placed.lat).toBeCloseTo(47.5, 5);
    expect(placed.bearing).toBeCloseTo(0, 0);
  });
});

describe('bearingDegrees and bearingAlongPolyline', () => {
  it('calculates cardinal bearings correctly', () => {
    const origin: LatLon = { lat: 47.49, lon: 19.04 };
    expect(bearingDegrees(origin, { lat: 47.5, lon: 19.04 })).toBeCloseTo(0, 0); // North
    expect(bearingDegrees(origin, { lat: 47.49, lon: 19.05 })).toBeCloseTo(90, 0); // East
    expect(bearingDegrees(origin, { lat: 47.48, lon: 19.04 })).toBeCloseTo(180, 0); // South
    expect(bearingDegrees(origin, { lat: 47.49, lon: 19.03 })).toBeCloseTo(270, 0); // West
  });

  it('calculates bearing along a segmented polyline with a bend', () => {
    // Segment 1: Heading North from (47.49, 19.04) to (47.50, 19.04)
    // Segment 2: Heading East from (47.50, 19.04) to (47.50, 19.06)
    const line: LatLon[] = [
      { lat: 47.49, lon: 19.04 },
      { lat: 47.50, lon: 19.04 },
      { lat: 47.50, lon: 19.06 },
    ];
    const dists = cumulativeHaversineDistances(line);

    // Midpoint of segment 1 (heading North)
    const dSeg1 = dists[1] / 2;
    expect(bearingAlongPolyline(line, dists, dSeg1)).toBeCloseTo(0, 0);

    // Midpoint of segment 2 (heading East)
    const dSeg2 = dists[1] + (dists[2] - dists[1]) / 2;
    expect(bearingAlongPolyline(line, dists, dSeg2)).toBeCloseTo(90, 0);

    // Near the end of polyline (still heading East)
    expect(bearingAlongPolyline(line, dists, dists[2])).toBeCloseTo(90, 0);

    // Empty or 1-point polyline returns 0
    expect(bearingAlongPolyline([], [], 0)).toBe(0);
    expect(bearingAlongPolyline([{ lat: 47.5, lon: 19.0 }], [0], 0)).toBe(0);
  });
});

describe('angleDifference', () => {
  it('calculates the shortest angular difference in degrees', () => {
    expect(angleDifference(0, 0)).toBe(0);
    expect(angleDifference(0, 90)).toBe(90);
    expect(angleDifference(90, 0)).toBe(90);
    expect(angleDifference(0, 180)).toBe(180);
    expect(angleDifference(10, 350)).toBe(20);
    expect(angleDifference(350, 10)).toBe(20);
    expect(angleDifference(45, 225)).toBe(180);
  });
});

describe('snapToPolyline with preferredBearing', () => {
  it('prefers segment matching preferredBearing on two-way lines', () => {
    // A two-way road:
    // Seg 0..1: heading North from (47.49, 19.0400) to (47.50, 19.0400)
    // Seg 1..2: loop back heading South from (47.50, 19.0402) to (47.49, 19.0402)
    const line: LatLon[] = [
      { lat: 47.49, lon: 19.04 },
      { lat: 47.5, lon: 19.04 },
      { lat: 47.49, lon: 19.0402 },
    ];
    // Vehicle is slightly closer to the northbound segment, but vehicle telemetry says heading South (180 deg)
    const veh = { lat: 47.495, lon: 19.04005 };
    const snappedNorth = snapToPolyline(veh, line, 0); // prefers North
    const snappedSouth = snapToPolyline(veh, line, 180); // prefers South

    expect(snappedNorth?.point.lon).toBeCloseTo(19.04, 4);
    expect(snappedSouth?.point.lon).toBeCloseTo(19.0401, 4);
  });
});

describe('placeVehiclesOnPolyline with telemetry bearing', () => {
  it('aligns with polyline if telemetry bearing agrees within 45 degrees', () => {
    const line: LatLon[] = [
      { lat: 47.49, lon: 19.04 },
      { lat: 47.5, lon: 19.04 }, // North (0 deg)
    ];
    // Telemetry says 5 deg (close to North)
    const [placed] = placeVehiclesOnPolyline([{ id: 'v1', lat: 47.495, lon: 19.04, bearing: 5 }], line);
    expect(placed.bearing).toBeCloseTo(0, 0); // snapped to road
  });

  it('preserves telemetry bearing if polyline heading is opposite (e.g. 180 deg wrong)', () => {
    const line: LatLon[] = [
      { lat: 47.49, lon: 19.04 },
      { lat: 47.5, lon: 19.04 }, // North (0 deg)
    ];
    // Telemetry says South (180 deg), while polyline is North
    const [placed] = placeVehiclesOnPolyline([{ id: 'v1', lat: 47.495, lon: 19.04, bearing: 180 }], line);
    expect(placed.bearing).toBe(180); // telemetry wins
  });
});

