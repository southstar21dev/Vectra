import { describe, it, expect } from 'vitest';
import { applyDirectionChange, normalizeLeg, type TripDirection, type TripPlan } from './types';

describe('normalizeLeg', () => {
  it('passes through legs that already carry the new timeline fields', () => {
    const leg = {
      routes: [{ routeId: 'BKK_3040', routeShortName: '4', routeColor: '003DA5', routeTextColor: 'FFFFFF' }],
      routeType: 'TRAM',
      routeLongName: 'Széll Kálmán tér M / Újbuda-központ M',
      departureStopId: 'BKK_1',
      departureStopName: 'Deák Ferenc tér M',
      destinationStopId: 'BKK_2',
      destinationStopName: 'Keleti pályaudvar M',
      headsign: 'Újbuda-központ M',
      waitMinutes: 4,
      mode: 'TRANSIT',
      departureMs: 1725900000000,
      arrivalMs: 1725900300000,
      isPredicted: true,
    };
    const normalized = normalizeLeg(leg);
    expect(normalized.mode).toBe('TRANSIT');
    expect(normalized.departureMs).toBe(1725900000000);
    expect(normalized.arrivalMs).toBe(1725900300000);
    expect(normalized.isPredicted).toBe(true);
  });

  it('passes through walking segments', () => {
    const walk = {
      routes: [],
      routeType: 'WALK',
      routeLongName: 'Walk',
      departureStopId: '',
      departureStopName: 'Keleti pályaudvar M',
      destinationStopId: '',
      destinationStopName: 'Baross tér',
      headsign: '',
      waitMinutes: 1,
      mode: 'WALK',
      departureMs: 1725900300000,
      arrivalMs: 1725900360000,
      distanceMeters: 420,
      durationMinutes: 6,
    };
    const normalized = normalizeLeg(walk);
    expect(normalized.mode).toBe('WALK');
    expect(normalized.distanceMeters).toBe(420);
    expect(normalized.durationMinutes).toBe(6);
  });

  it('keeps legacy legs unchanged (no new fields)', () => {
    const legacy = { routeId: 'BKK_123', routeShortName: '4', stopId: 'BKK_1', stopName: 'Deák Ferenc tér M' };
    const normalized = normalizeLeg(legacy);
    expect(normalized.mode).toBeUndefined();
    expect(normalized.departureMs).toBeUndefined();
    expect(normalized.arrivalMs).toBeUndefined();
    expect(normalized.departureStopId).toBe('BKK_1');
    expect(normalized.waitMinutes).toBe(5);
  });
});

describe('applyDirectionChange', () => {
  const base = (id: string, direction: TripDirection | null = null): TripPlan => ({
    id,
    userId: 'u1',
    name: id,
    legs: [],
    sortOrder: 0,
    createdAt: '',
    direction,
  });

  it('sets the direction on the target trip', () => {
    const result = applyDirectionChange([base('a')], 'a', 'to_uni');
    expect(result[0].direction).toBe('to_uni');
  });

  it('clears the same direction on other trips, leaving the other direction alone', () => {
    const trips = [base('a', 'to_uni'), base('b', 'to_home'), base('c')];
    const result = applyDirectionChange(trips, 'c', 'to_uni');
    expect(result.find((t) => t.id === 'a')?.direction).toBeNull();
    expect(result.find((t) => t.id === 'b')?.direction).toBe('to_home');
    expect(result.find((t) => t.id === 'c')?.direction).toBe('to_uni');
  });

  it('clears the target when direction is null', () => {
    const result = applyDirectionChange([base('a', 'to_home')], 'a', null);
    expect(result[0].direction).toBeNull();
  });
});