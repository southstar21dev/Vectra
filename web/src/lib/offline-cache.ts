import { getStoredUserId } from './auth-helpers';

export interface OfflineCacheOptions<T> {
  /** Custom serializer (defaults to JSON.stringify) */
  serialize?: (value: T) => string;
  /** Custom deserializer (defaults to JSON.parse) */
  deserialize?: (raw: string) => T;
}

export interface OfflineCache<T> {
  /** Read from the cache strictly scoped to the provided userId (or isolated anonymous space). */
  read(userId?: string | null): T | null;
  /** Write to the user-scoped cache key. */
  write(value: T, userId?: string | null): void;
  /** Clear cache for the specified user ID (or anonymous). */
  clear(userId?: string | null): void;
  /** Get the user-scoped cache key. */
  getKey(userId?: string | null): string;
}

/** Known offline cache base keys for bulk clearance on logout. */
export const OFFLINE_CACHE_BASE_KEYS = [
  'vectra_schedule_cache_events',
  'vectra_schedule_items',
  'vectra_transit_trackers_cache',
  'vectra_course_tracker_cache',
] as const;

/**
 * User/device-scoped singleton keys that don't use the cache factory but must
 * not survive a logout (they can carry account-specific state or a push token
 * that would deliver the previous user's notifications).
 */
export const USER_SCOPED_STORAGE_KEYS = [
  'vectra_neptun_sync_error',
  'vectra_neptun_sync_timestamps',
  // Location traces: the last weather snapshot (coords + city) and the last
  // device fix must not leak to the next account on a shared device. Literal
  // keys mirror WEATHER_SNAPSHOT_KEY (features/weather/weather-snapshot.ts) and
  // DEVICE_LOCATION_STORAGE_KEY (lib/location.ts).
  'vectra.weather.snapshot',
  'vectra.deviceLocation',
] as const;

/**
 * Factory for localStorage-backed offline caches with strictly user-scoped keys.
 * Completely eliminates un-scoped global key fallbacks to prevent cache bleed
 * across accounts or session injection attacks.
 */
export function createOfflineCache<T>(
  baseKey: string,
  options?: OfflineCacheOptions<T>
): OfflineCache<T> {
  const serialize = options?.serialize ?? JSON.stringify;
  const deserialize = options?.deserialize ?? JSON.parse;

  function getKey(userId?: string | null): string {
    const resolvedUserId = userId !== undefined ? userId : getStoredUserId();
    const scope = resolvedUserId && resolvedUserId.trim() ? resolvedUserId.trim() : 'anonymous';
    return `${baseKey}_${scope}`;
  }

  function read(userId?: string | null): T | null {
    try {
      if (typeof window === 'undefined' && typeof localStorage === 'undefined') return null;
      const storage = typeof window !== 'undefined' ? window.localStorage : localStorage;
      if (!storage) return null;

      const key = getKey(userId);
      const userCached = storage.getItem(key);
      if (userCached) return deserialize(userCached);

      return null;
    } catch {
      return null;
    }
  }

  function write(value: T, userId?: string | null): void {
    try {
      if (typeof window === 'undefined' && typeof localStorage === 'undefined') return;
      const storage = typeof window !== 'undefined' ? window.localStorage : localStorage;
      if (!storage) return;

      const key = getKey(userId);
      const data = serialize(value);
      storage.setItem(key, data);
    } catch (err) {
      console.error(`Failed to save to offline cache (${baseKey}):`, err);
    }
  }

  function clear(userId?: string | null): void {
    try {
      if (typeof window === 'undefined' && typeof localStorage === 'undefined') return;
      const storage = typeof window !== 'undefined' ? window.localStorage : localStorage;
      if (!storage) return;

      storage.removeItem(getKey(userId));
    } catch (err) {
      console.error(`Failed to clear offline cache (${baseKey}):`, err);
    }
  }

  return { read, write, clear, getKey };
}

/**
 * Bases of the keys holding writes only this device has: the schedule-items
 * offline queue and the course tracker's not yet uploaded data, as
 * `<base>_<userId>`. A forced sign-out (expiry, a dead token) keeps them so the
 * same account replays them after signing back in.
 */
export const OFFLINE_QUEUE_BASE_KEYS = ['vectra_schedule_items', 'vectra_course_tracker_cache'] as const;

/** Which account the offline queue kept by a forced sign-out belongs to. */
export const KEPT_QUEUE_OWNER_KEY = 'vectra_kept_queue_owner';

/** This user's offline queue keys. */
export function offlineQueueKeys(userId: string): string[] {
  return OFFLINE_QUEUE_BASE_KEYS.map((base) => `${base}_${userId}`);
}

function defaultStorage(): Storage | undefined {
  return typeof window !== 'undefined' ? window.localStorage : undefined;
}

/**
 * Clear all user-scoped and feature offline caches from storage on logout.
 * With `keepQueueFor` (a forced sign-out), that account's offline queue keys
 * stay and are recorded as kept; otherwise everything goes.
 */
export function clearAllOfflineUserData(storage?: Storage, keepQueueFor?: string | null): void {
  const s = storage ?? defaultStorage();
  if (!s) return;
  const kept = new Set(keepQueueFor ? offlineQueueKeys(keepQueueFor) : []);

  const keysToRemove: string[] = [];
  for (let i = 0; i < s.length; i++) {
    const key = s.key(i);
    if (!key || kept.has(key)) continue;

    // Check if key matches any of our known offline cache prefixes
    const matchesOfflineCache = OFFLINE_CACHE_BASE_KEYS.some((baseKey) => key.startsWith(baseKey));
    if (matchesOfflineCache) {
      keysToRemove.push(key);
    }
  }

  for (const key of keysToRemove) {
    s.removeItem(key);
  }

  for (const key of USER_SCOPED_STORAGE_KEYS) {
    s.removeItem(key);
  }

  if (keepQueueFor) s.setItem(KEPT_QUEUE_OWNER_KEY, keepQueueFor);
  else s.removeItem(KEPT_QUEUE_OWNER_KEY);
}

/**
 * After a sign-in: a queue kept by a forced sign-out is replayed when its own
 * account signs back in; any other account signing in deletes it instead.
 */
export function settleKeptOfflineQueue(userId: string, storage?: Storage): void {
  const s = storage ?? defaultStorage();
  if (!s) return;
  const owner = s.getItem(KEPT_QUEUE_OWNER_KEY);
  if (!owner) return;
  if (owner !== userId) {
    for (const key of offlineQueueKeys(owner)) s.removeItem(key);
  }
  s.removeItem(KEPT_QUEUE_OWNER_KEY);
}

