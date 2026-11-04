import { describe, expect, it } from 'vitest';
import { chooseTripAddressPosition } from './current-location';

describe('chooseTripAddressPosition', () => {
  it('prefers a fresh GPS position', () => {
    expect(
      chooseTripAddressPosition(
        { latitude: 47.5, longitude: 19.05 },
        { status: 'tracking', lat: 47.6, lon: 19.1, lastUpdatedMs: Date.now() }
      )
    ).toEqual({ latitude: 47.5, longitude: 19.05 });
  });

  it('falls back to a recent shared watcher fix', () => {
    const now = Date.now();
    expect(
      chooseTripAddressPosition(null, {
        status: 'tracking',
        lat: 47.6,
        lon: 19.1,
        lastUpdatedMs: now - 10_000,
      }, now)
    ).toEqual({ latitude: 47.6, longitude: 19.1 });
  });

  it('rejects a stale cached fix', () => {
    expect(() =>
      chooseTripAddressPosition(null, {
        status: 'tracking',
        lat: 47.6,
        lon: 19.1,
        lastUpdatedMs: Date.now() - 10 * 60_000,
      })
    ).toThrow('Could not determine your current location');
  });
});
