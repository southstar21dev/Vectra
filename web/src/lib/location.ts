import { useCallback, useSyncExternalStore } from 'react';
import { getErrorMessage } from '@/lib/error-utils';
import { defaultStorage, type StorageLike } from '@/lib/storage';

// ---- permissions & one-shot position ---------------------------------------

export type GeoPermissionState = 'granted' | 'prompt' | 'denied' | 'unsupported';

export interface GeoPosition {
  latitude: number;
  longitude: number;
}

/** Passive permission check — never fires a prompt. Uses the Permissions API. */
export async function checkGeoPermission(): Promise<GeoPermissionState> {
  try {
    if (typeof navigator === 'undefined' || typeof navigator.permissions?.query !== 'function') {
      return 'unsupported';
    }
    const status = await navigator.permissions.query({ name: 'geolocation' as PermissionName });
    return status.state;
  } catch {
    return 'unsupported';
  }
}

/** Active permission request — the browser prompt is fired by getCurrentPosition
 *  instead, so this only reflects the query result. */
export async function requestGeoPermission(): Promise<GeoPermissionState> {
  return checkGeoPermission();
}

/** One-shot position read. Uses the standard API (which fires the browser
 *  permission prompt when it is still unset). */
export function getGeoPosition(): Promise<GeoPosition> {
  return new Promise<GeoPosition>((resolve, reject) => {
    if (typeof navigator === 'undefined' || !('geolocation' in navigator)) {
      reject(new Error('Geolocation is not supported on this device'));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => resolve({ latitude: pos.coords.latitude, longitude: pos.coords.longitude }),
      reject,
      { enableHighAccuracy: false, timeout: 10_000, maximumAge: 30_000 }
    );
  });
}

// ---- continuous device-location watcher ------------------------------------

/* --------------------------------------------------------------------------
 * Shared continuous device geolocation for every feature that needs a live
 * position (dashboard nearby map, transit nearby radar).
 *
 * One watcher serves all consumers. State lives at module level, is seeded
 * from the last persisted fix on app open (no Budapest flash, instant first
 * paint) and is kept alive by a watchdog: stale/missed fixes restart the
 * watch, and coming back from the background always re-probes. The watch
 * starts with the first consumer mount and stops with the last one.
 * ------------------------------------------------------------------------ */

export interface WatchLocationState {
  status: 'idle' | 'locating' | 'tracking' | 'error';
  lat: number;
  lon: number;
  accuracy: number;
  heading: number | null;
  speed: number | null;
  lastUpdatedMs: number;
  isManualRefreshing: boolean;
  errorMessage: string | null;
  errorCode: number | null;
}

export const INITIAL_WATCH_LOCATION_STATE: WatchLocationState = {
  status: 'idle',
  lat: 47.4979, // Budapest center fallback while no fix exists
  lon: 19.0402,
  accuracy: 0,
  heading: null,
  speed: null,
  lastUpdatedMs: 0,
  isManualRefreshing: false,
  errorMessage: null,
  errorCode: null,
};

export const GEO_ERROR_MESSAGES: Record<number, string> = {
  1: 'Location permission denied — enable location to find nearby places',
  2: 'Location unavailable right now — try again',
  3: 'Location timed out — try again',
};

export const DEVICE_LOCATION_STORAGE_KEY = 'vectra.deviceLocation';

/** A fix older than this is considered lost: the watchdog restarts the watch. */
export const DEVICE_LOCATION_STALE_MS = 30_000;

export interface PositionFix {
  latitude: number;
  longitude: number;
  accuracy: number;
  heading: number | null;
  speed: number | null;
}

/** Pure transition for a successful fix. Always returns a fresh state with an
 *  updated `lastUpdatedMs` so the staleness watchdog sees the watch alive. */
export function applyPositionSuccess(
  current: WatchLocationState,
  coords: {
    latitude: number;
    longitude: number;
    accuracy: number;
    heading?: number | null;
    speed?: number | null;
  },
  now = Date.now()
): WatchLocationState {
  return {
    status: 'tracking',
    lat: coords.latitude,
    lon: coords.longitude,
    accuracy: coords.accuracy,
    heading: coords.heading ?? null,
    speed: coords.speed ?? null,
    lastUpdatedMs: now,
    isManualRefreshing: false,
    errorMessage: null,
    errorCode: null,
  };
}

/** Pure transition for a position error. A consumer that already has a fix
 *  keeps showing it (only the error is recorded); with no fix at all the
 *  state becomes an error so the UI can offer a retry. Permission denied
 *  (code 1) always ends tracking: the watch has stopped, so the old fix
 *  would otherwise be shown as live forever. */
export function applyPositionError(
  current: WatchLocationState,
  error: { code: number; message?: string }
): WatchLocationState {
  const message =
    GEO_ERROR_MESSAGES[error.code] || error.message || 'Could not get your location';
  return {
    ...current,
    status: current.status === 'tracking' && error.code !== 1 ? 'tracking' : 'error',
    isManualRefreshing: false,
    errorCode: error.code,
    errorMessage: message,
  };
}

