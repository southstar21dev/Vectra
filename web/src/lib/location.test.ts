import { describe, expect, it } from 'vitest';
import {
  DEVICE_LOCATION_STORAGE_KEY,
  GEO_ERROR_MESSAGES,
  INITIAL_WATCH_LOCATION_STATE,
  applyPositionError,
  applyPositionSuccess,
  isLocationStale,
  readStoredLocation,
  STORED_LOCATION_MAX_AGE_MS,
  writeStoredLocation,
  type WatchLocationState,
} from './location';
import type { StorageLike } from './storage';

function stubStorage(initial: Record<string, string> = {}): StorageLike {
  const map = new Map(Object.entries(initial));
  return {
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => {
      map.set(key, value);
    },
  };
}

const TRACKING_STATE: WatchLocationState = {
  status: 'tracking',
  lat: 47.5,
  lon: 19.05,
  accuracy: 12,
  heading: null,
  speed: null,
  lastUpdatedMs: 1_000,
  isManualRefreshing: false,
  errorMessage: null,
  errorCode: null,
};

describe('applyPositionSuccess', () => {
  it('moves from idle to tracking and records the fix', () => {
    const next = applyPositionSuccess(
      INITIAL_WATCH_LOCATION_STATE,
      { latitude: 47.5, longitude: 19.05, accuracy: 12, heading: 90, speed: 1.5 },
      1_000
    );

    expect(next.status).toBe('tracking');
    expect(next.lat).toBe(47.5);
    expect(next.lon).toBe(19.05);
    expect(next.accuracy).toBe(12);
    expect(next.heading).toBe(90);
    expect(next.speed).toBe(1.5);
    expect(next.lastUpdatedMs).toBe(1_000);
    expect(next.errorMessage).toBeNull();
    expect(next.errorCode).toBeNull();
  });

  it('refreshes lastUpdatedMs even when the coordinates are unchanged', () => {
    const next = applyPositionSuccess(
      TRACKING_STATE,
      { latitude: 47.5, longitude: 19.05, accuracy: 12 },
      9_000
    );

    expect(next.lastUpdatedMs).toBe(9_000);
    expect(next.lat).toBe(47.5);
  });

  it('clears a previous error and the manual-refresh flag', () => {
    const errored: WatchLocationState = {
      ...TRACKING_STATE,
      isManualRefreshing: true,
      errorCode: 3,
      errorMessage: 'Location timed out',
    };

    const next = applyPositionSuccess(
      errored,
      { latitude: 47.6, longitude: 19.06, accuracy: 10 },
      2_000
    );

    expect(next.errorCode).toBeNull();
    expect(next.errorMessage).toBeNull();
    expect(next.isManualRefreshing).toBe(false);
  });
});

describe('applyPositionError', () => {
  it('reports an error when no fix has been received', () => {
    const locating: WatchLocationState = { ...INITIAL_WATCH_LOCATION_STATE, status: 'locating' };
    const next = applyPositionError(locating, { code: 1, message: 'denied' });

    expect(next.status).toBe('error');
    expect(next.errorCode).toBe(1);
    expect(next.errorMessage).toBe(GEO_ERROR_MESSAGES[1]);
  });

  it('preserves the last fix while tracking through a transient error', () => {
    const next = applyPositionError(
      { ...TRACKING_STATE, isManualRefreshing: true },
      { code: 3, message: 'Timed out' }
    );

    expect(next.status).toBe('tracking');
    expect(next.lat).toBe(47.5);
    expect(next.lon).toBe(19.05);
    expect(next.isManualRefreshing).toBe(false);
    expect(next.errorCode).toBe(3);
  });

  it('stops tracking when permission is denied', () => {
    const next = applyPositionError(TRACKING_STATE, { code: 1, message: 'denied' });
    expect(next.status).toBe('error');
  });
});

describe('isLocationStale', () => {
  it('is stale once the last fix is older than the max age', () => {
    expect(isLocationStale(TRACKING_STATE, 1_000 + 60_000, 30_000)).toBe(true);
  });

  it('is not stale right after a fix', () => {
    expect(isLocationStale(TRACKING_STATE, 1_000 + 5_000, 30_000)).toBe(false);
  });

  it('is never stale before the first fix', () => {
    expect(isLocationStale(INITIAL_WATCH_LOCATION_STATE, 999_999, 30_000)).toBe(false);
    expect(
      isLocationStale({ ...INITIAL_WATCH_LOCATION_STATE, status: 'locating' }, 999_999, 30_000)
    ).toBe(false);
  });
});

describe('location persistence', () => {
  it('round-trips the last fix', () => {
    const storage = stubStorage();
    writeStoredLocation(TRACKING_STATE, storage);

    const restored = readStoredLocation(storage, 2_000);
    expect(restored).not.toBeNull();
    expect(restored?.status).toBe('tracking');
    expect(restored?.lat).toBe(47.5);
    expect(restored?.lon).toBe(19.05);
    expect(restored?.accuracy).toBe(12);
    expect(restored?.lastUpdatedMs).toBe(1_000);
    expect(restored?.errorMessage).toBeNull();
  });

  it('drops a stored fix older than the max age', () => {
    const storage = stubStorage();
    writeStoredLocation(TRACKING_STATE, storage);
    expect(readStoredLocation(storage, 1_000 + STORED_LOCATION_MAX_AGE_MS + 1)).toBeNull();
  });

  it('returns null for garbage or incomplete payloads', () => {
    expect(readStoredLocation(stubStorage({ [DEVICE_LOCATION_STORAGE_KEY]: 'not json' }))).toBeNull();
    expect(readStoredLocation(stubStorage({ [DEVICE_LOCATION_STORAGE_KEY]: '{"lat":47.5}' }))).toBeNull();
    expect(
      readStoredLocation(
        stubStorage({ [DEVICE_LOCATION_STORAGE_KEY]: '{"lat":"x","lon":19,"lastUpdatedMs":1}' })
      )
    ).toBeNull();
    expect(readStoredLocation(stubStorage())).toBeNull();
    expect(readStoredLocation(null)).toBeNull();
  });

  it('does not persist a state without a fix', () => {
    const storage = stubStorage();
    writeStoredLocation(INITIAL_WATCH_LOCATION_STATE, storage);
    writeStoredLocation({ ...INITIAL_WATCH_LOCATION_STATE, status: 'error' }, storage);

    expect(storage.getItem(DEVICE_LOCATION_STORAGE_KEY)).toBeNull();
    expect(readStoredLocation(storage)).toBeNull();
  });
});
