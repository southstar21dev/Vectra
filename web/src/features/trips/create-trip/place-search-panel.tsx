import { Suspense, lazy, useEffect, useState } from 'react';
import { RiArrowRightSLine, RiLoader4Line, RiFocus3Line, RiMap2Line as MapIcon, RiMapPinLine, RiSearchLine, RiTrainLine } from '@remixicon/react';
import { placeIcon } from './place-icon';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { getErrorMessage } from '@/lib/error-utils';
import { formatDistanceMeters } from '@/features/transit/nearby-stops';
import { haversineMeters, type LatLon } from '@/features/transit/route-geometry';
import { MapFallback } from '@/features/transit/map/map-fallback';
import type { CatalogStop } from '@/features/transit/catalog-types';
import { searchPlacesAndStops, nearestStop, ADDRESS_SNAP_MAX_METERS, type PlaceSearchResult } from '../place-search';
import { reverseGeocode } from '../geocode';
import { fetchWalkingRoute } from '../walking-route';
import { resolveCurrentTripAddress } from '../current-location';
import { endpointName, type Endpoint } from './constants';

// Lazy: the map picker pulls in maplibre, kept out of the eager bundle.
const LazyLocationMapPicker = lazy(() => import('../location-map-picker').then((m) => ({ default: m.LocationMapPicker })));

interface PlaceSearchPanelProps {
  target: 'from' | 'to';
  stops: CatalogStop[];
  /** Places from the user's saved trips, offered before they type. */
  savedPlaces: Array<Endpoint & { tag?: string }>;
  onPick: (endpoint: Endpoint) => void;
}

/** A search result as a journey endpoint, or null for a place with no stop nearby. */
function resultToEndpoint(result: PlaceSearchResult, stops: CatalogStop[]): Endpoint | null {
  if (!result.stopId) return null;
  if (result.kind === 'stop') {
    const stop = stops.find((s) => s.id === result.stopId);
    return { stop: stop ?? { id: result.stopId, name: result.label, lat: result.lat, lon: result.lon }, address: null };
  }
  const stop = stops.find((s) => s.id === result.stopId);
  return {
    stop: stop ?? { id: result.stopId, name: result.snappedStopName ?? result.label, lat: result.lat, lon: result.lon },
    address: {
      name: result.label,
      distanceMeters: result.snappedDistanceMeters ?? 0,
      lat: result.lat,
      lon: result.lon,
      stopId: result.stopId,
      stopName: result.snappedStopName,
    },
  };
}

const rowClass =
  'group flex w-full min-h-14 items-center gap-3 border-b border-subtle px-4 py-2.5 text-left transition-colors cursor-pointer last:border-b-0 hover:bg-surface-overlay active:bg-surface-overlay focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent-primary';

function ResultRow({
  icon: Icon,
  label,
  sublabel,
  onClick,
  disabled,
}: {
  icon: typeof RiMapPinLine;
  label: string;
  sublabel: string;
  onClick: () => void;
  disabled?: boolean;
}) {
  return (
    <button type="button" onClick={onClick} disabled={disabled} className={cn(rowClass, 'disabled:cursor-not-allowed disabled:opacity-45 disabled:hover:bg-transparent')}>
      <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-surface-overlay text-secondary group-hover:text-accent-primary">
        <Icon className="h-4 w-4" />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-sm font-semibold text-primary">{label}</span>
        <span className="mt-0.5 block truncate text-xs text-muted">{sublabel}</span>
      </span>
      {!disabled && <RiArrowRightSLine className="h-4 w-4 shrink-0 text-muted transition-transform group-hover:translate-x-0.5" />}
    </button>
  );
}

/**
 * Choosing one end of the journey: search stops and addresses, use the
 * current location, drop a pin, or reuse a place from a saved trip. Every
 * choice resolves to a BKK stop (an address snaps to its nearest stop).
 */
