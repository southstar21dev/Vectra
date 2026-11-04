import { useCallback, useEffect, useRef, useState } from 'react';
import type { WeatherCoords } from './weather-api';
import {
  checkGeoPermission,
  getGeoPosition,
  type GeoPosition,
} from '@/lib/location';
import { defaultStorage, type StorageLike } from '@/lib/storage';
import {
  isSnapshotFresh,
  readWeatherSnapshot,
  roundCoords,
  updateWeatherSnapshotCity,
} from './weather-snapshot';
import { timeoutSignal } from '@/lib/timeout-signal';

export type { StorageLike } from '@/lib/storage';

/** Device-level weather location preference. 'auto' = use the device location,
 *  'budapest' = always Budapest. Fresh installs default to 'budapest' (the
 *  toggle is off until the user explicitly grants access). Stored per-device
 *  in localStorage because the underlying permission is per-device too. */
export type WeatherLocationMode = 'auto' | 'budapest';

export const LOCATION_MODE_KEY = 'vectra.weather.locationMode';

/** Broadcast when a device location grant happens outside the settings toggle
 *  (e.g. the dashboard map's permission prompt), so every live
 *  useWeatherLocation instance picks it up without a reload. */
export const DEVICE_LOCATION_GRANTED_EVENT = 'vectra:device-location-granted';

/** Minimal storage surface so unit tests can inject a stub (vitest runs in the
 *  node environment — there is no window.localStorage). */
export function getStoredLocationMode(storage: StorageLike | null = defaultStorage()): WeatherLocationMode {
  try {
    return storage?.getItem(LOCATION_MODE_KEY) === 'auto' ? 'auto' : 'budapest';
  } catch {
    return 'budapest';
  }
}

export function setStoredLocationMode(
  mode: WeatherLocationMode,
  storage: StorageLike | null = defaultStorage()
): void {
  try {
    storage?.setItem(LOCATION_MODE_KEY, mode);
  } catch {
    /* storage failures degrade to the 'budapest' default — nothing to surface */
  }
}

/** Records a granted device location as the weather location setting: persists
 *  mode 'auto' and hands the fresh fix to every mounted useWeatherLocation, so
 *  the Settings toggle and the weather card turn on without a reload. */
export function adoptDeviceLocationGrant(
  position: GeoPosition,
  storage: StorageLike | null = defaultStorage()
): void {
  setStoredLocationMode('auto', storage);
  if (typeof window === 'undefined') return;
  window.dispatchEvent(
    new CustomEvent<GeoPosition>(DEVICE_LOCATION_GRANTED_EVENT, { detail: position })
  );
}

/** BigDataCloud reverse-geocode → display name. Resolution order: city →
 *  locality → principalSubdivision → null (card then renders "MY LOCATION"). */
export function parseGeoCity(json: unknown): string | null {
  if (!json || typeof json !== 'object') return null;
  const o = json as Record<string, unknown>;
  for (const key of ['city', 'locality', 'principalSubdivision']) {
    const value = o[key];
    if (typeof value === 'string' && value.trim()) return value.trim();
  }
  return null;
}

const GEOCODE_URL = 'https://api.bigdatacloud.net/data/reverse-geocode-client';

/** City name for coordinates; null on any failure or when no name exists. */
export async function fetchCityName(lat: number, lon: number): Promise<string | null> {
  try {
    const params = new URLSearchParams({
      latitude: String(lat),
      longitude: String(lon),
      localityLanguage: 'en',
    });
    const res = await fetch(`${GEOCODE_URL}?${params.toString()}`, {
      signal: timeoutSignal(8_000),
    });
    if (!res.ok) return null;
    return parseGeoCity(await res.json());
  } catch {
    return null;
  }
}

/** Module-level cache of successful geocodes, so focus refetches never re-call
 *  the geocoder for the same coordinates. Keyed by "lat,lon". Failures are NOT
 *  stored: a null from the geocoder means "unknown yet", not "no such city", so
 *  a later read retries instead of pinning the card to "My location". */
