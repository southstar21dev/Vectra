import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  getStoredLocationMode,
  setStoredLocationMode,
  adoptDeviceLocationGrant,
  parseGeoCity,
  fetchCityName,
  cachedCityName,
  getCachedCityName,
  resolveInitialWeatherLocation,
  applyPositionState,
  applyErrorState,
  type WeatherLocationState,
  LOCATION_MODE_KEY,
  DEVICE_LOCATION_GRANTED_EVENT,
  type StorageLike,
} from './weather-location';
import { writeWeatherSnapshot, type WeatherSnapshot } from './weather-snapshot';
import type { WeatherData } from './weather-api';

function stubStorage(initial: Record<string, string> = {}): StorageLike {
  const map = new Map(Object.entries(initial));
  return {
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => {
      map.set(key, value);
    },
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('getStoredLocationMode', () => {
  it('defaults to budapest (off) when nothing is stored', () => {
    expect(getStoredLocationMode(stubStorage())).toBe('budapest');
  });

  it('returns auto when the user opted in', () => {
    expect(getStoredLocationMode(stubStorage({ [LOCATION_MODE_KEY]: 'auto' }))).toBe('auto');
  });

  it('returns budapest when stored', () => {
    expect(getStoredLocationMode(stubStorage({ [LOCATION_MODE_KEY]: 'budapest' }))).toBe('budapest');
  });

  it('falls back to budapest for unknown values or missing storage', () => {
    expect(getStoredLocationMode(stubStorage({ [LOCATION_MODE_KEY]: 'garbage' }))).toBe('budapest');
    expect(getStoredLocationMode(null)).toBe('budapest');
  });
});

describe('setStoredLocationMode', () => {
  it('persists the mode', () => {
    const s = stubStorage();
    setStoredLocationMode('budapest', s);
    expect(s.getItem(LOCATION_MODE_KEY)).toBe('budapest');
    setStoredLocationMode('auto', s);
    expect(s.getItem(LOCATION_MODE_KEY)).toBe('auto');
  });

  it('does not throw when storage is null', () => {
    expect(() => setStoredLocationMode('auto', null)).not.toThrow();
  });
});

describe('adoptDeviceLocationGrant', () => {
  it('persists auto mode', () => {
    const s = stubStorage();
    adoptDeviceLocationGrant({ latitude: 47.5, longitude: 19.0 }, s);
    expect(s.getItem(LOCATION_MODE_KEY)).toBe('auto');
  });

  it('broadcasts the granted fix to live consumers', () => {
    const dispatchEvent = vi.fn();
    vi.stubGlobal('window', { dispatchEvent });

    adoptDeviceLocationGrant({ latitude: 47.5, longitude: 19.0 }, stubStorage());

    expect(dispatchEvent).toHaveBeenCalledTimes(1);
    const event = dispatchEvent.mock.calls[0][0] as CustomEvent<{ latitude: number; longitude: number }>;
    expect(event.type).toBe(DEVICE_LOCATION_GRANTED_EVENT);
    expect(event.detail).toEqual({ latitude: 47.5, longitude: 19.0 });
  });

  it('does not throw when there is no window', () => {
    expect(() => adoptDeviceLocationGrant({ latitude: 47.5, longitude: 19.0 }, stubStorage())).not.toThrow();
  });
});

const WEATHER_DATA: WeatherData = {
  temperature: 21,
  feelsLike: 20,
  windSpeed: 12,
  weatherCode: 3,
  rainProbability: 25,
  high: 24,
  low: 14,
};

function snapshot(overrides: Partial<WeatherSnapshot> = {}): WeatherSnapshot {
  return {
    coords: { latitude: 47.497912, longitude: 19.040256 },
    city: 'Budapest',
    data: WEATHER_DATA,
    fetchedAt: 1_000,
    ...overrides,
  };
}

describe('resolveInitialWeatherLocation', () => {
  it('seeds rounded device coords and city from a fresh auto-mode snapshot', () => {
    const s = stubStorage({ [LOCATION_MODE_KEY]: 'auto' });
    writeWeatherSnapshot(snapshot(), s);

    expect(resolveInitialWeatherLocation(s, 1_000 + 60_000)).toEqual({
      mode: 'auto',
      coords: { latitude: 47.4979, longitude: 19.0403 },
      city: 'Budapest',
      status: 'located',
    });
  });

  it('ignores a stale snapshot', () => {
    const s = stubStorage({ [LOCATION_MODE_KEY]: 'auto' });
    writeWeatherSnapshot(snapshot(), s);

    expect(resolveInitialWeatherLocation(s, 1_000 + 25 * 60 * 60_000)).toEqual({
      mode: 'auto',
      coords: null,
      city: null,
      status: 'idle',
    });
  });

  it('ignores the snapshot when the user chose Budapest', () => {
    const s = stubStorage({ [LOCATION_MODE_KEY]: 'budapest' });
    writeWeatherSnapshot(snapshot(), s);

    expect(resolveInitialWeatherLocation(s, 2_000)).toEqual({
      mode: 'budapest',
      coords: null,
      city: null,
      status: 'idle',
    });
  });

  it('returns idle when nothing is cached', () => {
    expect(resolveInitialWeatherLocation(stubStorage(), 2_000)).toEqual({
      mode: 'budapest',
      coords: null,
      city: null,
      status: 'idle',
    });
  });
});

describe('parseGeoCity', () => {
  it('prefers city over locality and subdivision', () => {
    const json = { city: 'Budapest', locality: 'Buda Castle Quarter', principalSubdivision: 'Budapest' };
    expect(parseGeoCity(json)).toBe('Budapest');
  });

  it('falls back to locality then subdivision', () => {
    expect(parseGeoCity({ locality: 'Budaörs', principalSubdivision: 'Pest' })).toBe('Budaörs');
    expect(parseGeoCity({ principalSubdivision: 'Pest' })).toBe('Pest');
  });

  it('returns null for garbage, empty names and missing fields', () => {
    expect(parseGeoCity(null)).toBeNull();
    expect(parseGeoCity('nope')).toBeNull();
    expect(parseGeoCity({ city: '   ' })).toBeNull();
    expect(parseGeoCity({})).toBeNull();
  });
});

describe('fetchCityName', () => {
  it('returns null on HTTP error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }));
    expect(await fetchCityName(47.4, 19.0)).toBeNull();
  });

  it('parses a real response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ city: 'Budapest' }) }));
    expect(await fetchCityName(47.4, 19.0)).toBe('Budapest');
  });
});

