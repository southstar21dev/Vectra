import { Suspense, lazy, useEffect, useId, useMemo, useState } from 'react';
import { RiLoader4Line, RiMapPinLine, RiAddLine, RiRefreshLine, RiSearchLine, RiDeleteBinLine } from '@remixicon/react';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { LoadingOverlay } from '@/components/ui/loading-overlay';
import { Input } from '@/components/ui/input';
import { DeleteRow, FormGroup, SHEET_INPUT_CLASS } from '@/components/ui/sheet-form';
import { SheetBar, SheetBody, SheetFooter, SheetSectionTitle, SHEET_GUTTER } from '@/features/trips/sheet-chrome';
import { toast } from 'sonner';
import { getErrorMessage } from '@/lib/error-utils';
import type { TransitTracker } from './use-transit';
import type { Arrival, FutarRouteVariant, FutarStop, TrackerStop } from './types';
import { fetchCombinedRouteDetails } from './futar-api';
import { RouteBadge } from './route-badge';
import { FormattedRouteTitle } from './formatted-route-title';
import { MapFallback } from './map/map-fallback';
import { DirectionSelect, ListRow, ListStatus, StopCountPill } from './tracker-list-row';

// Lazy: the route preview pulls in maplibre, which must stay out of the eager
// bundle and load only when this dialog shows the map.
const LazyRoutePreviewMap = lazy(() => import('./route-preview-map').then((m) => ({ default: m.RoutePreviewMap })));
import { decodePolyline } from './route-geometry';
import type { LatLon } from './route-geometry';
import { formatRouteDisplayName, getCombinedRouteIds, getTrackerStops, MAX_TRACKER_STOPS, normalizeSearchText, normalizeStopIdToStation, parseStopArrivals, routeTypeMeta } from './transit-utils';
import { useDeleteTransitTracker, useMultiStopArrivals, useUpdateTrackerStops, useVehiclesForRoute } from './use-transit';
import { cn } from '@/lib/utils';

interface EditTrackerDialogProps {
  open: boolean;
  onClose: () => void;
  tracker: TransitTracker | null;
  /** Page-level undoable delete handler; when absent the dialog deletes directly. */
  onDelete?: (tracker: TransitTracker) => Promise<void>;
  /** Called after the close animation finishes; the parent keeps the data prop until then. */
  onExited?: () => void;
}

const MAP_HEIGHT = 208;