const cityCache = new Map<string, string>();

/** Synchronous peek at the geocode cache — the last known name for the
 *  coordinates, or null when it has not resolved (or failed) yet. Lets the
 *  weather snapshot pick up a just-resolved city even when it races the fetch. */
export function getCachedCityName(lat: number, lon: number): string | null {
  return cityCache.get(`${lat},${lon}`) ?? null;
}

export function cachedCityName(lat: number, lon: number): Promise<string | null> {
  const key = `${lat},${lon}`;
  const hit = cityCache.get(key);
  if (hit !== undefined) return Promise.resolve(hit);
  return fetchCityName(lat, lon).then((name) => {
    if (name) cityCache.set(key, name);
    return name;
  });
}

/** Initial state for the weather location. When the user opted into device
 *  location and a fresh weather snapshot exists, its (rounded) coordinates and
 *  city are used right away — the card paints instantly instead of doing the
 *  Budapest → device-title dance on every app open. */
export function resolveInitialWeatherLocation(
  storage: StorageLike | null = defaultStorage(),
  now = Date.now()
): WeatherLocationState {
  const mode = getStoredLocationMode(storage);
  if (mode === 'auto') {
    const snapshot = readWeatherSnapshot(storage);
    if (snapshot && isSnapshotFresh(snapshot, now)) {
      const coords = roundCoords(snapshot.coords);
      // Only a real name is worth seeding; a null snapshot city must stay a
      // cache miss so the card can retry the geocoder instead of showing
      // "My location" for the whole session.
      if (snapshot.city) cityCache.set(`${coords.latitude},${coords.longitude}`, snapshot.city);
      return { mode, coords, city: snapshot.city, status: 'located' };
    }
  }
  return { mode, coords: null, city: null, status: 'idle' };
}

export type WeatherLocationStatus =
  | 'idle' // initial, before the first permission check
  | 'locating' // position read in flight
  | 'located' // using device coordinates
  | 'budapest' // mode is off — weather is Budapest-fixed
  | 'fallback' // auto mode, not granted — Budapest fallback in effect
  | 'denied' // auto mode, permission explicitly denied
  | 'unsupported'; // device has no geolocation API

export interface WeatherLocationState {
  mode: WeatherLocationMode;
  /** Device coordinates, or null when the caller should fall back to
   *  BUDAPEST_COORDS. */
  coords: WeatherCoords | null;
  /** Reverse-geocoded city for the device coordinates (null while loading or
   *  when the name is unknown → card shows "MY LOCATION"). */
  city: string | null;
  status: WeatherLocationStatus;
}

/** State after a device fix lands. A fix that resolves after the user
 *  switched to Budapest mode (the read was already in flight) is dropped. */
export function applyPositionState(prev: WeatherLocationState, coords: WeatherCoords): WeatherLocationState {
  if (prev.mode !== 'auto') return prev;
  return { ...prev, coords, status: 'located' };
}

/** State after a position read fails. Permission denied (1) clears the device
 *  location; a transient failure — position unavailable (2) or timeout (3) —
 *  keeps the last good coordinates instead of snapping the card to Budapest.
 *  Ignored once the mode is no longer 'auto'. */
export function applyErrorState(prev: WeatherLocationState, code: number | undefined): WeatherLocationState {
  if (prev.mode !== 'auto') return prev;
  if ((code === 2 || code === 3) && prev.coords) {
    return { ...prev, status: 'located' };
  }
  return { ...prev, coords: null, city: null, status: code === 1 ? 'denied' : 'fallback' };
}

