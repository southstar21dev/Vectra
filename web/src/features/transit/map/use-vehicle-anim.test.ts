import { describe, expect, it } from 'vitest';
import {
  VEHICLE_ANIM_MAX_MS,
  VEHICLE_ANIM_MIN_MS,
  easeInOutQuad,
  resolveBearing,
  transitionFor,
} from './use-vehicle-anim';
import { cumulativeHaversineDistances } from '../route-geometry';

describe('transitionFor', () => {
  it('snaps immediately when there is no previous state', () => {
    expect(transitionFor(undefined, 120, 1000)).toEqual({
      fromDist: 120,
      toDist: 120,
      startMs: 1000,
      durationMs: 0,
      done: true,
    });
  });

  it('snaps when the distance change is under 1 meter', () => {
    expect(transitionFor({ currentDist: 100 }, 100.4, 2000)).toMatchObject({
      fromDist: 100.4,
      toDist: 100.4,
      done: true,
      durationMs: 0,
    });
  });

  it('animates at 10 m/s and clamps to 600ms..10s', () => {
    expect(transitionFor({ currentDist: 0 }, 5, 0).durationMs).toBe(VEHICLE_ANIM_MIN_MS);
    expect(transitionFor({ currentDist: 0 }, 200_000, 0).durationMs).toBe(VEHICLE_ANIM_MAX_MS);
    expect(transitionFor({ currentDist: 0 }, 8000, 0).durationMs).toBe(800);
  });
});

describe('easeInOutQuad', () => {
  it('eases symmetrically through the midpoint', () => {
    expect(easeInOutQuad(0)).toBe(0);
    expect(easeInOutQuad(0.5)).toBe(0.5);
    expect(easeInOutQuad(1)).toBe(1);
    expect(easeInOutQuad(0.25)).toBeCloseTo(0.125, 6);
  });
});

describe('resolveBearing', () => {
  const lineNorth = [
    { lat: 47.49, lon: 19.04 },
    { lat: 47.50, lon: 19.04 },
  ];
  const distsNorth = cumulativeHaversineDistances(lineNorth);

  it('uses polyline bearing when no telemetry bearing is supplied', () => {
    const bearing = resolveBearing(lineNorth, distsNorth, distsNorth[1] / 2);
    expect(bearing).toBeCloseTo(0, 0); // North
  });

  it('snaps to polyline bearing when telemetry bearing is close (within 45 deg)', () => {
    const bearing = resolveBearing(lineNorth, distsNorth, distsNorth[1] / 2, 8);
    expect(bearing).toBeCloseTo(0, 0); // aligned with road
  });

  it('preserves telemetry bearing when polyline disagrees by more than 45 deg', () => {
    // Road runs North (0 deg), but vehicle telemetry says South (180 deg)
    const bearing = resolveBearing(lineNorth, distsNorth, distsNorth[1] / 2, 180);
    expect(bearing).toBe(180);
  });

  it('falls back to telemetry bearing when polyline is too short', () => {
    expect(resolveBearing([], [], 0, 120)).toBe(120);
    expect(resolveBearing([{ lat: 47.49, lon: 19.04 }], [0], 0, 90)).toBe(90);
  });
});