export function PlaceSearchPanel({ target, stops, savedPlaces, onPick }: PlaceSearchPanelProps) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<PlaceSearchResult[]>([]);
  const [searching, setSearching] = useState(false);
  const [locating, setLocating] = useState(false);
  const [locationError, setLocationError] = useState('');
  const [mapOpen, setMapOpen] = useState(false);
  const [pin, setPin] = useState<LatLon | null>(null);
  const [pinResult, setPinResult] = useState<Endpoint | null>(null);
  const [pinLoading, setPinLoading] = useState(false);
  const [pinError, setPinError] = useState('');

  // Debounced stop + address search. State writes stay inside the timer so
  // typing never triggers a synchronous cascading render. `cancelled` drops a
  // slower response for an older query, so it can never overwrite the results
  // of the newer one (or flip the spinner off while that one is in flight).
  useEffect(() => {
    const q = query.trim();
    let cancelled = false;
    const timer = setTimeout(async () => {
      if (!q) {
        setResults([]);
        setSearching(false);
        return;
      }
      setSearching(true);
      try {
        const next = await searchPlacesAndStops(q, stops);
        if (!cancelled) setResults(next);
      } catch {
        if (!cancelled) setResults([]);
      } finally {
        if (!cancelled) setSearching(false);
      }
    }, 250);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [query, stops]);

  const useCurrentLocation = async () => {
    setLocating(true);
    setLocationError('');
    try {
      const address = await resolveCurrentTripAddress(stops);
      const stop = stops.find((s) => s.id === address.stopId);
      if (!stop) throw new Error('The nearest BKK stop could not be found.');
      onPick({ stop, address });
    } catch (error) {
      setLocationError(getErrorMessage(error, 'Could not get your location. Check location permission and try again.'));
    } finally {
      setLocating(false);
    }
  };

  const dropPin = async (point: LatLon) => {
    setPin(point);
    setPinResult(null);
    setPinError('');
    const stop = nearestStop(point.lat, point.lon, stops);
    if (!stop) {
      setPinError(`No BKK stop within ${Math.round(ADDRESS_SNAP_MAX_METERS / 1000)} km of this point. Tap closer to a stop.`);
      return;
    }
    setPinLoading(true);
    const [reverse, walking] = await Promise.allSettled([reverseGeocode(point.lat, point.lon), fetchWalkingRoute(point, stop)]);
    const place = reverse.status === 'fulfilled' ? reverse.value : null;
    const walk = walking.status === 'fulfilled' ? walking.value : null;
    setPinResult({
      stop,
      address: {
        name: place?.displayName || place?.name || 'Pinned location',
        distanceMeters: walk?.distanceMeters ?? Math.max(1, Math.round(haversineMeters(point, stop))),
        walkingMinutes: walk?.durationMinutes,
        lat: point.lat,
        lon: point.lon,
        stopId: stop.id,
        stopName: stop.name,
      },
    });
    setPinLoading(false);
  };

  const stopResults = results.filter((r) => r.kind === 'stop');
  const placeResults = results.filter((r) => r.kind === 'place');
  const typing = query.trim().length > 0;

  return (
    <div className="space-y-5">
      <Input
        icon={RiSearchLine}
        autoFocus
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder={target === 'from' ? 'Search where you start' : 'Search where you’re going'}
        aria-label={target === 'from' ? 'Search starting point' : 'Search destination'}
        enterKeyHint="search"
        className="h-12 text-base sm:text-sm"
      />

      <div className="grid grid-cols-2 gap-2">
        <Button type="button" variant="outline" className="h-11 rounded-2xl" onClick={useCurrentLocation} disabled={locating}>
          {locating ? <RiLoader4Line className="h-4 w-4 animate-spin" /> : <RiFocus3Line className="h-4 w-4" />}
          Current location
        </Button>
        <Button
          type="button"
          variant="outline"
          aria-pressed={mapOpen}
          className={cn('h-11 rounded-2xl', mapOpen && 'border-accent-primary/50 text-accent-primary')}
          onClick={() => setMapOpen((open) => !open)}
        >
          <MapIcon className="h-4 w-4" />
          Pick on map
        </Button>
      </div>
      {locationError && <p role="alert" className="-mt-2 text-xs text-tint-red">{locationError}</p>}

      {mapOpen && (
        <div className="space-y-3">
          <div className="overflow-hidden rounded-3xl">
            <Suspense fallback={<MapFallback />}>
              <LazyLocationMapPicker initialCenter={pin ?? undefined} selectedPoint={pin} onMapClick={dropPin} />
            </Suspense>
          </div>
          {!pin && !pinError && <p className="text-xs text-muted">Tap the map where you {target === 'from' ? 'start' : 'want to end up'}.</p>}
          {pinLoading && (
            <p className="flex items-center gap-2 text-xs text-muted">
              <RiLoader4Line className="h-3.5 w-3.5 animate-spin" /> Finding the nearest stop…
            </p>
          )}
          {pinError && <p role="alert" className="text-xs text-tint-red">{pinError}</p>}
          {pinResult && !pinLoading && (
            <div className="flex items-center gap-3 rounded-2xl panel-glass p-3">
              <RiMapPinLine className="h-4 w-4 shrink-0 text-accent-primary" />
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-semibold text-primary">{pinResult.address?.name}</p>
                <p className="truncate text-xs text-muted">
                  {formatDistanceMeters(pinResult.address?.distanceMeters ?? 0)} to {pinResult.stop.name}
                </p>
              </div>
              <Button type="button" size="sm" onClick={() => onPick(pinResult)}>
                Use this
              </Button>
            </div>
          )}
        </div>
      )}

      {!typing && savedPlaces.length > 0 && (
        <section aria-labelledby="saved-places">
          <h3 id="saved-places" className="mb-2 px-1 text-xs font-semibold text-muted">
            From your trips
          </h3>
          <div className="overflow-hidden rounded-3xl card-glass">
            {savedPlaces.map((place) => (
              <ResultRow
                key={`${place.stop.id}:${place.address?.name ?? ''}`}
                icon={placeIcon(place.tag)}
                label={endpointName(place)}
                sublabel={[place.tag, place.address ? `Near ${place.stop.name}` : 'BKK stop'].filter(Boolean).join(' · ')}
                onClick={() => onPick(place)}
              />
            ))}
          </div>
        </section>
      )}

      {typing && searching && results.length === 0 && (
        <div role="status" aria-label="Searching" className="space-y-1.5">
          {[0, 1, 2].map((i) => (
            <div key={i} className="flex items-center gap-3 px-3 py-2.5">
              <div className="h-9 w-9 rounded-xl bg-surface-overlay motion-safe:animate-pulse" />
              <div className="flex-1 space-y-1.5">
                <div className="h-3 w-2/3 rounded bg-surface-overlay motion-safe:animate-pulse" />
                <div className="h-2.5 w-1/3 rounded bg-surface-overlay motion-safe:animate-pulse" />
              </div>
            </div>
          ))}
        </div>
      )}

      {typing && stopResults.length > 0 && (
        <section aria-labelledby="stop-results">
          <h3 id="stop-results" className="mb-2 px-1 text-xs font-semibold text-muted">Stops</h3>
          <div className="overflow-hidden rounded-3xl card-glass">
          {stopResults.map((result) => (
            <ResultRow
              key={result.id}
              icon={RiTrainLine}
              label={result.label}
              sublabel="BKK stop"
              onClick={() => {
                const endpoint = resultToEndpoint(result, stops);
                if (endpoint) onPick(endpoint);
              }}
            />
          ))}
          </div>
        </section>
      )}

      {typing && placeResults.length > 0 && (
        <section aria-labelledby="place-results">
          <h3 id="place-results" className="mb-2 px-1 text-xs font-semibold text-muted">Places and addresses</h3>
          <div className="overflow-hidden rounded-3xl card-glass">
          {placeResults.map((result) => {
            const endpoint = resultToEndpoint(result, stops);
            return (
              <ResultRow
                key={result.id}
                icon={RiMapPinLine}
                label={result.label}
                sublabel={endpoint ? result.sublabel : 'No BKK stop within 2 km'}
                disabled={!endpoint}
                onClick={() => endpoint && onPick(endpoint)}
              />
            );
          })}
          </div>
        </section>
      )}

      {typing && !searching && results.length === 0 && (
        <div className="px-3 py-6 text-center">
          <p className="text-sm font-semibold text-primary">Nothing found for “{query.trim()}”</p>
          <p className="mt-1 text-xs text-muted">Try a stop name or a Budapest street, or pick the spot on the map.</p>
        </div>
      )}
    </div>
  );
}
