import { describe, it, expect } from 'vitest';
import { timelineFromTimes } from './trip-timeline';
import type { TripLeg } from './types';

const T0 = new Date('2026-09-10T10:00:00+02:00').getTime();
const MIN = 60_000;

const leg = (partial: Partial<TripLeg>): TripLeg => ({
  routes: [],
  routeType: 'TRANSIT',
  routeLongName: '',
  departureStopId: '',
  departureStopName: '',
  destinationStopId: '',
  destinationStopName: '',
  headsign: '',
  waitMinutes: 0,
  ...partial,
});

describe('timelineFromTimes', () => {
  it('builds a timeline from persisted leg times', () => {
    const legs = [
      leg({ departureMs: T0 + 6 * MIN, arrivalMs: T0 + 18 * MIN, mode: 'TRANSIT' }),
      leg({ departureMs: T0 + 18 * MIN, arrivalMs: T0 + 22 * MIN, mode: 'WALK', distanceMeters: 260, durationMinutes: 4 }),
      leg({ departureMs: T0 + 22 * MIN, arrivalMs: T0 + 30 * MIN, mode: 'TRANSIT' }),
    ];
    const timeline = timelineFromTimes(legs);
    expect(timeline.legs).toEqual(legs);
    expect(timeline.leaveAt).toBe(T0 + 6 * MIN);
    expect(timeline.arriveAt).toBe(T0 + 30 * MIN);
    expect(timeline.totalMinutes).toBe(24);
  });

  it('uses the earliest departure and latest arrival regardless of leg order', () => {
    const legs = [
      leg({ departureMs: T0 + 22 * MIN, arrivalMs: T0 + 30 * MIN, mode: 'TRANSIT' }),
      leg({ departureMs: T0 + 6 * MIN, arrivalMs: T0 + 18 * MIN, mode: 'TRANSIT' }),
    ];
    const timeline = timelineFromTimes(legs);
    expect(timeline.leaveAt).toBe(T0 + 6 * MIN);
    expect(timeline.arriveAt).toBe(T0 + 30 * MIN);
    expect(timeline.totalMinutes).toBe(24);
  });

  it('returns null when any leg lacks times (legacy manual trips)', () => {
    const legs = [leg({ departureMs: T0 + 6 * MIN, arrivalMs: T0 + 18 * MIN }), leg({})];
    expect(timelineFromTimes(legs).legs).toBeNull();
  });

  it('returns null for empty legs', () => {
    expect(timelineFromTimes([]).legs).toBeNull();
  });
});