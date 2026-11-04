import { describe, expect, it } from 'vitest';
import {
  approachFromVehicle,
  clipRouteToTrackedStop,
  matchingVehicles,
  resolveTrackedStopIndex,
  routeProgress,
  stripLabels,
  trackedTripIds,
} from './live-position';
import type { Arrival, FutarVehicle } from './types';

const arrival = (tripId: string, headSign: string): Arrival => ({
  arrivalMs: Date.now(),
  headSign,
  isPredicted: true,
  tripId,
});

describe('trackedTripIds', () => {
  it('returns the first arrival tripId per direction, max two', () => {
    const arrivals = [
      arrival('T1', 'Keleti pályaudvar'),
      arrival('T2', 'Keleti pályaudvar'),
      arrival('T3', 'Kelenföld vasútállomás'),
    ];
    expect(trackedTripIds(arrivals)).toEqual(['T1', 'T3']);
  });

  it('returns a single tripId for a direction tracker', () => {
    expect(trackedTripIds([arrival('T1', 'Keleti pályaudvar')])).toEqual(['T1']);
  });

  it('skips arrivals without a tripId and caps at three', () => {
    const arrivals = [
      arrival('', 'Keleti pályaudvar'),
      arrival('T1', 'Keleti pályaudvar'),
      arrival('T2', 'B'),
      arrival('T3', 'C'),
      arrival('T4', 'D'),
    ];
    expect(trackedTripIds(arrivals)).toEqual(['T1', 'T2', 'T3']);
  });
});

describe('matchingVehicles', () => {
  it('keeps only vehicles on tracked trips', () => {
    const vehicles: FutarVehicle[] = [
      { vehicleId: 'V1', tripId: 'T1', routeId: 'R', location: { lat: 1, lon: 1 } },
      { vehicleId: 'V2', tripId: 'T9', routeId: 'R', location: { lat: 1, lon: 1 } },
      { vehicleId: 'V3', location: { lat: 1, lon: 1 }, routeId: 'R' }, // no tripId
    ];
    expect(matchingVehicles(vehicles, ['T1']).map((v) => v.vehicleId)).toEqual(['V1']);
  });
});

describe('routeProgress', () => {
  // A straight north-south line with 4 equally spaced stops.
  const stops = [
    { lat: 47.5, lon: 19.04 },
    { lat: 47.51, lon: 19.04 },
    { lat: 47.52, lon: 19.04 },
    { lat: 47.53, lon: 19.04 },
  ];
  const polyline = stops;

  it('reports no vehicle info without a live vehicle', () => {
    const p = routeProgress(stops, polyline, null, 2);
    expect(p.vehicleIndex).toBe(-1);
    expect(p.stopsLeft).toBeNull();
    expect(p.vehicleFraction).toBeNull();
    expect(p.trackedFraction).toBeCloseTo(2 / 3, 5);
  });

  it('computes the next stop, stops left and fractions for a live vehicle', () => {
    // Halfway between stop 1 and stop 2.
    const vehicle = { location: { lat: 47.515, lon: 19.04 } };
    const p = routeProgress(stops, polyline, vehicle, 3);
    expect(p.vehicleIndex).toBe(2);
    expect(p.stopsLeft).toBe(2);
    expect(p.vehicleFraction).toBeCloseTo(0.5, 1);
    expect(p.trackedFraction).toBe(1);
    expect(p.segmentFraction).toBeCloseTo(0.5, 1);
  });

  it('reports no stops left once the vehicle passed the tracked stop', () => {
    const vehicle = { location: { lat: 47.525, lon: 19.04 } };
    const p = routeProgress(stops, polyline, vehicle, 1);
    expect(p.stopsLeft).toBeNull();
    expect(p.vehicleFraction).toBeCloseTo(0.833, 1);
  });

  it('falls back to index-based fractions without geometry', () => {
    const p = routeProgress(stops, [], null, 3);
    expect(p.trackedFraction).toBe(1);
  });
});

describe('stripLabels', () => {
  const names = ['A', 'B', 'C', 'D'].map((name) => ({ name }));

  it('puts the tracked stop in the middle', () => {
    expect(stripLabels(names, 2)).toEqual({ left: 'A', middle: 'C · tracked', right: 'D' });
  });

  it('lets the tracked stop own the start label when it is the first stop', () => {
    expect(stripLabels(names, 0)).toEqual({ left: 'A · tracked', middle: null, right: 'D' });
  });

  it('lets the tracked stop own the end label when it is the terminus', () => {
    expect(stripLabels(names, 3)).toEqual({ left: 'A', middle: null, right: 'D · tracked' });
  });

  it('handles a single-stop line without duplicating the name', () => {
    expect(stripLabels([{ name: 'A' }], 0)).toEqual({ left: 'A · tracked', middle: null, right: '' });
  });
});

