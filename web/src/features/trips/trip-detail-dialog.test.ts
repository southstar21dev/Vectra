import { describe, it, expect } from 'vitest';
import { tripHasDepartures } from './trip-utils';

describe('tripHasDepartures', () => {
  it('returns true for walking-only trips', () => {
    expect(
      tripHasDepartures({
        hasTransit: false,
        optionsCount: 0,
        firstDepartureCountdown: null,
        allTransitLegsHaveDepartures: false,
      })
    ).toBe(true);
  });

  it('returns true when chained departure options exist', () => {
    expect(
      tripHasDepartures({
        hasTransit: true,
        optionsCount: 3,
        firstDepartureCountdown: '4 MIN',
        allTransitLegsHaveDepartures: true,
      })
    ).toBe(true);
  });

  it('returns false when no departures are available (off-service at night)', () => {
    expect(
      tripHasDepartures({
        hasTransit: true,
        optionsCount: 0,
        firstDepartureCountdown: null,
        allTransitLegsHaveDepartures: false,
      })
    ).toBe(false);
  });

  it('returns false when countdown is placeholder --', () => {
    expect(
      tripHasDepartures({
        hasTransit: true,
        optionsCount: 0,
        firstDepartureCountdown: '--',
        allTransitLegsHaveDepartures: true,
      })
    ).toBe(false);
  });

  it('returns false when one transit leg has a departure but another transit leg is off-service', () => {
    expect(
      tripHasDepartures({
        hasTransit: true,
        optionsCount: 0,
        firstDepartureCountdown: '3 MIN',
        allTransitLegsHaveDepartures: false,
      })
    ).toBe(false);
  });

  it('returns true when a single transit leg has upcoming departure even without chained options', () => {
    expect(
      tripHasDepartures({
        hasTransit: true,
        optionsCount: 0,
        firstDepartureCountdown: 'NOW',
        allTransitLegsHaveDepartures: true,
      })
    ).toBe(true);
  });
});
