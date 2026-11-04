import { describe, expect, it } from 'vitest';
import {
  WEATHER_SNAPSHOT_KEY,
  coordsMatch,
  isSnapshotFresh,
  readWeatherSnapshot,
  roundCoords,
  updateWeatherSnapshotCity,
  writeWeatherSnapshot,
  type WeatherSnapshot,
} from './weather-snapshot';
import type { WeatherData } from './weather-api';
import type { StorageLike } from '@/lib/storage';

function stubStorage(initial: Record<string, string> = {}): StorageLike {
  const map = new Map(Object.entries(initial));
  return {
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => {
      map.set(key, value);
    },
  };
}

const WEATHER: WeatherData = {
  temperature: 21,
  feelsLike: 20,
  windSpeed: 12,
  weatherCode: 3,
  rainProbability: 25,
  high: 24,
  low: 14,
};

const SNAPSHOT: WeatherSnapshot = {
  coords: { latitude: 47.4979, longitude: 19.0402 },
  city: 'Budapest',
  data: WEATHER,
  fetchedAt: 1_000,
};

describe('roundCoords', () => {
  it('rounds to four decimals by default', () => {
    expect(roundCoords({ latitude: 47.49791234, longitude: 19.04025678 })).toEqual({
      latitude: 47.4979,
      longitude: 19.0403,
    });
  });
});

describe('coordsMatch', () => {
  it('treats coordinates inside the rounding precision as the same place', () => {
    expect(
      coordsMatch(
        { latitude: 47.4979, longitude: 19.0402 },
        { latitude: 47.49791, longitude: 19.04024 }
      )
    ).toBe(true);
  });

  it('rejects coordinates more than the rounding precision apart', () => {
    expect(
      coordsMatch(
        { latitude: 47.4979, longitude: 19.0402 },
        { latitude: 47.51, longitude: 19.0402 }
      )
    ).toBe(false);
  });
});

describe('weather snapshot persistence', () => {
  it('round-trips a snapshot', () => {
    const storage = stubStorage();
    writeWeatherSnapshot(SNAPSHOT, storage);

    expect(readWeatherSnapshot(storage)).toEqual(SNAPSHOT);
  });

  it('returns null for garbage, missing fields or bad data', () => {
    expect(readWeatherSnapshot(stubStorage({ [WEATHER_SNAPSHOT_KEY]: 'nope' }))).toBeNull();
    expect(readWeatherSnapshot(stubStorage({ [WEATHER_SNAPSHOT_KEY]: '{"coords":{}}' }))).toBeNull();
    expect(
      readWeatherSnapshot(
        stubStorage({
          [WEATHER_SNAPSHOT_KEY]: JSON.stringify({ ...SNAPSHOT, data: { temperature: 'hot' } }),
        })
      )
    ).toBeNull();
    expect(readWeatherSnapshot(stubStorage())).toBeNull();
    expect(readWeatherSnapshot(null)).toBeNull();
  });

  it('updates the city only for matching coordinates', () => {
    const storage = stubStorage();
    writeWeatherSnapshot(SNAPSHOT, storage);

    updateWeatherSnapshotCity({ latitude: 47.49791, longitude: 19.04024 }, 'Buda', storage);
    expect(readWeatherSnapshot(storage)?.city).toBe('Buda');

    updateWeatherSnapshotCity({ latitude: 48, longitude: 20 }, 'Elsewhere', storage);
    expect(readWeatherSnapshot(storage)?.city).toBe('Buda');
  });

  it('never replaces a known city with a null (failed) result', () => {
    const storage = stubStorage();
    writeWeatherSnapshot(SNAPSHOT, storage);

    updateWeatherSnapshotCity(SNAPSHOT.coords, null, storage);
    expect(readWeatherSnapshot(storage)?.city).toBe('Budapest');
  });
});

describe('isSnapshotFresh', () => {
  it('accepts a snapshot within the max age', () => {
    expect(isSnapshotFresh(SNAPSHOT, 1_000 + 60_000, 24 * 60 * 60_000)).toBe(true);
  });

  it('rejects a snapshot past the max age', () => {
    expect(isSnapshotFresh(SNAPSHOT, 1_000 + 25 * 60 * 60_000, 24 * 60 * 60_000)).toBe(false);
  });
});