describe('cachedCityName', () => {
  it('does not memoize a failed lookup, so a later retry can succeed', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: false })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ city: 'Retryville' }) });
    vi.stubGlobal('fetch', fetchMock);

    // Unique coordinates so the module-level cache cannot leak from other tests.
    expect(await cachedCityName(11.1111, 22.2222)).toBeNull();
    expect(await cachedCityName(11.1111, 22.2222)).toBe('Retryville');
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(getCachedCityName(11.1111, 22.2222)).toBe('Retryville');
  });
});

describe('resolveInitialWeatherLocation cache seeding', () => {
  const NULL_CITY_COORDS = { latitude: 2.1111, longitude: 3.2222 };

  it('does not cache a null snapshot city as a permanent answer', async () => {
    const s = stubStorage({ [LOCATION_MODE_KEY]: 'auto' });
    writeWeatherSnapshot(
      { coords: NULL_CITY_COORDS, city: null, data: WEATHER_DATA, fetchedAt: 1_000 },
      s
    );

    const resolved = resolveInitialWeatherLocation(s, 1_000);
    expect(resolved.city).toBeNull();
    expect(resolved.coords).toEqual(NULL_CITY_COORDS);
    expect(getCachedCityName(NULL_CITY_COORDS.latitude, NULL_CITY_COORDS.longitude)).toBeNull();

    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ city: 'Seeded' }) });
    vi.stubGlobal('fetch', fetchMock);
    expect(
      await cachedCityName(resolved.coords!.latitude, resolved.coords!.longitude)
    ).toBe('Seeded');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe('applyPositionState / applyErrorState', () => {
  const located: WeatherLocationState = {
    mode: 'auto',
    coords: { latitude: 47.5, longitude: 19.05 },
    city: 'Budapest',
    status: 'located',
  };
  const coords = { latitude: 47.6, longitude: 19.1 };

  it('applies a fix in auto mode', () => {
    expect(applyPositionState({ ...located, status: 'locating' }, coords)).toEqual({ ...located, coords, status: 'located' });
  });

  it('drops a fix that lands after switching to Budapest', () => {
    const budapest: WeatherLocationState = { mode: 'budapest', coords: null, city: null, status: 'budapest' };
    expect(applyPositionState(budapest, coords)).toBe(budapest);
    expect(applyErrorState(budapest, 1)).toBe(budapest);
  });

  it('keeps the last coordinates on a transient failure', () => {
    expect(applyErrorState({ ...located, status: 'locating' }, 2)).toEqual(located);
    expect(applyErrorState({ ...located, status: 'locating' }, 3)).toEqual(located);
  });

  it('falls back when a transient failure has nothing to keep', () => {
    expect(applyErrorState({ mode: 'auto', coords: null, city: null, status: 'locating' }, 3).status).toBe('fallback');
  });

  it('clears the location when permission is denied', () => {
    expect(applyErrorState(located, 1)).toEqual({ mode: 'auto', coords: null, city: null, status: 'denied' });
  });
});