/** True when the last fix is old enough that the watcher must be restarted. */
export function isLocationStale(
  state: WatchLocationState,
  now = Date.now(),
  maxAgeMs = DEVICE_LOCATION_STALE_MS
): boolean {
  if (state.status !== 'tracking') return false;
  return now - state.lastUpdatedMs > maxAgeMs;
}

export function writeStoredLocation(
  state: WatchLocationState,
  storage: StorageLike | null = defaultStorage()
): void {
  if (state.status !== 'tracking') return;
  try {
    storage?.setItem(
      DEVICE_LOCATION_STORAGE_KEY,
      JSON.stringify({
        lat: state.lat,
        lon: state.lon,
        accuracy: state.accuracy,
        heading: state.heading,
        speed: state.speed,
        lastUpdatedMs: state.lastUpdatedMs,
      })
    );
  } catch {
    /* storage failures only cost the instant-first-paint optimisation */
  }
}

/** Stored fixes older than this are discarded on load. */
export const STORED_LOCATION_MAX_AGE_MS = 30 * 60_000;

export function readStoredLocation(
  storage: StorageLike | null = defaultStorage(),
  now = Date.now()
): WatchLocationState | null {
  try {
    const raw = storage?.getItem(DEVICE_LOCATION_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Record<string, unknown> | null;
    if (!parsed || typeof parsed !== 'object') return null;
    const { lat, lon, lastUpdatedMs } = parsed;
    if (!Number.isFinite(lat) || !Number.isFinite(lon) || !Number.isFinite(lastUpdatedMs)) {
      return null;
    }
    // A fix from hours or days ago (possibly another city) is not "tracking";
    // only a recent one is worth an instant first paint.
    if (now - (lastUpdatedMs as number) > STORED_LOCATION_MAX_AGE_MS) return null;
    return {
      status: 'tracking',
      lat: lat as number,
      lon: lon as number,
      accuracy: Number.isFinite(parsed.accuracy) ? (parsed.accuracy as number) : 0,
      heading: typeof parsed.heading === 'number' ? parsed.heading : null,
      speed: typeof parsed.speed === 'number' ? parsed.speed : null,
      lastUpdatedMs: lastUpdatedMs as number,
      isManualRefreshing: false,
      errorMessage: null,
      errorCode: null,
    };
  } catch {
    return null;
  }
}

const WATCH_OPTIONS = {
  enableHighAccuracy: false,
  timeout: 10_000,
  maximumAge: 30_000,
} as const;

/** One-shot fix with accuracy — standard API. Rejects on failure (including
 *  permission denied / unsupported). */
function getPositionFix(): Promise<PositionFix> {
  return new Promise<PositionFix>((resolve, reject) => {
    if (typeof navigator === 'undefined' || !('geolocation' in navigator)) {
      reject(new Error('Geolocation is not supported on this device'));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) =>
        resolve({
          latitude: pos.coords.latitude,
          longitude: pos.coords.longitude,
          accuracy: pos.coords.accuracy,
          heading: pos.coords.heading,
          speed: pos.coords.speed,
        }),
      reject,
      WATCH_OPTIONS
    );
  });
}

/** Continuous position updates via navigator.watchPosition. Returns a cleanup
 *  that reliably stops the watch. */
function startPositionWatch(
  onFix: (fix: PositionFix) => void,
  onError: (code: number, message: string) => void
): () => void {

  if (typeof navigator === 'undefined' || !('geolocation' in navigator)) {
    onError(0, 'Geolocation is not supported on this device');
    return () => {};
  }
  const id = navigator.geolocation.watchPosition(
    (pos) =>
      onFix({
        latitude: pos.coords.latitude,
        longitude: pos.coords.longitude,
        accuracy: pos.coords.accuracy,
        heading: pos.coords.heading,
        speed: pos.coords.speed,
      }),
    (err) => onError(err.code, err.message),
    WATCH_OPTIONS
  );
  return () => navigator.geolocation.clearWatch(id);
}

/* ------------------------------ shared state ------------------------------ */

let sharedState: WatchLocationState = INITIAL_WATCH_LOCATION_STATE;
let initialized = false;
let sessionActive = false;
let watchCleanup: (() => void) | null = null;
let watchdogId: ReturnType<typeof setInterval> | null = null;
let watchRefCount = 0;
let permissionDenied = false;
let lastWatchStartedAt = 0;
let lastPersistedAt = 0;
type LocationListener = (state: WatchLocationState) => void;
const listeners = new Set<LocationListener>();

const WATCHDOG_INTERVAL_MS = 10_000;
const RESTART_COOLDOWN_MS = 4_000;
const PERSIST_INTERVAL_MS = 5_000;

/** Consecutive failed restarts — backs off the watchdog when location is
 *  genuinely unavailable instead of restarting every tick. */
let failedRestartCount = 0;

function notifyListeners() {
  for (const listener of listeners) listener(sharedState);
}

function ensureInitialized() {
  if (initialized) return;
  initialized = true;
  sharedState = readStoredLocation() ?? INITIAL_WATCH_LOCATION_STATE;
}

