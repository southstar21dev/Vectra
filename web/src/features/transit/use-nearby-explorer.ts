import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { toast } from 'sonner';
import { useAuth } from '@/features/auth/use-auth';
import { useDeviceLocation } from '@/lib/location';
import { useNow } from '@/hooks/use-now';
import {
  BUDAPEST_CENTER,
  buildExplorerMapStops,
  buildRadarDepartures,
  findStopsInBounds,
  parseNearbyBoards,
  resolveAnchor,
  type ExplorerMapStop,
  type LatLonBounds,
  type MapAnchor,
  type NearbyDeparture,
  type NearbyStop,
  type RadarDepartureItem,
} from './nearby-stops';
import {
  placeVehiclesOnPolyline,
  resolveStopRefs,
  selectTripPolyline,
  selectTripStopIds,
  selectVariantPolylines,
  selectVariantStopIds,
  type LatLon,
  type PlacedVehicle,
} from './route-geometry';
import {
  createModeFilterWriteQueue,
  parseModeFilter,
  sameModeFilter,
  selectSingleModeFilter,
} from './transit-mode-filter';
import { useCatalogRoutes, useCatalogStops } from './use-nearby';
import {
  useBatchStopArrivals,
  useRouteDetails,
  useTripDetails,
  useVehiclesForRoute,
} from './use-transit';

/** Departures are not fetched below this zoom (the map is too wide to matter). */
export const EXPLORER_MIN_ARRIVALS_ZOOM = 12;
/** Max stops polled for arrivals per settled viewport. */
const MAX_ARRIVAL_STOPS = 10;
/** Max feed rows. */
const MAX_FEED_ITEMS = 40;

export interface ExplorerViewport {
  lat: number;
  lon: number;
  zoom: number;
  bounds: LatLonBounds;
}

export interface ExplorerSelection {
  stopId: string;
  stopName: string;
  lat: number | null;
  lon: number | null;
  routeId: string;
  shortName: string;
  /** Mode of the latched route, used to drop the selection on filter changes. */
  routeType: string;
  headsign: string;
  color: string;
  textColor: string;
  /** The exact trip serving this departure — preferred geometry source. */
  tripId?: string;
  /** Physical stop the departure leaves from, so its own route-stop dot can
   *  be hidden under the pulse marker. */
  platformStopId?: string | null;
  distanceMeters: number;
  arrivalMs: number;
  isPredicted: boolean;
}

/** The selected stop's latched identity — survives leaving the viewport. */
export interface ExplorerSelectedStop {
  id: string;
  name: string;
  lat: number;
  lon: number;
  platformStopId?: string | null;
}

export interface ExplorerRouteStop {
  id: string;
  name: string;
  lat: number;
  lon: number;
}

