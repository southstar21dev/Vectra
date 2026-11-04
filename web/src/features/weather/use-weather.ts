import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query-keys';
import { fetchWeather, BUDAPEST_COORDS, type WeatherCoords } from './weather-api';
import {
  coordsMatch,
  isSnapshotFresh,
  readWeatherSnapshot,
  writeWeatherSnapshot,
} from './weather-snapshot';
import { getCachedCityName } from './weather-location';

/** Current conditions at the given coordinates (defaults to the Budapest
 *  fallback). Open-Meteo refreshes its data every ~15 min, so staleTime
 *  matches that cadence; window-focus refetches keep the card fresh without
 *  background polling. Errors bubble to the card, which hides itself. The
 *  query key carries the coordinates so a location change triggers a fresh
 *  fetch.
 *
 *  A persisted snapshot of the last successful fetch seeds the query when the
 *  coordinates still match, so opening the app paints the card immediately
 *  instead of flashing a skeleton. Stale snapshots are ignored and every
 *  successful fetch refreshes the snapshot. */
export function useWeather(coords: WeatherCoords = BUDAPEST_COORDS) {
  return useQuery({
    queryKey: queryKeys.weather(coords.latitude, coords.longitude),
    queryFn: async () => {
      const data = await fetchWeather(coords);
      const previous = readWeatherSnapshot();
      // Prefer the snapshot's own city for these coords; otherwise fall back to
      // the in-memory geocode cache. That cache may already hold the just-
      // resolved name even when this fetch won the race against the snapshot
      // write, so the city is not lost to a null.
      const city =
        previous && coordsMatch(previous.coords, coords)
          ? previous.city
          : getCachedCityName(coords.latitude, coords.longitude);
      writeWeatherSnapshot({
        coords: { latitude: coords.latitude, longitude: coords.longitude },
        city,
        data,
        fetchedAt: Date.now(),
      });
      return data;
    },
    initialData: () => {
      const snapshot = readWeatherSnapshot();
      return snapshot && isSnapshotFresh(snapshot) && coordsMatch(snapshot.coords, coords)
        ? snapshot.data
        : undefined;
    },
    initialDataUpdatedAt: () => {
      const snapshot = readWeatherSnapshot();
      return snapshot && coordsMatch(snapshot.coords, coords) ? snapshot.fetchedAt : undefined;
    },
    staleTime: 15 * 60_000,
    refetchOnWindowFocus: true,
    retry: 3,
    // When the location changes (Budapest fallback → device coords) or the
    // window refocuses, keep showing the last weather while the new fetch is
    // in flight — otherwise the card would flash to its skeleton every time
    // the query key changes.
    placeholderData: (previousData) => previousData,
  });
}
