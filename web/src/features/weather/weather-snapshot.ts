import { defaultStorage, type StorageLike } from '@/lib/storage';
import type { WeatherCoords, WeatherData } from './weather-api';

/* --------------------------------------------------------------------------
 * Persisted snapshot of the last successful weather fetch (plus the location
 * it belongs to). React Query's cache is memory-only, so without this every
 * app open flashed the skeleton and re-resolved the location. The card now
 * renders the snapshot immediately and revalidates in the background.
 * ------------------------------------------------------------------------ */

export const WEATHER_SNAPSHOT_KEY = 'vectra.weather.snapshot';

/** Weather older than this is not worth showing — a fresh fetch is awaited. */
export const WEATHER_SNAPSHOT_MAX_AGE_MS = 24 * 60 * 60_000;

export interface WeatherSnapshot {
  coords: WeatherCoords;
  city: string | null;
  data: WeatherData;
  fetchedAt: number;
}

/** Weather coordinates are rounded to ~11 m: precise enough for a forecast,
 *  coarse enough that a reload reuses the same query key and snapshot. */
export function roundCoords(coords: WeatherCoords, precision = 4): WeatherCoords {
  const factor = 10 ** precision;
  return {
    latitude: Math.round(coords.latitude * factor) / factor,
    longitude: Math.round(coords.longitude * factor) / factor,
  };
}

export function coordsMatch(a: WeatherCoords, b: WeatherCoords, precision = 4): boolean {
  const roundedA = roundCoords(a, precision);
  const roundedB = roundCoords(b, precision);
  return roundedA.latitude === roundedB.latitude && roundedA.longitude === roundedB.longitude;
}

function isWeatherData(value: unknown): value is WeatherData {
  if (!value || typeof value !== 'object') return false;
  const data = value as Record<string, unknown>;
  return (
    Number.isFinite(data.temperature) &&
    Number.isFinite(data.feelsLike) &&
    Number.isFinite(data.windSpeed) &&
    Number.isFinite(data.weatherCode) &&
    Number.isFinite(data.rainProbability) &&
    Number.isFinite(data.high) &&
    Number.isFinite(data.low)
  );
}

export function readWeatherSnapshot(
  storage: StorageLike | null = defaultStorage()
): WeatherSnapshot | null {
  try {
    const raw = storage?.getItem(WEATHER_SNAPSHOT_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Record<string, unknown> | null;
    if (!parsed || typeof parsed !== 'object') return null;
    const coords = parsed.coords as Record<string, unknown> | undefined;
    if (!coords || !Number.isFinite(coords.latitude) || !Number.isFinite(coords.longitude)) {
      return null;
    }
    if (!Number.isFinite(parsed.fetchedAt) || !isWeatherData(parsed.data)) return null;
    return {
      coords: { latitude: coords.latitude as number, longitude: coords.longitude as number },
      city: typeof parsed.city === 'string' ? parsed.city : null,
      data: parsed.data,
      fetchedAt: parsed.fetchedAt as number,
    };
  } catch {
    return null;
  }
}

export function writeWeatherSnapshot(
  snapshot: WeatherSnapshot,
  storage: StorageLike | null = defaultStorage()
): void {
  try {
    storage?.setItem(WEATHER_SNAPSHOT_KEY, JSON.stringify(snapshot));
  } catch {
    /* storage failures only cost the instant-first-paint optimisation */
  }
}

/** Keeps the snapshot's city label in sync with the reverse geocoder, without
 *  clobbering a snapshot for a different location. */
export function updateWeatherSnapshotCity(
  coords: WeatherCoords,
  city: string | null,
  storage: StorageLike | null = defaultStorage()
): void {
  const snapshot = readWeatherSnapshot(storage);
  if (!snapshot || !coordsMatch(snapshot.coords, coords)) return;
  if (snapshot.city === city) return;
  // A null result is a failed/unknown geocode, not a fact: never downgrade a
  // name we already know for these coordinates.
  if (city === null && snapshot.city) return;
  writeWeatherSnapshot({ ...snapshot, city }, storage);
}

export function isSnapshotFresh(
  snapshot: WeatherSnapshot,
  now = Date.now(),
  maxAgeMs = WEATHER_SNAPSHOT_MAX_AGE_MS
): boolean {
  return now - snapshot.fetchedAt <= maxAgeMs;
}