export function useNearbyExplorer() {
  const { profile, updateProfile } = useAuth();
  // Latest preferences, read when a queued filter write actually runs, so it
  // never re-saves a snapshot that predates another preference change.
  const preferencesRef = useRef(profile?.preferences);
  useEffect(() => {
    preferencesRef.current = profile?.preferences;
  }, [profile?.preferences]);
  const { state: geoState, refreshLocation } = useDeviceLocation();
  const now = useNow(30_000);
  const catalogStops = useCatalogStops();
  const catalogRoutes = useCatalogRoutes();

  const [viewport, setViewport] = useState<ExplorerViewport | null>(null);
  // Feed filtering uses the catalog station id, while map highlighting uses
  // the exact physical dot that was pressed. They differ when a station has
  // several same-named platforms.
  const [focusedStopId, setFocusedStopId] = useState<string | null>(null);
  const [focusedMapStopId, setFocusedMapStopId] = useState<string | null>(null);
  const [selection, setSelection] = useState<ExplorerSelection | null>(null);

  const persistedModeFilter = useMemo(
    () =>
      parseModeFilter(
        profile?.preferences as Record<string, unknown> | null | undefined
      ),
    [profile?.preferences]
  );

  // Local-first override: chip taps apply instantly even while the profile
  // write is in flight (and stay applied if it fails). The override clears as
  // soon as the persisted value catches up, so it never shadows an external
  // change (e.g. the dashboard gear dialog) for longer than one refetch.
  const [modeFilterOverride, setModeFilterOverride] = useState<string[] | null>(null);
  const modeFilter = modeFilterOverride ?? persistedModeFilter;

  // Latest locally selected filter. State can lag same-tick taps; the chip
  // handler computes the next value from this ref so rapid taps chain, and the
  // write queue below persists only the final value.
  const desiredModeFilterRef = useRef<string[] | null>(null);

  // Serialized so an earlier mode write can never complete after a later one.
  const writeModeFilter = useMemo(() => createModeFilterWriteQueue(), []);

  // Release the local override as soon as the persisted filter catches up.
  // Adjusted during render so a stale override can never paint.
  if (modeFilterOverride && sameModeFilter(modeFilterOverride, persistedModeFilter)) {
    setModeFilterOverride(null);
  }

  // Release the rapid-tap ref once the override is gone (refs are never
  // written during render).
  useEffect(() => {
    if (!modeFilterOverride) desiredModeFilterRef.current = null;
  }, [modeFilterOverride]);

  // Drop a latched departure selection as soon as the active filter no longer
  // includes its mode, so its polyline/vehicles/stops do not outlive the
  // filter (an empty filter means ALL, nothing to clear). Adjusted during
  // render so the stale selection can never paint.
  const [prevSelectionState, setPrevSelectionState] = useState({ modeFilter, selection });
  if (modeFilter !== prevSelectionState.modeFilter || selection !== prevSelectionState.selection) {
    setPrevSelectionState({ modeFilter, selection });
    if (modeFilter.length > 0 && selection) {
      const active = new Set(modeFilter.map((mode) => mode.toUpperCase()));
      if (!active.has(selection.routeType.toUpperCase())) setSelection(null);
    }
  }

  const anchor: MapAnchor | null = useMemo(() => {
    if (geoState.status === 'tracking') return resolveAnchor(geoState.lat, geoState.lon);
    return null;
  }, [geoState.status, geoState.lat, geoState.lon]);

  const initialCenter: LatLon = anchor
    ? { lat: anchor.lat, lon: anchor.lon }
    : { ...BUDAPEST_CENTER };

  const stopsInView = useMemo(() => {
    if (!catalogStops.data || !viewport) return [];
    // The map should show every catalog stop inside the visible bounds. Only
    // arrivals polling is capped below; limiting this list made valid stops
    // disappear whenever a dense area exceeded the old 60-dot cap.
    return findStopsInBounds(catalogStops.data, viewport.bounds, { limit: Number.MAX_SAFE_INTEGER });
  }, [catalogStops.data, viewport]);

  const arrivalsEnabled = !!viewport && viewport.zoom >= EXPLORER_MIN_ARRIVALS_ZOOM;
  const arrivalStops = useMemo(
    () => (arrivalsEnabled ? stopsInView.slice(0, MAX_ARRIVAL_STOPS) : []),
    [arrivalsEnabled, stopsInView]
  );
  const arrivalStopIds = useMemo(() => arrivalStops.map((s) => s.id), [arrivalStops]);
  const stopArrivalsResults = useBatchStopArrivals(arrivalStopIds);

  const stopDeparturesMap = useMemo(() => {
    const map = new Map<string, NearbyDeparture[]>();
    for (const stop of arrivalStops) {
      const data = stopArrivalsResults[stop.id]?.data;
      map.set(stop.id, parseNearbyBoards(data, catalogRoutes.data ?? null, now));
    }
    return map;
  }, [arrivalStops, stopArrivalsResults, catalogRoutes.data, now]);

  // Map dots: every catalog station in view, with each polled station's
  // physical stops substituted from the arrivals response it already fetched.
  // Arrivals polling remains capped for performance; the visual stop list does not.
  const mapStops = useMemo<ExplorerMapStop[]>(() => {
    if (!viewport) return [];
    const arrivalsByStopId = new Map(
      arrivalStops.map(
        (stop) => [stop.id, stopArrivalsResults[stop.id]?.data] as const
      )
    );
    return buildExplorerMapStops(stopsInView, arrivalsByStopId, viewport.bounds);
  }, [viewport, stopsInView, arrivalStops, stopArrivalsResults]);

  const feed = useMemo(() => {
    if (!arrivalsEnabled) return [];
    return buildRadarDepartures({
      stops: arrivalStops,
      stopDeparturesMap,
      now,
      modeFilter,
      maxItems: MAX_FEED_ITEMS,
    });
  }, [arrivalsEnabled, arrivalStops, stopDeparturesMap, now, modeFilter]);

  const visibleFeed = useMemo(
    () => (focusedStopId ? feed.filter((item) => item.stopId === focusedStopId) : feed),
    [feed, focusedStopId]
  );

  const focusedStop: NearbyStop | null = useMemo(
    () => (focusedStopId ? stopsInView.find((s) => s.id === focusedStopId) ?? null : null),
    [focusedStopId, stopsInView]
  );

  const selectedStop = useMemo<ExplorerSelectedStop | null>(() => {
    if (!selection || selection.lat === null || selection.lon === null) return null;
    return {
      id: selection.stopId,
      name: selection.stopName,
      lat: selection.lat,
      lon: selection.lon,
      platformStopId: selection.platformStopId ?? null,
    };
  }, [selection]);

  // Clear the focus when its stop leaves the viewport (adjusted during render
  // so a focus for an off-screen stop can never paint).
  const [prevViewState, setPrevViewState] = useState({ focusedStopId, stopsInView });
  if (focusedStopId !== prevViewState.focusedStopId || stopsInView !== prevViewState.stopsInView) {
    setPrevViewState({ focusedStopId, stopsInView });
    if (focusedStopId && !stopsInView.some((s) => s.id === focusedStopId)) {
      setFocusedStopId(null);
    }
  }

  const handleViewportChange = useCallback((next: ExplorerViewport) => {
    setViewport(next);
  }, []);

  const handleSelectItem = useCallback(
    (item: RadarDepartureItem) => {
      // Route tracking is a toggle by route: selecting the route already being
      // tracked dismisses its line, while another route replaces it. This is
      // intentionally independent of the map camera, so pan/zoom gestures do
      // not affect the tracked route.
      const isSameDepartureGroup =
        selection?.routeId === item.routeId &&
        selection.stopId === item.stopId &&
        selection.headsign === item.headsign;
      if (isSameDepartureGroup) {
        setSelection(null);
        return;
      }

      const stop = stopsInView.find((s) => s.id === item.stopId) ?? null;
      setSelection({
        stopId: item.stopId,
        stopName: stop?.name ?? item.stopName,
        // Prefer the departure's own physical stop over the catalog station
        // centre: same-named platforms can sit far apart.
        lat: item.platformLat ?? stop?.lat ?? null,
        lon: item.platformLon ?? stop?.lon ?? null,
        routeId: item.routeId,
        shortName: item.shortName,
        routeType: item.routeType,
        headsign: item.headsign,
        color: item.color,
        textColor: item.textColor,
        tripId: item.tripId,
        platformStopId: item.platformStopId ?? null,
        distanceMeters: item.distanceMeters,
        arrivalMs: item.arrivalMs,
        isPredicted: item.isPredicted,
      });
    },
    [selection, stopsInView]
  );

  const clearSelection = useCallback(() => setSelection(null), []);

  const focusStop = useCallback((stopId: string, stationId?: string) => {
    // Tapping the same physical dot toggles its focus off. A different stop is
    // an explicit new map selection and dismisses route tracking; empty taps
    // and camera gestures do not.
    if (focusedMapStopId === stopId) {
      setFocusedStopId(null);
      setFocusedMapStopId(null);
      return;
    }
    setSelection(null);
    setFocusedStopId(stationId ?? stopId);
    setFocusedMapStopId(stopId);
  }, [focusedMapStopId]);

  const clearFocus = useCallback(() => {
    setFocusedStopId(null);
    setFocusedMapStopId(null);
  }, []);

  // Selected route geometry + live vehicles. The departure's own trip is the
  // exact geometry source (abbreviated route-details headsigns can otherwise
  // match several variants); route-details stays the fallback when trip
  // details are unavailable (e.g. scheduled trips without a polyline).
  const routeDetails = useRouteDetails(selection?.routeId ?? null);
  const tripDetails = useTripDetails(selection?.tripId ?? null);
  const polylines = useMemo<LatLon[][]>(() => {
    if (!selection) return [];
    const tripPolyline = selectTripPolyline(tripDetails.data);
    if (tripPolyline.length > 0) return [tripPolyline];
    const variants = routeDetails.data?.data?.entry?.variants ?? [];
    // A departure identifies one trip/direction. Keep one fallback geometry so
    // the explorer never renders every matching route variant at once.
    const matchingPolylines = selectVariantPolylines(variants, selection.headsign, selection.routeId);
    return matchingPolylines.length > 0 ? [matchingPolylines[0]] : [];
  }, [selection, routeDetails.data, tripDetails.data]);

  // The selected route's own stops, from the trip's stop sequence when
  // available, else the route-details response (not the viewport-capped
  // catalog list), so stops the cap drops still appear.
  const routeStops = useMemo<ExplorerRouteStop[]>(() => {
    if (!selection) return [];
    const tripData = tripDetails.data?.data;
    if (tripData?.entry?.stopTimes?.length) {
      const resolved = resolveStopRefs(
        selectTripStopIds(tripDetails.data),
        tripData.references?.stops
      );
      // Missing/zero-coordinate trip refs fall through to the route-details
      // variants instead of rendering an empty stop list.
      if (resolved.length > 0) return resolved;
    }
    const data = routeDetails.data?.data;
    const variants = data?.entry?.variants ?? [];
    return resolveStopRefs(
      selectVariantStopIds(variants, selection.headsign, selection.routeId),
      data?.references?.stops
    );
  }, [selection, routeDetails.data, tripDetails.data]);

  const trackedCountdownMinutes = selection
    ? Math.max(0, Math.ceil((selection.arrivalMs - now) / 60_000))
    : null;

  const vehiclesQuery = useVehiclesForRoute(selection?.routeId ?? null);
  const vehicles = useMemo<PlacedVehicle[]>(() => {
    if (!selection) return [];
    const list = vehiclesQuery.data?.data?.list ?? [];
    const trips = vehiclesQuery.data?.data?.references?.trips ?? {};
    const primary = polylines[0] ?? [];
    const raw = list
      .filter((v) => v.location && !(v.location.lat === 0 && v.location.lon === 0))
      .map((v) => ({
        id: v.vehicleId,
        lat: v.location.lat,
        lon: v.location.lon,
        tripId: v.tripId,
        bearing: v.bearing,
      }));

    // The selected departure is the tracked vehicle. Prefer its exact trip;
    // when the feed has no trip id, use one headsign match as a conservative
    // fallback. Never render the whole route's vehicle fleet here.
    let chosen = selection.tripId
      ? raw.filter((v) => v.tripId === selection.tripId).slice(0, 1)
      : [];
    if (chosen.length === 0 && selection.headsign) {
      chosen = raw
        .filter((v) => {
          const headsign = v.tripId ? trips[v.tripId]?.tripHeadsign : undefined;
          return !!headsign && headsign === selection.headsign;
        })
        .slice(0, 1);
    }
    return placeVehiclesOnPolyline(chosen, primary);
  }, [selection, vehiclesQuery.data, polylines]);

  const handleToggleMode = useCallback(
    (id: string) => {
      // Explorer chips are single-select: tapping a mode replaces the active
      // one (tapping the active mode clears back to ALL). Multi-select stays
      // in the dashboard gear dialog's own handler. The ref chains rapid taps
      // that have not re-rendered yet.
      const next = selectSingleModeFilter(desiredModeFilterRef.current ?? modeFilter, id);
      desiredModeFilterRef.current = next;
      // Apply locally first so the feed filters on the next render no matter
      // how the profile round-trip goes.
      setModeFilterOverride(next);
      void writeModeFilter(next, (filter) =>
        updateProfile({
          preferences: {
            ...((preferencesRef.current as Record<string, unknown>) ?? {}),
            transit_mode_filter: filter,
          },
        })
      ).catch(() => {
        toast.error('Could not save transit filter', {
          description: 'The filter is applied here but may reset after reload.',
        });
      });
    },
    [modeFilter, writeModeFilter, updateProfile]
  );

  const isArrivalsLoading = arrivalStops.some(
    (s) => stopArrivalsResults[s.id]?.isLoading
  );

  return {
    geoState,
    refreshLocation,
    anchor,
    initialCenter,
    viewport,
    stopsInView,
    visibleStopCount: stopsInView.length,
    polledStopCount: arrivalStops.length,
    mapStops,
    focusedStop,
    focusedStopId: focusedMapStopId,
    focusStop,
    clearFocus,
    selection,
    selectedStop,
    polylines,
    routeStops,
    vehicles,
    feed: visibleFeed,
    feedCount: feed.length,
    arrivalsEnabled,
    isArrivalsLoading,
    modeFilter,
    handleToggleMode,
    handleSelectItem,
    handleViewportChange,
    clearSelection,
    trackedCountdownMinutes,
  };
}