describe('clipRouteToTrackedStop', () => {
  // A straight north-south line, 4 equally spaced vertices.
  const polyline = [
    { lat: 47.5, lon: 19.04 },
    { lat: 47.51, lon: 19.04 },
    { lat: 47.52, lon: 19.04 },
    { lat: 47.53, lon: 19.04 },
  ];
  const stops = polyline.map((p, i) => ({ ...p, name: `S${i}` }));

  it('clips the polyline and stops to the tracked stop', () => {
    const out = clipRouteToTrackedStop(polyline, stops, 2);
    expect(out).not.toBeNull();
    expect(out!.stops.map((s) => s.name)).toEqual(['S0', 'S1', 'S2']);
    // Ends at (or very near) the tracked stop's vertex.
    const end = out!.polyline[out!.polyline.length - 1];
    expect(end.lat).toBeCloseTo(47.52, 4);
    expect(out!.polyline.length).toBeLessThan(polyline.length);
  });

  it('keeps the whole line when the tracked stop is the terminus', () => {
    const out = clipRouteToTrackedStop(polyline, stops, 3);
    expect(out!.stops).toHaveLength(4);
    const end = out!.polyline[out!.polyline.length - 1];
    expect(end.lat).toBeCloseTo(47.53, 4);
  });

  it('returns null when the tracked stop is the first stop (nothing to clip)', () => {
    expect(clipRouteToTrackedStop(polyline, stops, 0)).toBeNull();
  });

  it('returns null without usable geometry or a tracked stop', () => {
    expect(clipRouteToTrackedStop([], stops, 1)).toBeNull();
    expect(clipRouteToTrackedStop(polyline, [], 1)).toBeNull();
    expect(clipRouteToTrackedStop(polyline, stops, 99)).toBeNull();
  });
});

describe('approachFromVehicle', () => {
  const polyline = [
    { lat: 47.5, lon: 19.04 },
    { lat: 47.51, lon: 19.04 },
    { lat: 47.52, lon: 19.04 },
    { lat: 47.53, lon: 19.04 },
  ];
  const stops = polyline.map((p, i) => ({ id: `S${i}`, name: `S${i}`, ...p }));

  it('clips from the vehicle position to the tracked stop', () => {
    const out = approachFromVehicle(polyline, stops, 2, { lat: 47.505, lon: 19.04 });
    expect(out).not.toBeNull();
    expect(out!.stops.map((s) => s.id)).toEqual(['S1', 'S2']);
    expect(out!.polyline[0].lat).toBeCloseTo(47.505, 3);
    expect(out!.polyline[out!.polyline.length - 1].lat).toBeCloseTo(47.52, 4);
  });

  it('starts at the line start when no vehicle is known', () => {
    const out = approachFromVehicle(polyline, stops, 2, null);
    expect(out!.stops.map((s) => s.id)).toEqual(['S0', 'S1', 'S2']);
    expect(out!.polyline[0].lat).toBeCloseTo(47.5, 4);
  });

  it('returns null once the vehicle has passed the tracked stop', () => {
    expect(approachFromVehicle(polyline, stops, 2, { lat: 47.525, lon: 19.04 })).toBeNull();
  });

  it('returns null when the tracked stop is the line start', () => {
    expect(approachFromVehicle(polyline, stops, 0, { lat: 47.5, lon: 19.04 })).toBeNull();
  });

  it('returns null without usable geometry', () => {
    expect(approachFromVehicle([], stops, 1, null)).toBeNull();
    expect(approachFromVehicle(polyline, [], 1, null)).toBeNull();
    expect(approachFromVehicle(polyline, stops, 99, null)).toBeNull();
  });
});

describe('resolveTrackedStopIndex', () => {
  const refs = {
    BKK_F001: { parentStationId: 'BKK_CS001' },
    BKK_F002: { parentStationId: 'BKK_CS002' },
  };

  it('matches the exact platform id', () => {
    expect(resolveTrackedStopIndex(['BKK_F001', 'BKK_F002'], refs, 'BKK_F002')).toBe(1);
  });

  it('falls back to the station (area) id', () => {
    expect(resolveTrackedStopIndex(['BKK_F001', 'BKK_F002'], refs, 'BKK_CS002')).toBe(1);
  });

  it('returns -1 when the stop is not on the list', () => {
    expect(resolveTrackedStopIndex(['BKK_F001', 'BKK_F002'], refs, 'BKK_F999')).toBe(-1);
    expect(resolveTrackedStopIndex([], refs, 'BKK_F001')).toBe(-1);
    expect(resolveTrackedStopIndex(['BKK_F001'], refs, null)).toBe(-1);
  });
});