export function useWeatherLocation(): {
  state: WeatherLocationState;
  setMode: (mode: WeatherLocationMode) => void;
} {
  const [state, setState] = useState<WeatherLocationState>(() =>
    resolveInitialWeatherLocation()
  );
  const mountedRef = useRef(true);
  const locatingRef = useRef(false);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const applyPosition = useCallback((pos: GeoPosition) => {
    // Rounded to ~11 m so reloads reuse the same query key/snapshot.
    const coords: WeatherCoords = roundCoords({
      latitude: pos.latitude,
      longitude: pos.longitude,
    });
    if (!mountedRef.current) return;
    setState((prev) => applyPositionState(prev, coords));
    // Lazy city name; cached per-coords so focus refetches are free.
    cachedCityName(coords.latitude, coords.longitude).then((city) => {
      // A failed geocode (null) is "unknown", not "no name" — keep the city we
      // already have on screen and leave the snapshot untouched so a retry can
      // still resolve it.
      if (city) updateWeatherSnapshotCity(coords, city);
      if (!mountedRef.current) return;
      setState((prev) =>
        prev.coords?.latitude === coords.latitude && prev.coords?.longitude === coords.longitude
          ? city
            ? { ...prev, city }
            : prev
          : prev
      );
    });
  }, []);

  const applyError = useCallback((err: unknown) => {
    if (!mountedRef.current) return;
    const code = (err as GeolocationPositionError | null)?.code;
    setState((prev) => applyErrorState(prev, code));
  }, []);

  const readPosition = useCallback(() => {
    if (locatingRef.current) return;
    locatingRef.current = true;
    getGeoPosition().then(
      (pos) => {
        locatingRef.current = false;
        applyPosition(pos);
      },
      (err) => {
        locatingRef.current = false;
        applyError(err);
      }
    );
  }, [applyPosition, applyError]);

  const probePermission = useCallback(() => {
    const mode = getStoredLocationMode();
    if (mode === 'budapest') return;
    // Passive check — never fires a prompt on load. The settings toggle is the
    // only place that actively requests the permission.
    void checkGeoPermission().then((perm) => {
      if (!mountedRef.current) return;
      if (perm === 'granted') {
        setState((prev) => ({ ...prev, mode, status: 'locating' }));
        readPosition();
      } else if (perm === 'denied') {
        setState({ mode, coords: null, city: null, status: 'denied' });
      } else {
        // 'prompt' / 'unsupported': keep the Budapest fallback until the user
        // flips the toggle.
        setState({ mode, coords: null, city: null, status: 'fallback' });
      }
    });
  }, [readPosition]);

  const refresh = useCallback(() => {
    const mode = getStoredLocationMode();
    if (mode === 'budapest') {
      setState({ mode, coords: null, city: null, status: 'budapest' });
      return;
    }
    probePermission();
  }, [probePermission]);

  // Initial check + re-check when the window regains focus (permission granted
  // in OS settings picks up here without a reload). The initial render already
  // reflects the stored 'budapest' mode, so the mount probe only runs the
  // async auto-mode permission check — no state is set during the effect.
  useEffect(() => {
    probePermission();
    window.addEventListener('focus', refresh);
    return () => window.removeEventListener('focus', refresh);
  }, [probePermission, refresh]);

  // A grant from another feature's prompt (the dashboard map) turns this
  // setting on and hands over the fix — no second GPS read needed.
  useEffect(() => {
    const onGranted = (event: Event) => {
      const position = (event as CustomEvent<GeoPosition>).detail;
      setState((prev) => ({ ...prev, mode: 'auto' }));
      if (position) applyPosition(position);
    };
    window.addEventListener(DEVICE_LOCATION_GRANTED_EVENT, onGranted);
    return () => window.removeEventListener(DEVICE_LOCATION_GRANTED_EVENT, onGranted);
  }, [applyPosition]);

  const setMode = useCallback(
    (mode: WeatherLocationMode) => {
      setStoredLocationMode(mode);
      if (mode === 'budapest') {
        setState({ mode, coords: null, city: null, status: 'budapest' });
        return;
      }
      // 'auto' from the settings toggle is a user gesture — read the position
      // (the browser prompt fires on first read when permission is unset).
      setState((prev) => ({ ...prev, mode, status: 'locating' }));
      const acquire = async () => {
        readPosition();
      };
      void acquire();
    },
    [readPosition]
  );

  return { state, setMode };
}
