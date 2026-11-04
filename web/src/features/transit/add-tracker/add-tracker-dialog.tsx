import { Fragment, useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import { RiArrowLeftRightLine, RiLoader4Line, RiSearchLine } from '@remixicon/react';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { SHEET_INPUT_CLASS } from '@/components/ui/sheet-form';
import { EASE_DRAWER } from '@/components/ui/motion-tokens';
import { SheetBar, SheetBody, SheetFooter, SHEET_GUTTER } from '@/features/trips/sheet-chrome';
import { toast } from 'sonner';
import { cn } from '@/lib/utils';
import { getErrorMessage } from '@/lib/error-utils';
import { CURATED_STOPS, METRO_ROUTE_IDS } from '../curated-stops';
import type { CatalogRoute, CatalogStop } from '../catalog-types';
import { loadCatalogRoutes, loadCatalogStops } from '../catalog-data';
import { fetchCombinedRouteDetails, fetchStopArrivals } from '../futar-api';
import {
  MAX_TRACKER_STOPS,
  addOrUpdateTrackerStop,
  formatRouteDisplayName,
  getCombinedRouteIds,
  getTrackerStops,
  isRouteMatch,
  normalizeSearchText,
  normalizeStopIdToStation,
  parseStopArrivals,
  sortRoutes,
  splitRouteTitle,
} from '../transit-utils';
import type { Arrival, FutarArrivalsResponse, FutarRoute, FutarRouteVariant, FutarStop, TrackerStop } from '../types';
import { useAddTransitTracker, useMultiStopArrivals, useUpdateTrackerStops, useVehiclesForRoute } from '../use-transit';
import { decodePolyline } from '../route-geometry';
import type { LatLon } from '../route-geometry';
import type { TransitTracker } from '../use-transit';
import { useAppearance } from '@/hooks/use-appearance';
import { RouteBadge } from '../route-badge';
import { METRO_FALLBACK_COLORS, metroViewLabel, type BrowseMode, type MetroView } from './constants';
import { CuratedStopList, StopBrowser, StopRoutePicker } from './stop-browser';
import { LineBrowser, LineStopPicker } from './line-browser';

interface AddTrackerDialogProps {
  open: boolean;
  onClose: () => void;
  existing?: TransitTracker[];
}

/** Which pane the sheet shows; the bar's title and leading button follow it. */
type View =
  | { kind: 'browse'; depth: 0 }
  | { kind: 'metro'; depth: 1 }
  | { kind: 'line'; depth: 1 }
  | { kind: 'stop'; depth: 1 | 2 };

export function AddTrackerDialog({ open, onClose, existing = [] }: AddTrackerDialogProps) {
  const { prefs } = useAppearance();
  const addTracker = useAddTransitTracker();
  const updateTrackerStops = useUpdateTrackerStops();
  const titleId = useId();
  const reduceMotion = useReducedMotion();

  const [scrolled, setScrolled] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [metroView, setMetroView] = useState<MetroView | null>(null);
  const [browseMode, setBrowseMode] = useState<BrowseMode>('line');
  const [selectedStopId, setSelectedStopId] = useState<string | null>(null);
  const [selectedRouteId, setSelectedRouteId] = useState<string | null>(null);
  const [selectedHeadsign, setSelectedHeadsign] = useState('');

  // Routes + full arrivals for the selected stop (BY STOP mode)
  const [routes, setRoutes] = useState<FutarRoute[] | null>(null);
  const [arrivalsData, setArrivalsData] = useState<FutarArrivalsResponse | null>(null);
  const [loadingRoutes, setLoadingRoutes] = useState(false);
  const [routesError, setRoutesError] = useState<string | null>(null);

  // Lazy-loaded catalog
  const [catalog, setCatalog] = useState<{ routes: CatalogRoute[]; stops: CatalogStop[] } | null>(null);
  const [catalogError, setCatalogError] = useState(false);
  const [catalogAttempt, setCatalogAttempt] = useState(0);
  const catalogLoadSeqRef = useRef(0);

  // BY LINE browser state
  const [routeQuery, setRouteQuery] = useState('');
  const [routeTypeFilter, setRouteTypeFilter] = useState('ALL');
  const [stopQuery, setStopQuery] = useState('');
  const [browseRoute, setBrowseRoute] = useState<CatalogRoute | null>(null);
  const [routeStops, setRouteStops] = useState<FutarStop[] | null>(null);
  const [routeHeadsigns, setRouteHeadsigns] = useState<string[]>([]);
  const [routePolylines, setRoutePolylines] = useState<LatLon[][]>([]);
  const [loadingRouteStops, setLoadingRouteStops] = useState(false);
  const [routeStopsError, setRouteStopsError] = useState<string | null>(null);
  const [selectedLineStops, setSelectedLineStops] = useState<TrackerStop[]>([]);
  const routeFetchSeqRef = useRef(0);

  const searchRef = useRef<HTMLInputElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  const selectedStop = useMemo(
    () => CURATED_STOPS.find((s) => s.id === selectedStopId) || null,
    [selectedStopId]
  );

  // Reset the browser to a clean slate each time the dialog opens — adjusted
  // during render so the previous session's picks can never paint for a frame.
  const [prevOpen, setPrevOpen] = useState(false);
  if (open !== prevOpen) {
    setPrevOpen(open);
    if (open) {
      setScrolled(false);
      setSearchQuery('');
      setMetroView(null);
      setBrowseMode('line');
      setSelectedStopId(null);
      setSelectedRouteId(null);
      setSelectedHeadsign('');
      setRoutes(null);
      setArrivalsData(null);
      setRoutesError(null);
      setRouteQuery('');
      setRouteTypeFilter('ALL');
      setStopQuery('');
      setBrowseRoute(null);
      setRouteStops(null);
      setRouteHeadsigns([]);
      setRoutePolylines([]);
      setRouteStopsError(null);
      setSelectedLineStops([]);
      setCatalogError(false);
    }
  }

  // Closing invalidates in-flight route fetches so a slow response from this
  // session can't fill the dialog the next time it opens.
  useEffect(() => {
    if (!open) routeFetchSeqRef.current++;
  }, [open]);

  // Focus the search box once the open animation has started.
  useEffect(() => {
    if (!open) return;
    const raf = requestAnimationFrame(() => searchRef.current?.focus());
    return () => cancelAnimationFrame(raf);
  }, [open]);

  // Lazy-load catalog
  useEffect(() => {
    if (!open) return;
    const seq = ++catalogLoadSeqRef.current;
    void Promise.all([loadCatalogRoutes(), loadCatalogStops()])
      .then(([routes, stops]) => {
        if (seq !== catalogLoadSeqRef.current) return;
        setCatalog({ routes, stops });
      })
      .catch(() => {
        if (seq === catalogLoadSeqRef.current) setCatalogError(true);
      });
  }, [open, catalogAttempt]);

  const retryCatalog = () => {
    setCatalogError(false);
    setCatalogAttempt((a) => a + 1);
  };

  const loadRoutes = async (stopId: string) => {
    // Guards against fast stop-switching: a slower response for stop A must
    // never overwrite the routes/arrivals already shown for stop B.
    const seq = ++routeFetchSeqRef.current;
    setLoadingRoutes(true);
    setRoutesError(null);
    setRoutes(null);
    setArrivalsData(null);
    setSelectedRouteId(null);
    setSelectedHeadsign('');
    try {
      const res = await fetchStopArrivals(stopId, 90);
      if (seq !== routeFetchSeqRef.current) return;
      setArrivalsData(res);
      const refs = res.data?.references?.routes || {};
      let routeList = Object.values(refs) as FutarRoute[];
      const fallback = fallbackRouteFor(stopId);
      if (fallback && !routeList.some((r) => r.id === fallback.id)) {
        routeList = [...routeList, fallback];
      }
      setRoutes(sortRoutes(routeList));
      if (routeList.length === 0) throw new Error('No routes returned for this stop');
    } catch (err) {
      if (seq !== routeFetchSeqRef.current) return;
      console.warn('Failed to load routes for stop:', err);
      setRoutesError(getErrorMessage(err, 'Failed to load routes'));
    } finally {
      if (seq === routeFetchSeqRef.current) setLoadingRoutes(false);
    }
  };

  const handleStopSelect = (stopId: string) => {
    setSelectedStopId(stopId);
    void loadRoutes(stopId);
  };

  const fallbackRouteFor = (stopId: string): FutarRoute | null => {
    const stop = CURATED_STOPS.find((s) => s.id === stopId);
    if (!stop || stop.group === 'POPULAR') return null;
    const line = stop.group;
    const routeId = METRO_ROUTE_IDS[line];
    if (!routeId) return null;
    const colors = METRO_FALLBACK_COLORS[line];
    return {
      id: routeId,
      shortName: line,
      description: '',
      type: 'SUBWAY',
      color: colors.color,
      textColor: colors.textColor,
    };
  };

  const fallbackRoute = useMemo(() => (selectedStop ? fallbackRouteFor(selectedStop.id) : null), [selectedStop]);

  const selectedRoute = useMemo(
    () =>
      (routes || []).find((r) => r.id === selectedRouteId) ||
      (fallbackRoute && selectedRouteId === fallbackRoute.id ? fallbackRoute : null) ||
      null,
    [routes, selectedRouteId, fallbackRoute]
  );

  const directions = useMemo(() => {
    if (!selectedRoute || !arrivalsData) return [];
    const arrivals = parseStopArrivals(arrivalsData, selectedRoute.id);
    const map = new Map<string, Arrival[]>();
    for (const a of arrivals) {
      const key = a.headSign || '—';
      if (!map.has(key)) map.set(key, []);
      map.get(key)!.push(a);
    }
    return [...map.entries()]
      .map(([headSign, times]) => ({ headSign, times }))
      .sort((a, b) => a.times[0].arrivalMs - b.times[0].arrivalMs);
  }, [selectedRoute, arrivalsData]);

  const filteredStops = useMemo(() => {
    const q = normalizeSearchText(searchQuery.trim());
    if (!q) return [];
    return (catalog?.stops ?? []).filter((s) => normalizeSearchText(s.name).includes(q)).slice(0, 80);
  }, [searchQuery, catalog]);

  const filteredRoutes = useMemo(() => {
    const q = normalizeSearchText(routeQuery.trim());
    return sortRoutes(
      (catalog?.routes ?? []).filter((r) => {
        if (routeTypeFilter !== 'ALL') {
          const type = r.type === 'SUBURBAN_RAILWAY' ? 'RAIL' : r.type;
          if (type !== routeTypeFilter) return false;
        }
        if (!q) return true;
        return (
          normalizeSearchText(r.shortName).includes(q) ||
          normalizeSearchText(r.description).includes(q) ||
          normalizeSearchText(r.type).includes(q)
        );
      }) as FutarRoute[]
    );
  }, [routeQuery, routeTypeFilter, catalog]);

  const filteredRouteStops = useMemo(() => {
    const q = normalizeSearchText(stopQuery.trim());
    if (!q) return routeStops ?? [];
    return (routeStops ?? []).filter((s) => normalizeSearchText(s.name).includes(q));
  }, [stopQuery, routeStops]);

  /** Pick a line in By Line browser → load its stops and initialize selected stops */
  const handleRouteSelect = async (route: CatalogRoute) => {
    const seq = ++routeFetchSeqRef.current;
    setBrowseRoute(route);
    setRouteStops(null);
    setRouteHeadsigns([]);
    setRoutePolylines([]);
    setRouteStopsError(null);
    setStopQuery('');
    setLoadingRouteStops(true);

    // Pre-populate already configured stops if this line is already tracked
    const existingTracker = existing.find((t) => isRouteMatch(t.route_id, route.id));
    if (existingTracker) {
      setSelectedLineStops(getTrackerStops(existingTracker));
    } else {
      setSelectedLineStops([]);
    }

    try {
      const res = await fetchCombinedRouteDetails(route.id);
      if (seq !== routeFetchSeqRef.current) return;
      const refs = res.data?.references?.stops ?? {};
      const variants: FutarRouteVariant[] = res.data?.entry?.variants ?? [];

      const headsignSet = new Set<string>();
      for (const v of variants) {
        if (v.headsign) headsignSet.add(v.headsign);
      }
      setRouteHeadsigns(Array.from(headsignSet));

      // Whole-route geometry: every direction's decoded polyline (the map).
      const polylines: LatLon[][] = [];
      for (const v of variants) {
        const pts = v.polyline?.points ? decodePolyline(v.polyline.points) : [];
        if (pts.length >= 2) polylines.push(pts);
      }
      if (seq !== routeFetchSeqRef.current) return;
      setRoutePolylines(polylines);

      const seen = new Set<string>();
      const stopsOut: FutarStop[] = [];
      for (const v of variants) {
        for (const id of v.stopIds ?? []) {
          const ref = refs[id];
          const areaId = ref?.parentStationId || normalizeStopIdToStation(id);
          if (seen.has(areaId)) continue;
          seen.add(areaId);
          stopsOut.push({
            id: areaId,
            name: refs[areaId]?.name ?? ref?.name ?? id,
            lat: ref?.lat ?? 0,
            lon: ref?.lon ?? 0,
            parentStationId: ref?.parentStationId || areaId,
          });
        }
      }
      if (seq !== routeFetchSeqRef.current) return;
      setRouteStops(stopsOut);
      if (stopsOut.length === 0) throw new Error('No stops returned for this line');
    } catch (err) {
      if (seq !== routeFetchSeqRef.current) return;
      console.warn('Failed to load route stops:', err);
      setRouteStopsError(getErrorMessage(err, 'Failed to load stops'));
    } finally {
      if (seq === routeFetchSeqRef.current) setLoadingRouteStops(false);
    }
  };

  // Live vehicles on the browsed route — polled while the line view is open.
  // The dialog stays mounted when closed (state only resets on open), so both
  // live queries are gated on `open` or they would keep polling in the background.
  const vehiclesQuery = useVehiclesForRoute(browseRoute?.id ?? null, { enabled: open });

  // Arrivals at the stops selected so far — the earliest departure's tripId
  // is THE vehicle the user will take; every other vehicle on the line is
  // noise and gets filtered out below. No stops selected → no relevant
  // vehicle → markers stay hidden until a stop is picked.
  const previewStopIds = useMemo(() => selectedLineStops.map((s) => s.stop_id), [selectedLineStops]);
  const previewArrivals = useMultiStopArrivals(previewStopIds, { enabled: open });
  const relevantTripIds = useMemo(() => {
    const ids = new Set<string>();
    const routeId = browseRoute?.id ?? '';
    if (!routeId) return ids;
    const members = getCombinedRouteIds(routeId);
    const memberIds = members && members.length > 1 ? members : [routeId];
    for (const stop of selectedLineStops) {
      const data = stop.stop_id ? previewArrivals[stop.stop_id]?.data : undefined;
      if (!data) continue;
      let best: Arrival | null = null;
      for (const member of memberIds) {
        const next = parseStopArrivals(data, member, stop.headsign || null)[0];
        if (next && (!best || next.arrivalMs < best.arrivalMs)) best = next;
      }
      if (best?.tripId) ids.add(best.tripId);
    }
    return ids;
  }, [selectedLineStops, previewArrivals, browseRoute]);

  const previewVehicles = useMemo(
    () =>
      (vehiclesQuery.data?.data?.list ?? [])
        .filter((v) => v.location && v.location.lat !== 0 && v.location.lon !== 0)
        .filter((v) => v.tripId && relevantTripIds.has(v.tripId))
        .map((v) => ({
          id: v.vehicleId,
          lat: v.location.lat,
          lon: v.location.lon,
          bearing: v.bearing,
        })),
    [vehiclesQuery.data, relevantTripIds]
  );

  const handleToggleLineStop = (stop: FutarStop) => {
    const exists = selectedLineStops.some((s) => s.stop_id === stop.id);
    if (exists) {
      setSelectedLineStops((prev) => prev.filter((s) => s.stop_id !== stop.id));
    } else {
      if (selectedLineStops.length >= MAX_TRACKER_STOPS) {
        toast.warning('Maximum 4 stops', {
          description: `You can track up to ${MAX_TRACKER_STOPS} stops on a line.`,
        });
        return;
      }
      setSelectedLineStops((prev) => [...prev, { stop_id: stop.id, stop_name: stop.name, headsign: '' }]);
    }
  };

  const handleLineStopDirectionChange = (stopId: string, headsign: string) => {
    setSelectedLineStops((prev) =>
      prev.map((s) => (s.stop_id === stopId ? { ...s, headsign } : s))
    );
  };

  /** Save in BY LINE mode */
  const handleSaveLineTracker = async () => {
    if (!browseRoute || selectedLineStops.length === 0) return;

    const existingTracker = existing.find((t) => isRouteMatch(t.route_id, browseRoute.id));
    try {
      if (existingTracker) {
        await updateTrackerStops.mutateAsync({
          id: existingTracker.id,
          stops: selectedLineStops,
        });
        toast.success('Line tracker updated', {
          description: `Updated ${browseRoute.shortName} with ${selectedLineStops.length} stop${selectedLineStops.length === 1 ? '' : 's'}.`,
        });
      } else {
        await addTracker.mutateAsync({
          route_id: browseRoute.id,
          route_short_name: browseRoute.shortName,
          route_long_name: browseRoute.description || '',
          route_type: browseRoute.type,
          route_color: browseRoute.color || 'FFFFFF',
          route_text_color: browseRoute.textColor || '000000',
          stops: selectedLineStops,
        });
        toast.success('Line tracker added', {
          description: `Tracking ${selectedLineStops.length} stop${selectedLineStops.length === 1 ? '' : 's'} on ${browseRoute.shortName}.`,
        });
      }
      onClose();
    } catch (err) {
      toast.error('Failed to save tracker', { description: getErrorMessage(err) });
    }
  };

  /** Save in BY STOP mode */
  const handleSaveStopTracker = async () => {
    if (!selectedStop || !selectedRoute) return;

    const existingForRoute = existing.find((t) => isRouteMatch(t.route_id, selectedRoute.id));

    try {
      if (existingForRoute) {
        const existingStops = getTrackerStops(existingForRoute);
        const alreadyTracked = existingStops.some((s) => s.stop_id === selectedStop.id);
        if (!alreadyTracked && existingStops.length >= MAX_TRACKER_STOPS) {
          toast.error('Limit reached (4 stops)', {
            description: `This line tracker already has ${MAX_TRACKER_STOPS} stops. Manage stops to replace or edit them.`,
          });
          return;
        }

        const newStops = addOrUpdateTrackerStop(existingStops, {
          stop_id: selectedStop.id,
          stop_name: selectedStop.name,
          headsign: selectedHeadsign,
        });
        await updateTrackerStops.mutateAsync({
          id: existingForRoute.id,
          stops: newStops,
        });
        toast.success('Added to existing line tracker', {
          description: `Added ${selectedStop.name} to ${selectedRoute.shortName}.`,
        });
      } else {
        await addTracker.mutateAsync({
          route_id: selectedRoute.id,
          route_short_name: selectedRoute.shortName,
          route_long_name: selectedRoute.description || '',
          route_type: selectedRoute.type,
          route_color: selectedRoute.color || 'FFFFFF',
          route_text_color: selectedRoute.textColor || '000000',
          stops: [
            {
              stop_id: selectedStop.id,
              stop_name: selectedStop.name,
              headsign: selectedHeadsign,
            },
          ],
        });
        toast.success('Tracker added', {
          description: `${selectedRoute.shortName} at ${selectedStop.name}${selectedHeadsign ? ` towards ${selectedHeadsign}` : ''}.`,
        });
      }
      onClose();
    } catch (err) {
      toast.error('Failed to add tracker', { description: getErrorMessage(err) });
    }
  };

  const isSaving = addTracker.isPending || updateTrackerStops.isPending;
  const allDeparturesCount = directions.reduce((n, d) => n + d.times.length, 0);

  // The pane on screen, derived from the picks so far. A stop opened from a
  // metro list sits one level deeper than one opened from search, so Back
  // lands on the list it came from.
  const view: View = browseRoute
    ? { kind: 'line', depth: 1 }
    : selectedStop
      ? { kind: 'stop', depth: metroView ? 2 : 1 }
      : metroView
        ? { kind: 'metro', depth: 1 }
        : { kind: 'browse', depth: 0 };
  const viewKey = view.kind === 'line' ? `line-${browseRoute!.id}` : view.kind === 'stop' ? `stop-${selectedStop!.id}` : view.kind === 'metro' ? `metro-${metroView}` : 'browse';

  // Slide direction follows depth: deeper panes enter from the right, Back
  // brings the previous one in from the left.
  const [prevView, setPrevView] = useState({ key: viewKey, depth: view.depth as number });
  const [viewDirection, setViewDirection] = useState(1);
  if (prevView.key !== viewKey) {
    setPrevView({ key: viewKey, depth: view.depth });
    setViewDirection(view.depth < prevView.depth ? -1 : 1);
  }
  useLayoutEffect(() => {
    scrollRef.current?.scrollTo({ top: 0 });
  }, [viewKey]);

  const goBack = () => {
    if (view.kind === 'line') {
      setBrowseRoute(null);
      setRouteStops(null);
      setSelectedLineStops([]);
    } else if (view.kind === 'stop') {
      setSelectedStopId(null);
      setSelectedRouteId(null);
      setRoutes(null);
      setArrivalsData(null);
    } else if (view.kind === 'metro') {
      setMetroView(null);
    }
  };

  const viewVariants = reduceMotion
    ? {
        enter: { opacity: 0 },
        center: { opacity: 1 },
        exit: { opacity: 0 },
      }
    : {
        enter: (dir: number) => ({ opacity: 0, transform: `translateX(${dir * 28}%)` }),
        center: { opacity: 1, transform: 'translateX(0%)', transitionEnd: { transform: 'none' } },
        exit: (dir: number) => ({ opacity: 0, transform: `translateX(${dir * -16}%)` }),
      };

  const barTitle =
    view.kind === 'line' && browseRoute ? (
      // Both termini wrap instead of truncating: at phone width a long line
      // title ("Városház tér ⇆ Deák Ferenc tér M") would otherwise lose both ends.
      <span className="flex max-w-full flex-wrap items-center justify-center gap-x-2 gap-y-1">
        <RouteBadge size="sm" name={formatRouteDisplayName(browseRoute.shortName, browseRoute.id)} color={browseRoute.color} textColor={browseRoute.textColor} />
        <span className="min-w-0 text-sm">
          {splitRouteTitle(browseRoute.description || browseRoute.shortName).map((part, index) => (
            <Fragment key={index}>
              {index > 0 && (
                <>
                  <RiArrowLeftRightLine className="mx-1 inline-block h-3.5 w-3.5 align-middle text-muted/60" aria-hidden="true" />
                  <span className="sr-only"> to </span>
                </>
              )}
              <span>{part}</span>
            </Fragment>
          ))}
        </span>
      </span>
    ) : view.kind === 'stop' && selectedStop ? (
      selectedStop.name
    ) : view.kind === 'metro' && metroView ? (
      metroViewLabel(metroView)
    ) : (
      'Add tracker'
    );

  const barSubtitle =
    view.kind === 'line'
      ? 'Tick the stops to track'
      : view.kind === 'stop'
        ? 'Pick a line serving this stop'
        : view.kind === 'metro'
          ? 'Pick a stop'
          : undefined;

  const searchValue = browseMode === 'line' ? routeQuery : searchQuery;
  const setSearchValue = browseMode === 'line' ? setRouteQuery : setSearchQuery;

  const renderView = () => {
    if (view.kind === 'line' && browseRoute) {
      return (
        <LineStopPicker
          browseRoute={browseRoute}
          selectedLineStops={selectedLineStops}
          routePolylines={routePolylines}
          routeStops={routeStops}
          previewVehicles={previewVehicles}
          onToggleStop={handleToggleLineStop}
          stopQuery={stopQuery}
          setStopQuery={setStopQuery}
          loadingRouteStops={loadingRouteStops}
          routeStopsError={routeStopsError}
          onRetry={() => void handleRouteSelect(browseRoute)}
          filteredRouteStops={filteredRouteStops}
          routeHeadsigns={routeHeadsigns}
          onDirectionChange={handleLineStopDirectionChange}
        />
      );
    }
    if (view.kind === 'stop' && selectedStop) {
      return (
        <div className={cn('space-y-6 pt-2', SHEET_GUTTER)}>
          <StopRoutePicker
            loadingRoutes={loadingRoutes}
            routesError={routesError}
            onRetry={() => handleStopSelect(selectedStop.id)}
            routes={routes}
            selectedRouteId={selectedRouteId}
            onSelectRoute={(id) => {
              setSelectedRouteId(id);
              setSelectedHeadsign('');
            }}
            existing={existing}
            selectedRoute={selectedRoute}
            selectedHeadsign={selectedHeadsign}
            onSelectHeadsign={setSelectedHeadsign}
            allDeparturesCount={allDeparturesCount}
            directions={directions}
            timeFormat={prefs.timeFormat}
          />
        </div>
      );
    }
    if (view.kind === 'metro' && metroView) {
      return (
        <div className={cn('pt-2', SHEET_GUTTER)}>
          <CuratedStopList group={metroView} onSelectStop={handleStopSelect} />
        </div>
      );
    }
    return (
      <div className={cn('space-y-4 pt-2', SHEET_GUTTER)}>
        {browseMode === 'stop' ? (
          <StopBrowser
            searchQuery={searchQuery}
            filteredStops={filteredStops}
            catalog={catalog}
            catalogError={catalogError}
            onRetryCatalog={retryCatalog}
            onSelectStop={handleStopSelect}
            onOpenGroup={setMetroView}
          />
        ) : (
          <LineBrowser
            routeTypeFilter={routeTypeFilter}
            setRouteTypeFilter={setRouteTypeFilter}
            catalog={catalog}
            catalogError={catalogError}
            onRetryCatalog={retryCatalog}
            filteredRoutes={filteredRoutes}
            routeQuery={routeQuery}
            existing={existing}
            onSelectRoute={(route) => void handleRouteSelect(route)}
          />
        )}
      </div>
    );
  };

  const footer =
    view.kind === 'line' ? (
      <SheetFooter>
        <Button
          type="button"
          size="lg"
          className="h-12 w-full rounded-2xl"
          onClick={handleSaveLineTracker}
          disabled={selectedLineStops.length === 0 || isSaving}
        >
          {isSaving ? (
            <RiLoader4Line className="h-4 w-4 animate-spin" aria-hidden="true" />
          ) : selectedLineStops.length === 0 ? (
            'Tick a stop to track'
          ) : existing.some((t) => isRouteMatch(t.route_id, browseRoute?.id ?? '')) ? (
            `Update line · ${selectedLineStops.length} stop${selectedLineStops.length === 1 ? '' : 's'}`
          ) : (
            `Track line · ${selectedLineStops.length} stop${selectedLineStops.length === 1 ? '' : 's'}`
          )}
        </Button>
      </SheetFooter>
    ) : view.kind === 'stop' ? (
      <SheetFooter>
        <Button
          type="button"
          size="lg"
          className="h-12 w-full rounded-2xl"
          onClick={handleSaveStopTracker}
          disabled={!selectedRouteId || isSaving}
        >
          {isSaving ? (
            <RiLoader4Line className="h-4 w-4 animate-spin" aria-hidden="true" />
          ) : !selectedRoute ? (
            'Pick a line to track'
          ) : (
            `Track ${selectedRoute.shortName} here`
          )}
        </Button>
      </SheetFooter>
    ) : null;

  return (
    <Dialog open={open} onOpenChange={onClose}>
      <DialogContent
        edgeToEdge
        aria-labelledby={titleId}
        className="sm:max-w-lg max-h-[92vh] rounded-[28px] p-0 overflow-hidden flex flex-col gap-0"
      >
        <SheetBar
          leading={view.kind === 'browse' ? 'close' : 'back'}
          onLeading={view.kind === 'browse' ? onClose : goBack}
          onClose={onClose}
          wrapTitle={view.kind === 'line'}
          title={barTitle}
          titleId={titleId}
          subtitle={barSubtitle}
          scrolled={scrolled}
          accessory={
            view.kind === 'browse' ? (
              <div className="space-y-2">
                <Tabs value={browseMode} onValueChange={(v) => setBrowseMode(v as BrowseMode)}>
                  <TabsList aria-label="Browse by" className="grid w-full grid-cols-2">
                    <TabsTrigger value="line">By line</TabsTrigger>
                    <TabsTrigger value="stop">By stop</TabsTrigger>
                  </TabsList>
                </Tabs>
                <Input
                  ref={searchRef}
                  icon={RiSearchLine}
                  value={searchValue}
                  onChange={(e) => setSearchValue(e.target.value)}
                  placeholder={browseMode === 'line' ? 'Search lines… 4, 9, M3, 47' : 'Search stops… Teleki László tér'}
                  aria-label={browseMode === 'line' ? 'Search lines' : 'Search stops'}
                  className={SHEET_INPUT_CLASS}
                />
              </div>
            ) : undefined
          }
        />

        <SheetBody
          ref={scrollRef}
          onScrolledChange={setScrolled}
          fadeBottom={view.kind === 'browse' || view.kind === 'metro'}
          className="pb-6"
        >
          <AnimatePresence mode="popLayout" initial={false} custom={viewDirection}>
            <motion.div
              key={viewKey}
              custom={viewDirection}
              variants={viewVariants}
              initial="enter"
              animate="center"
              exit="exit"
              transition={{ duration: reduceMotion ? 0.2 : 0.34, ease: EASE_DRAWER }}
            >
              {renderView()}
            </motion.div>
          </AnimatePresence>
        </SheetBody>

        {footer}
      </DialogContent>
    </Dialog>
  );
}