import { approximateVehiclePosition, stripDirection } from './live-position';

describe('approximateVehiclePosition', () => {
  // Straight west-east line, 3 stops; stop 1 departs t=0, stop 2 t=120, stop 3 t=240 (epoch sec).
  const polyline = [
    { lat: 47.5, lon: 19.0 },
    { lat: 47.5, lon: 19.05 },
    { lat: 47.5, lon: 19.1 },
  ];
  const stopTimes = [
    { departureTime: 0, arrivalTime: 0, shapeDistTraveled: 0 },
    { departureTime: 120, arrivalTime: 120, shapeDistTraveled: 5000 },
    { departureTime: 240, arrivalTime: 240, shapeDistTraveled: 10000 },
  ];

  it('returns null without two timed stops or a polyline', () => {
    expect(approximateVehiclePosition({ stopTimes: [], polyline, nowMs: 60_000 })).toBeNull();
    expect(approximateVehiclePosition({ stopTimes, polyline: [], nowMs: 60_000 })).toBeNull();
  });

  it('parks at the origin before the first departure', () => {
    const p = approximateVehiclePosition({ stopTimes, polyline, nowMs: -10_000 });
    expect(p).toEqual(polyline[0]);
  });

  it('parks at the terminus after the last departure', () => {
    const p = approximateVehiclePosition({ stopTimes, polyline, nowMs: 250_000 });
    expect(p).toEqual(polyline[polyline.length - 1]);
  });

  it('interpolates halfway between stops 1 and 2 at the segment midpoint', () => {
    // t=60 is halfway between stop 1 (t=0) and stop 2 (t=120): dist 2500 of 10000.
    const p = approximateVehiclePosition({ stopTimes, polyline, nowMs: 60_000 });
    expect(p).not.toBeNull();
    expect(p!.lon).toBeCloseTo(19.025, 4);
  });

  it('applies the delay offset (running late → behind schedule)', () => {
    // 60s late: at t=180 the vehicle is where the schedule says t=120.
    const p = approximateVehiclePosition({ stopTimes, polyline, nowMs: 180_000, delayMs: 60_000 });
    expect(p).not.toBeNull();
    expect(p!.lon).toBeCloseTo(19.05, 4);
  });

  it('uses the terminus arrival time when it has no departure time (real feed shape)', () => {
    // The live feed's terminus stopTime carries only an arrivalTime; the origin
    // only a departureTime. The vehicle must move between the second-to-last
    // stop's departure and the terminus arrival — not sit at the terminus early
    // because the terminus was excluded from the schedule.
    const terminusArr = 300; // last stop arrives at t=300
    const trip = [
      { departureTime: 0, arrivalTime: undefined, shapeDistTraveled: 0 }, // origin: dep only
      { departureTime: 120, arrivalTime: 120, shapeDistTraveled: 5000 },
      { departureTime: 240, arrivalTime: 240, shapeDistTraveled: 10000 },
      { departureTime: undefined, arrivalTime: terminusArr, shapeDistTraveled: 12000 }, // terminus: arr only
    ];
    // Halfway between stop 3 (t=240) and the terminus (t=300): dist 11000 of
    // 12000 → fraction 0.9167 of the polyline (past stop 2 at 19.05, shy of the
    // end at 19.1). With the OLD code the terminus was excluded (no departure
    // time), so "last" was stop 3 — the dot parked at the polyline end the
    // moment stop 3 departed at t=240.
    const p = approximateVehiclePosition({ stopTimes: trip, polyline, nowMs: 270_000 });
    expect(p).not.toBeNull();
    expect(p!.lon).toBeGreaterThan(19.05);
    expect(p!.lon).toBeLessThan(19.1);
    expect(p!.lon).toBeCloseTo(19.0917, 3);
    // Exactly at the terminus arrival it reaches the polyline end.
    const end = approximateVehiclePosition({ stopTimes: trip, polyline, nowMs: 300_000 });
    expect(end).toEqual(polyline[polyline.length - 1]);
  });
});

describe('stripDirection', () => {
  it('returns null when the headsign equals the end label', () => {
    expect(stripDirection('Széll Kálmán tér', 'Széll Kálmán tér')).toBeNull();
    expect(stripDirection(' széll kálmán tér ', 'Széll Kálmán tér')).toBeNull();
  });
  it('returns the headsign when it differs (short turns)', () => {
    expect(stripDirection('Újpalota, Szentmihályi út', 'Blaha Lujza tér M')).toBe('Újpalota, Szentmihályi út');
  });
  it('returns null for an empty headsign', () => {
    expect(stripDirection(null, 'X')).toBeNull();
    expect(stripDirection('', 'X')).toBeNull();
  });
});