function statesDiffer(a: WatchLocationState, b: WatchLocationState): boolean {
  return (
    a.status !== b.status ||
    a.lat !== b.lat ||
    a.lon !== b.lon ||
    a.accuracy !== b.accuracy ||
    a.heading !== b.heading ||
    a.speed !== b.speed ||
    a.isManualRefreshing !== b.isManualRefreshing ||
    a.errorCode !== b.errorCode ||
    a.errorMessage !== b.errorMessage
  );
}

function persistThrottled(state: WatchLocationState, now = Date.now()) {
  if (state.status !== 'tracking') return;
  if (now - lastPersistedAt < PERSIST_INTERVAL_MS) return;
  lastPersistedAt = now;
  writeStoredLocation(state);
}

function normalizeError(err: unknown): { code: number; message: string } {
  const code =
    typeof (err as { code?: unknown })?.code === 'number'
      ? (err as { code: number }).code
      : 0;
  return { code, message: getErrorMessage(err, 'Could not get your location') };
}

function applyFix(fix: PositionFix) {
  const previous = sharedState;
  const next = applyPositionSuccess(previous, fix);
  sharedState = next;
  failedRestartCount = 0;
  persistThrottled(next);
  if (statesDiffer(previous, next)) notifyListeners();
}

function applyError(error: { code: number; message?: string }) {
  if (error.code === 1) {
    // Denied: stop hammering the OS with restarts. A resume/manual refresh
    // clears this flag so a permission change in OS settings is picked up.
    permissionDenied = true;
    stopWatch();
  }
  const previous = sharedState;
  const next = applyPositionError(previous, error);
  sharedState = next;
  if (statesDiffer(previous, next)) notifyListeners();
}

function stopWatch() {
  watchCleanup?.();
  watchCleanup = null;
}

function startWatch() {
  if (!sessionActive || watchCleanup || permissionDenied) return;
  if (sharedState.status !== 'tracking') {
    sharedState = { ...sharedState, status: 'locating' };
    notifyListeners();
  }
  lastWatchStartedAt = Date.now();
  // Immediate fix to minimize time-to-first-fix, then keep watching.
  getPositionFix().then(applyFix, (err: unknown) => applyError(normalizeError(err)));
  watchCleanup = startPositionWatch(applyFix, (code, message) => applyError({ code, message }));
}

function restartWatchIfStale() {
  if (!sessionActive || permissionDenied) return;
  // A dead watch can also surface as a stuck error state (e.g. the provider
  // errored once and never called back), so both cases trigger a restart.
  const needsRestart = sharedState.status === 'error' || isLocationStale(sharedState);
  if (!needsRestart) return;
  const cooldown = RESTART_COOLDOWN_MS * Math.min(failedRestartCount + 1, 6);
  if (Date.now() - lastWatchStartedAt < cooldown) return;
  failedRestartCount += 1;
  stopWatch();
  startWatch();
}

function onVisibilityChange() {
  if (typeof document === 'undefined' || document.visibilityState !== 'visible') return;
  permissionDenied = false;
  if (!sessionActive) return;
  if (!watchCleanup) {
    startWatch();
  } else {
    restartWatchIfStale();
  }
}

function startSession() {
  if (sessionActive) return;
  sessionActive = true;
  permissionDenied = false;
  if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', onVisibilityChange);
  }
  watchdogId = setInterval(restartWatchIfStale, WATCHDOG_INTERVAL_MS);
  startWatch();
}

function stopSession() {
  sessionActive = false;
  if (watchdogId !== null) {
    clearInterval(watchdogId);
    watchdogId = null;
  }
  if (typeof document !== 'undefined') {
    document.removeEventListener('visibilitychange', onVisibilityChange);
  }
  stopWatch();
}

function refreshLocation() {
  permissionDenied = false;
  sharedState = { ...sharedState, isManualRefreshing: true };
  notifyListeners();
  if (!watchCleanup) startWatch();
  getPositionFix().then(applyFix, (err: unknown) => applyError(normalizeError(err)));
}

/** Subscribe the shared watcher lifecycle to a React consumer. */
function subscribeToDeviceLocation(onStoreChange: () => void): () => void {
  const listener: LocationListener = () => onStoreChange();
  listeners.add(listener);
  watchRefCount += 1;
  if (watchRefCount === 1) startSession();
  return () => {
    listeners.delete(listener);
    watchRefCount -= 1;
    if (watchRefCount <= 0) {
      watchRefCount = 0;
      stopSession();
    }
  };
}

function getDeviceLocationSnapshot(): WatchLocationState {
  return sharedState;
}

/** Shared continuous device geolocation. One watcher across all consumers,
 *  seeded from the last known fix and kept fresh by the watchdog. */
export function useDeviceLocation(): {
  state: WatchLocationState;
  refreshLocation: () => void;
} {
  ensureInitialized();
  const state = useSyncExternalStore(
    subscribeToDeviceLocation,
    getDeviceLocationSnapshot,
    getDeviceLocationSnapshot
  );

  const requestRefresh = useCallback(() => refreshLocation(), []);

  return { state, refreshLocation: requestRefresh };
}