export function EditTrackerDialog({ open, onClose, tracker, onDelete, onExited }: EditTrackerDialogProps) {
  const updateTrackerStops = useUpdateTrackerStops();
  const deleteTracker = useDeleteTransitTracker();
  const titleId = useId();

  const [scrolled, setScrolled] = useState(false);
  const [stops, setStops] = useState<TrackerStop[]>([]);
  const [stopSearch, setStopSearch] = useState('');
  const [routeStops, setRouteStops] = useState<FutarStop[]>([]);
  const [routeHeadsigns, setRouteHeadsigns] = useState<string[]>([]);
  const [routePolylines, setRoutePolylines] = useState<LatLon[][]>([]);
  const [routeMapReady, setRouteMapReady] = useState(false);
  const [loadingRoute, setLoadingRoute] = useState(false);
  const [routeError, setRouteError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  // Bumped by the retry button so the route-details effect re-fetches (the
  // effect can't depend on routeError — resetting it would refetch-loop).
  const [reloadNonce, setReloadNonce] = useState(0);

  // Seed the editable stops from the tracker each time the dialog opens or is
  // retargeted — adjusted during render so the previous tracker's list can
  // never paint for a frame.
  const [prevSync, setPrevSync] = useState<{ open: boolean; tracker: TransitTracker | null }>({
    open: false,
    tracker: null,
  });

  if (open !== prevSync.open || tracker !== prevSync.tracker) {
    setPrevSync({ open, tracker });
    if (open && tracker) {
      setScrolled(false);
      setStops(getTrackerStops(tracker));
      setStopSearch('');
      setRoutePolylines([]);
      setRouteMapReady(false);
      setLoadingRoute(true);
      setRouteError(null);
      setDeleting(false);
      setReloadNonce(0);
    }
  }

  // Fetch the route's stops/headsigns/polylines for the current tracker.
  useEffect(() => {
    if (!open || !tracker) return;

    // Ignore completions from a previous open/tracker — the dialog may be
    // reused for a different tracker before the fetch resolves.
    let alive = true;

    void (async () => {
      try {
        const res = await fetchCombinedRouteDetails(tracker.route_id);
        if (!alive) return;
        const refs = res.data?.references?.stops ?? {};
        const variants: FutarRouteVariant[] = res.data?.entry?.variants ?? [];

        const headsignSet = new Set<string>();
        for (const v of variants) {
          if (v.headsign) headsignSet.add(v.headsign);
        }
        setRouteHeadsigns(Array.from(headsignSet));

        const polylines: LatLon[][] = [];
        for (const v of variants) {
          const pts = v.polyline?.points ? decodePolyline(v.polyline.points) : [];
          if (pts.length >= 2) polylines.push(pts);
        }
        if (!alive) return;
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
        if (!alive) return;
        setRouteStops(stopsOut);
      } catch (err) {
        if (!alive) return;
        console.warn('Failed to load route details for editing:', err);
        setRouteError(getErrorMessage(err, 'Failed to load route stops'));
      } finally {
        if (alive) setLoadingRoute(false);
      }
    })();

    return () => {
      alive = false;
    };
  }, [open, tracker, reloadNonce]);

  const trackedStopIds = useMemo(() => new Set(stops.map((s) => s.stop_id)), [stops]);

  const availableStops = useMemo(() => {
    const q = normalizeSearchText(stopSearch.trim());
    return routeStops.filter((s) => {
      if (trackedStopIds.has(s.id)) return false;
      if (!q) return true;
      return normalizeSearchText(s.name).includes(q);
    });
  }, [routeStops, trackedStopIds, stopSearch]);

  // Live vehicles on this line — polled while the edit dialog is open.
  const vehiclesQuery = useVehiclesForRoute(tracker?.route_id ?? null);

  // Arrivals at the tracked stops — the earliest departure's tripId is THE
  // vehicle the user will take; every other vehicle on the line is noise and
  // gets filtered out below.
  const trackedStopIdList = useMemo(() => stops.map((s) => s.stop_id), [stops]);
  const previewArrivals = useMultiStopArrivals(trackedStopIdList);
  const relevantTripIds = useMemo(() => {
    const ids = new Set<string>();
    const routeId = tracker?.route_id ?? '';
    if (!routeId) return ids;
    const members = getCombinedRouteIds(routeId);
    const memberIds = members && members.length > 1 ? members : [routeId];
    for (const stop of stops) {
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
  }, [stops, previewArrivals, tracker]);

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

  if (!tracker) return null;

  const displayName = formatRouteDisplayName(tracker.route_short_name, tracker.route_id);

  const handleRemoveStop = (stopId: string) => {
    setStops((prev) => prev.filter((s) => s.stop_id !== stopId));
  };

  const handleAddStop = (stop: FutarStop) => {
    if (stops.length >= MAX_TRACKER_STOPS) {
      toast.warning('Maximum 4 stops', {
        description: `You can track up to ${MAX_TRACKER_STOPS} stops on this line.`,
      });
      return;
    }
    setStops((prev) => [...prev, { stop_id: stop.id, stop_name: stop.name, headsign: '' }]);
  };

  const handleDirectionChange = (stopId: string, headsign: string) => {
    setStops((prev) =>
      prev.map((s) => (s.stop_id === stopId ? { ...s, headsign } : s))
    );
  };

  const handleSave = async () => {
    if (stops.length === 0) return;
    try {
      await updateTrackerStops.mutateAsync({
        id: tracker.id,
        stops,
      });
      toast.success('Tracker updated');
      onClose();
    } catch (err) {
      toast.error('Failed to update tracker', { description: getErrorMessage(err) });
    }
  };

  // Deletion itself is delegated to the page-level handler (which owns the
  // undo toast); the two-tap guard lives in DeleteRow.
  const handleDeleteTracker = async () => {
    setDeleting(true);
    try {
      if (onDelete) {
        await onDelete(tracker);
      } else {
        await deleteTracker.mutateAsync(tracker.id);
        toast.success('Tracker removed');
      }
      onClose();
    } catch (err) {
      toast.error('Failed to remove tracker', { description: getErrorMessage(err) });
    } finally {
      setDeleting(false);
    }
  };

  const isAtStopLimit = stops.length >= MAX_TRACKER_STOPS;
  const saving = updateTrackerStops.isPending;

  return (
    <Dialog open={open} onOpenChange={onClose} onExited={onExited}>
      <DialogContent
        edgeToEdge
        aria-labelledby={titleId}
        className="sm:max-w-lg max-h-[92vh] rounded-[28px] p-0 overflow-hidden flex flex-col gap-0"
      >
        <SheetBar
          leading="close"
          onLeading={onClose}
          titleId={titleId}
          title={
            <span className="inline-flex max-w-full items-center gap-2">
              <RouteBadge size="sm" name={displayName} color={tracker.route_color} textColor={tracker.route_text_color} />
              <span className="min-w-0 truncate">
                <FormattedRouteTitle title={tracker.route_long_name || displayName} />
              </span>
            </span>
          }
          subtitle={routeTypeMeta(tracker.route_type).label}
          scrolled={scrolled}
        />

        <SheetBody onScrolledChange={setScrolled} fadeBottom={false} className="pb-6">
          {/* The route leads, edge to edge; tap a dot to track or untrack it. */}
          <div className="relative" style={{ height: MAP_HEIGHT }}>
            <Suspense fallback={<MapFallback />}>
              <LazyRoutePreviewMap
                polylines={routePolylines}
                stops={routeStops.map((s) => ({
                  id: s.id,
                  name: s.name,
                  lat: s.lat,
                  lon: s.lon,
                  selected: trackedStopIds.has(s.id),
                }))}
                vehicles={previewVehicles}
                routeColor={tracker.route_color || '64748b'}
                height={MAP_HEIGHT}
                onReady={() => setRouteMapReady(true)}
                onSelectStop={(stopId) => {
                  if (trackedStopIds.has(stopId)) {
                    handleRemoveStop(stopId);
                  } else {
                    const stop = routeStops.find((s) => s.id === stopId);
                    if (stop) handleAddStop(stop);
                  }
                }}
              />
            </Suspense>
            <LoadingOverlay show={loadingRoute || !routeMapReady} label="Loading route map…" />
            <div aria-hidden className="pointer-events-none absolute inset-x-0 bottom-0 h-12 bg-gradient-to-t from-surface-secondary to-transparent" />
          </div>

          <div className={cn('space-y-6 pt-4', SHEET_GUTTER)}>
            <section className="space-y-3">
              <SheetSectionTitle action={<StopCountPill count={stops.length} />}>Tracked stops</SheetSectionTitle>
              <FormGroup className="overflow-hidden">
                {stops.map((stop) => (
                  <div key={stop.stop_id} className="flex items-start gap-3 px-4 py-3">
                    <RiMapPinLine className="mt-2 h-4 w-4 shrink-0 text-tint-blue" aria-hidden="true" />
                    <div className="min-w-0 flex-1 space-y-2">
                      <p className="truncate pt-1.5 text-sm font-semibold text-primary">{stop.stop_name}</p>
                      {routeHeadsigns.length > 0 && (
                        <DirectionSelect
                          value={stop.headsign || ''}
                          headsigns={routeHeadsigns}
                          onChange={(h) => handleDirectionChange(stop.stop_id, h)}
                          label={`Direction at ${stop.stop_name}`}
                        />
                      )}
                    </div>
                    <button
                      type="button"
                      onClick={() => handleRemoveStop(stop.stop_id)}
                      aria-label={`Stop tracking ${stop.stop_name}`}
                      className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-muted transition-colors hover:bg-rose-500/10 hover:text-tint-red cursor-pointer active:scale-95 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary"
                    >
                      <RiDeleteBinLine className="h-4 w-4" aria-hidden="true" />
                    </button>
                  </div>
                ))}
                {stops.length === 0 && (
                  <p className="px-4 py-5 text-center text-sm text-muted">No stops tracked. Add one below, or delete the tracker.</p>
                )}
              </FormGroup>
            </section>

            <section className="space-y-3">
              <SheetSectionTitle>Add a stop</SheetSectionTitle>
              {isAtStopLimit ? (
                <p className="px-1 text-xs text-muted">That’s the most stops a line can track. Remove one above to add another.</p>
              ) : (
                <>
                  <Input
                    icon={RiSearchLine}
                    value={stopSearch}
                    onChange={(e) => setStopSearch(e.target.value)}
                    placeholder={`Search stops on ${displayName}…`}
                    aria-label={`Search stops on ${displayName}`}
                    className={SHEET_INPUT_CLASS}
                  />
                  {loadingRoute ? (
                    <ListStatus role="status" aria-label="Loading stops" className="py-6">
                      <RiLoader4Line className="h-5 w-5 animate-spin text-muted" aria-hidden="true" />
                    </ListStatus>
                  ) : routeError ? (
                    <ListStatus className="py-6">
                      <p className="max-w-sm text-sm text-tint-red">{routeError}</p>
                      <Button
                        variant="outline"
                        size="sm"
                        className="rounded-full"
                        onClick={() => {
                          setRouteError(null);
                          setLoadingRoute(true);
                          setReloadNonce((n) => n + 1);
                        }}
                      >
                        <RiRefreshLine className="h-3.5 w-3.5" aria-hidden="true" /> Retry
                      </Button>
                    </ListStatus>
                  ) : availableStops.length > 0 ? (
                    <FormGroup className="overflow-hidden">
                      {availableStops.slice(0, 20).map((stop) => (
                        <ListRow
                          key={stop.id}
                          onClick={() => handleAddStop(stop)}
                          title={stop.name}
                          trailing={<RiAddLine className="h-4 w-4 text-accent-primary" aria-hidden="true" />}
                        />
                      ))}
                    </FormGroup>
                  ) : (
                    <ListStatus className="py-6">
                      <p className="text-sm text-muted">
                        {stopSearch.trim() ? <>No stops match “{stopSearch.trim()}”</> : 'Every stop on this line is already tracked.'}
                      </p>
                    </ListStatus>
                  )}
                </>
              )}
            </section>

            <DeleteRow label="Delete tracker" onConfirm={handleDeleteTracker} disabled={saving} busy={deleting || deleteTracker.isPending} />
          </div>
        </SheetBody>

        <SheetFooter>
          <Button type="button" size="lg" className="h-12 w-full rounded-2xl" onClick={handleSave} disabled={stops.length === 0 || saving || deleting}>
            {saving ? <RiLoader4Line className="h-4 w-4 animate-spin" aria-hidden="true" /> : stops.length === 0 ? 'Keep at least one stop' : 'Save changes'}
          </Button>
        </SheetFooter>
      </DialogContent>
    </Dialog>
  );
}
