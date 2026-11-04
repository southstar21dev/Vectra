import { Suspense, lazy, useCallback, useEffect, useId, useMemo, useState, useRef } from 'react';
import { RiArrowLeftRightLine, RiArrowRightLine, RiRefreshLine, RiWifiOffLine } from '@remixicon/react';
import { LoadingOverlay } from '@/components/ui/loading-overlay';
import { FormGroup } from '@/components/ui/sheet-form';
import { SheetBar, SheetBody, SheetSectionTitle, SHEET_GUTTER } from '@/features/trips/sheet-chrome';
import { TrackedStopTabs } from '../tracker-list-row';
import { cn } from '@/lib/utils';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import type { TransitTracker } from '../use-transit';
import { RouteBadge } from '../route-badge';
import { MapFallback } from '../map/map-fallback';

// Lazy: RouteMap pulls in maplibre, kept out of the eager bundle.
const LazyRouteMap = lazy(() => import('../route-map').then((m) => ({ default: m.RouteMap })));
import type { RouteMapSecondaryLine, RouteMapStop, RouteMapVehicle } from '../map/types';
import { FullscreenMapDialog } from '../fullscreen-map-dialog';
import { AlertsSection } from '../alerts-banner';
import {
  approximateVehiclePosition,
  matchingVehicles,
  resolveTrackedStopIndex,
  routeProgress,
  trackedTripIds,
} from '../live-position';
import { decodePolyline } from '../route-geometry';
import {
  formatRouteDisplayName,
  getCatalogRoutes,
  getTrackerStops,
  isHeadsignMatch,
  parseAlerts,
  parseStopArrivals,
  routeTypeMeta,
  followingArrivals,
} from '../transit-utils';
import { useNow, useRouteDetails, useStopArrivals, useTripDetails, useVehiclesForRoute } from '../use-transit';
import type { Arrival } from '../types';
import { DirectionStrip, type DirectionView } from './direction-strip';
import { DirectionStopList } from './direction-stop-list';

/** Live-vehicle poll cadence — keep in sync with useVehiclesForRoute's refetchInterval. */
const VEHICLE_POLL_MS = 10_000;

interface LivePositionDialogProps {
  open: boolean;
  tracker: TransitTracker | null;
  onClose: () => void;
  /** Called after the close animation finishes; the parent keeps the data prop until then. */
  onExited?: () => void;
}

export function LivePositionDialog({ open, tracker, onClose, onExited }: LivePositionDialogProps) {
  const now = useNow(1000);
  const titleId = useId();
  const [scrolled, setScrolled] = useState(false);

  const trackerStops = useMemo(() => (tracker ? getTrackerStops(tracker) : []), [tracker]);
  const [activeStopId, setActiveStopId] = useState<string>('');
  // Retarget the active stop whenever the dialog's tracker changes —
  // adjusted during render so the previous tracker's stop can never paint.
  const [prevTrackerForStop, setPrevTrackerForStop] = useState<TransitTracker | null | undefined>(undefined);
  if (tracker !== prevTrackerForStop) {
    setPrevTrackerForStop(tracker);
    if (!tracker) {
      setActiveStopId('');
    } else {
      const stops = getTrackerStops(tracker);
      const matched = stops.find((s) => s.stop_id === tracker.stop_id);
      setActiveStopId(matched?.stop_id || stops[0]?.stop_id || tracker.stop_id);
    }
  }


  const activeStop = useMemo(() => {
    if (!tracker) return null;
    const found = trackerStops.find((s) => s.stop_id === activeStopId);
    return found || trackerStops[0] || { stop_id: tracker.stop_id, stop_name: tracker.stop_name, headsign: tracker.headsign || '' };
  }, [tracker, trackerStops, activeStopId]);


  const arrivalsQuery = useStopArrivals(activeStop?.stop_id ?? null);
  const vehiclesQuery = useVehiclesForRoute(tracker?.route_id ?? null);

  // Route-id → short-name fallback for alert matching: the arrivals response's
  // references.routes covers the common case; the runtime catalog JSON fills
  // the gaps (e.g. tram 51: BKK_VP51 vs BKK_3510).
  const [catalogMap, setCatalogMap] = useState<Map<string, { shortName: string; type: string }> | null>(null);
  useEffect(() => {
    let alive = true;
    void getCatalogRoutes().then(
      (m) => {
        if (alive) setCatalogMap(m);
      },
      () => {
        // references.routes remains the fallback when the catalog can't load.
      }
    );
    return () => {
      alive = false;
    };
  }, []);

  // Disruption alerts attached to this stop (embedded in the arrivals feed).
  const alerts = useMemo(
    () =>
      tracker && activeStop
        ? parseAlerts(
            arrivalsQuery.data,
            {
              routeId: tracker.route_id,
              routeShortName: tracker.route_short_name,
              routeType: tracker.route_type,
              stopId: activeStop.stop_id,
            },
            now,
            catalogMap ? (id) => catalogMap.get(id)?.shortName : undefined,
            catalogMap ? (id) => catalogMap.get(id)?.type : undefined
          )
        : [],
    [arrivalsQuery.data, now, tracker, activeStop, catalogMap]
  );

  // Next arrival per tracked direction → the trips we're following.
  const arrivals = useMemo(
    () =>
      tracker && activeStop
        ? parseStopArrivals(arrivalsQuery.data, tracker.route_id, activeStop.headsign || null)
        : [],
    [arrivalsQuery.data, tracker, activeStop]
  );
  const tripIds = useMemo(() => trackedTripIds(arrivals), [arrivals]);

  // Live markers: vehicles whose trip matches a tracked arrival.
  const vehicles = useMemo(
    () => matchingVehicles(vehiclesQuery.data?.data?.list ?? [], tripIds),
    [vehiclesQuery.data, tripIds]
  );

  // For an all-directions tracker (no headsign) the arrivals resolve up to
  // three trips — one per direction plus short-turn variants — and ALL are
  // tracked (strip + map). The LEAD direction (map emphasis + first strip) is
  // the one with a live vehicle when there is one, else the soonest arrival.
  const hasTrip = tripIds.length > 0;
  const leadTripId = useMemo(() => {
    if (tripIds.length <= 1) return tripIds[0] ?? null;
    const hasVehicle = (id: string | null) => id != null && vehicles.some((v) => v.tripId === id);
    return tripIds.find((id) => hasVehicle(id)) ?? tripIds[0];
  }, [tripIds, vehicles]);
  const otherTripId = useMemo(
    () => (tripIds.length > 1 ? tripIds.find((id) => id !== leadTripId) ?? null : null),
    [tripIds, leadTripId]
  );
  const thirdTripId = useMemo(
    () => (tripIds.length > 2 ? tripIds.find((id) => id !== leadTripId && id !== otherTripId) ?? null : null),
    [tripIds, leadTripId, otherTripId]
  );

  // Geometry sources. Prefer the tracked trips; when no departure resolves a
  // trip OR the fork's trip-details 404s (nightly service-date roll — it only
  // serves yesterday's trips until the server catches up, e.g. 100E after
  // Budapest midnight), fall back to the route's variants so the map still
  // draws. The route fallback has no per-trip schedule (no live/approx vehicle)
  // but keeps the line, stops and tracked stop visible.
  const tripQueryA = useTripDetails(hasTrip ? leadTripId : null);
  const tripQueryB = useTripDetails(hasTrip && otherTripId ? otherTripId : null);
  const tripQueryC = useTripDetails(hasTrip && thirdTripId ? thirdTripId : null);
  // Always fetch the route-level geometry too — it's the fallback whenever
  // trip geometry isn't available (no departure, trip-details 404ing on the
  // nightly service-date roll, or a slow/hung trip request). The trip entry
  // wins in `directions` when both are present, so this is free when healthy.
  const routeQuery = useRouteDetails(tracker?.route_id ?? null);
  const stopRefs = useMemo(() => routeQuery.data?.data?.references?.stops ?? {}, [routeQuery.data]);

  // Direction-aware fallback geometry: matches the variant (stop sequence + polyline)
  // that corresponds to each arrival's specific direction/headsign when trip-details
  // is unavailable (e.g. nightly roll, 404, or network delay).
  const getVariantEntryForArrival = useCallback(
    (arr: Arrival | null, fallbackIdx: number = 0) => {
      const variants = routeQuery.data?.data?.entry?.variants ?? [];
      if (variants.length === 0) return undefined;
      const refs = routeQuery.data?.data?.references?.stops ?? {};
      const trackedId = activeStop?.stop_id || tracker?.stop_id;

      if (arr) {
        const byHeadsignAndStop = variants.find((v) => {
          const matches =
            isHeadsignMatch(v.headsign, arr.headSign, arr.tripHeadsign) ||
            (v.name && isHeadsignMatch(v.name, arr.headSign, arr.tripHeadsign));
          if (!matches) return false;
          if (!trackedId) return true;
          const ids = v.stopIds ?? [];
          return ids.includes(trackedId) || ids.some((id) => refs[id]?.parentStationId === trackedId);
        });
        if (byHeadsignAndStop) {
          return {
            stopTimes: (byHeadsignAndStop.stopIds ?? []).map((id) => ({ stopId: id })),
            polyline: byHeadsignAndStop.polyline,
          };
        }

        const byHeadsign = variants.find(
          (v) =>
            isHeadsignMatch(v.headsign, arr.headSign, arr.tripHeadsign) ||
            (v.name && isHeadsignMatch(v.name, arr.headSign, arr.tripHeadsign))
        );
        if (byHeadsign) {
          return {
            stopTimes: (byHeadsign.stopIds ?? []).map((id) => ({ stopId: id })),
            polyline: byHeadsign.polyline,
          };
        }
      }

      if (trackedId) {
        const matching = variants.filter((v) => {
          const ids = v.stopIds ?? [];
          return ids.includes(trackedId) || ids.some((id) => refs[id]?.parentStationId === trackedId);
        });
        if (matching.length > 0) {
          const v = matching[fallbackIdx % matching.length];
          return {
            stopTimes: (v.stopIds ?? []).map((id) => ({ stopId: id })),
            polyline: v.polyline,
          };
        }
      }

      const v = variants[fallbackIdx % variants.length] ?? variants[0];
      return {
        stopTimes: (v.stopIds ?? []).map((id) => ({ stopId: id })),
        polyline: v.polyline,
      };
    },
    [routeQuery.data, tracker, activeStop]
  );

  const trackedStopIdsSet = useMemo(() => new Set(trackerStops.map((s) => s.stop_id)), [trackerStops]);

  // One DirectionView per tracked direction (or the route fallback), each with
  // its own stops, geometry, vehicles and per-direction progress. The tracked
  // stop resolves against each direction's own stop list.
  const directions = useMemo<DirectionView[]>(() => {
    const out: DirectionView[] = [];
    const push = (
      entry:
        | {
            stopTimes?: Array<{ stopId: string; stopHeadsign?: string | null; departureTime?: number; shapeDistTraveled?: number }>;
            polyline?: { points?: string };
          }
        | undefined,
      refs: Record<string, { name?: string; lat?: number; lon?: number; parentStationId?: string } | undefined>,
      tripId: string | null,
      dirVehicles: RouteMapVehicle[],
      arrival: Arrival | null
    ) => {
      const stopTimes = entry?.stopTimes ?? [];
      if (stopTimes.length === 0) return;
      const trackedIndex = resolveTrackedStopIndex(
        stopTimes.map((st) => st.stopId),
        refs,
        activeStop?.stop_id ?? null
      );
      const stops: RouteMapStop[] = stopTimes.map((st, i) => {
        const ref = refs[st.stopId];
        const isThisTracked =
          trackedStopIdsSet.has(st.stopId) ||
          (ref?.parentStationId != null && trackedStopIdsSet.has(ref.parentStationId));
        return {
          name: ref?.name ?? st.stopHeadsign ?? st.stopId,
          lat: ref?.lat ?? 0,
          lon: ref?.lon ?? 0,
          isTracked: isThisTracked || i === trackedIndex,
          isSelected: i === trackedIndex,
        };
      });
      const polyline = entry?.polyline?.points ? decodePolyline(entry.polyline.points) : [];
      out.push({
        tripId,
        stops,
        polyline,
        stopTimes: entry?.stopTimes ?? [],
        vehicles: dirVehicles,
        arrival,
        upcoming: followingArrivals(arrivals, arrival, 3),
        trackedIndex,
        progress: routeProgress(stops, polyline, dirVehicles[0] ?? null, trackedIndex),
      });
    };

    if (hasTrip) {
      if (leadTripId) {
        const arr = arrivals.find((a) => a.tripId === leadTripId) ?? null;
        push(
          tripQueryA.data?.data?.entry ?? getVariantEntryForArrival(arr, 0),
          tripQueryA.data?.data?.references?.stops ?? stopRefs,
          leadTripId,
          vehicles
            .filter((v) => v.tripId === leadTripId)
            .map((v) => ({ vehicleId: v.vehicleId, location: v.location, bearing: v.bearing })),
          arr
        );
      }
      if (otherTripId) {
        const arr = arrivals.find((a) => a.tripId === otherTripId) ?? null;
        push(
          tripQueryB.data?.data?.entry ?? getVariantEntryForArrival(arr, 1),
          tripQueryB.data?.data?.references?.stops ?? stopRefs,
          otherTripId,
          vehicles
            .filter((v) => v.tripId === otherTripId)
            .map((v) => ({ vehicleId: v.vehicleId, location: v.location, bearing: v.bearing })),
          arr
        );
      }
      if (thirdTripId) {
        const arr = arrivals.find((a) => a.tripId === thirdTripId) ?? null;
        push(
          tripQueryC.data?.data?.entry ?? getVariantEntryForArrival(arr, 2),
          tripQueryC.data?.data?.references?.stops ?? stopRefs,
          thirdTripId,
          vehicles
            .filter((v) => v.tripId === thirdTripId)
            .map((v) => ({ vehicleId: v.vehicleId, location: v.location, bearing: v.bearing })),
          arr
        );
      }
    } else {
      const variants = routeQuery.data?.data?.entry?.variants ?? [];
      if (variants.length > 1) {
        for (let idx = 0; idx < Math.min(2, variants.length); idx++) {
          const entry = {
            stopTimes: (variants[idx].stopIds ?? []).map((id) => ({ stopId: id })),
            polyline: variants[idx].polyline,
          };
          push(entry, stopRefs, null, [], null);
        }
      } else {
        push(getVariantEntryForArrival(null, 0), stopRefs, null, [], null);
      }
    }
    return out;
  }, [hasTrip, leadTripId, otherTripId, thirdTripId, tripQueryA.data, tripQueryB.data, tripQueryC.data, getVariantEntryForArrival, stopRefs, activeStop, vehicles, arrivals, trackedStopIdsSet, routeQuery.data]);

  // DEV diagnostic — confirms the route-level fallback is what's rendering
  // when trip-details can't serve the current service date (nightly roll).
  const fallbackWarnedTripRef = useRef<string | null>(null);
  useEffect(() => {
    if (
      import.meta.env.DEV &&
      hasTrip &&
      leadTripId &&
      directions.length > 0 &&
      tripQueryA.isFetched &&
      !tripQueryA.data?.data?.entry &&
      fallbackWarnedTripRef.current !== leadTripId
    ) {
      fallbackWarnedTripRef.current = leadTripId;
      console.warn(`[transit] trip-details unavailable for ${leadTripId} — showing route-level fallback geometry`);
    }
  }, [hasTrip, leadTripId, directions.length, tripQueryA.isFetched, tripQueryA.data]);

  // Approximate metro positions: schedule interpolation when the tracked trip
  // has no live vehicle. Recomputed on a 10s clock (the vehicles poll cadence)
  // so markers move in step with live vehicles and the map's line/stops layers
  // keep stable refs. The clock is used rather than the vehicles query's
  // timestamp, which stays 0 until that query first succeeds.
  const approxNowMs = Math.floor(now / 10_000) * 10_000;
  const enriched = useMemo(
    () =>
      directions.map((d) => {
        const location =
          tracker?.route_type === 'SUBWAY' && d.vehicles.length === 0 && d.polyline.length >= 2
            ? approximateVehiclePosition({
                stopTimes: d.stopTimes,
                polyline: d.polyline,
                nowMs: approxNowMs,
                delayMs: 0,
              })
            : null;
        const approxVehicle: RouteMapVehicle | null = location
          ? { vehicleId: d.tripId ?? 'approx', location, isApproximate: true }
          : null;
        return {
          ...d,
          approxVehicle,
          progress: routeProgress(
            d.stops,
            d.polyline,
            approxVehicle ? { location: approxVehicle.location } : (d.vehicles[0] ?? null),
            d.trackedIndex
          ),
        };
      }),
    [directions, tracker, approxNowMs]
  );

  // The map's secondary lines: every direction beyond the lead.
  // Preserves ALL directions and vehicles so opposite-direction and
  // alternate-trip live beacons always appear on the map.
  const secondaryLines = useMemo<RouteMapSecondaryLine[]>(() => {
    if (enriched.length < 2) return [];
    return enriched.slice(1).map((d) => ({
      stops: d.stops,
      polyline: d.polyline.length >= 2 ? d.polyline : (enriched[0]?.polyline ?? []),
      vehicles: d.approxVehicle ? [...d.vehicles, d.approxVehicle] : d.vehicles,
    }));
  }, [enriched]);

  // Freeze stopsLeft per direction while dialog is open.
  // The stop numbers and visualizer beads remain static during a session,
  // refreshing only when the user closes and re-opens the dialog. Reset and
  // backfill happen during render so a closed/retargeted dialog can never
  // paint the previous session's frozen values.
  const [frozenStops, setFrozenStops] = useState<Record<string, number | null>>({});
  const [prevFrozenKey, setPrevFrozenKey] = useState<string | null>(null);
  // Keyed by stop as well: trip ids and direction indexes repeat across the
  // tabs of a multi-stop tracker.
  const frozenKey = tracker ? `${tracker.id}:${activeStop?.stop_id ?? ''}` : null;
  if (frozenKey !== prevFrozenKey) {
    setPrevFrozenKey(frozenKey);
    setFrozenStops({});
  } else if (tracker && enriched.length > 0) {
    let changed = false;
    const next: Record<string, number | null> = { ...frozenStops };
    enriched.forEach((d, i) => {
      const key = d.tripId ?? `dir-${i}`;
      if (next[key] === undefined && d.progress.stopsLeft != null) {
        next[key] = d.progress.stopsLeft;
        changed = true;
      }
    });
    if (changed) setFrozenStops(next);
  }

  // The mini map mirrors the fullscreen map: full route, all directions and
  // all vehicles; only the viewport size differs.

  // "Show all stops" reveals the full per-direction lists (opt-in — the strip
  // is the default so the dialog stays compact).
  const [showAll, setShowAll] = useState(false);
  const [prevTrackerId, setPrevTrackerId] = useState<string | undefined>(undefined);
  if (tracker?.id !== prevTrackerId) {
    setPrevTrackerId(tracker?.id);
    if (showAll) setShowAll(false);
  }

  // Full-viewport map overlay (fresh RouteMap instance — see FullscreenMapDialog).
  const [showFullscreen, setShowFullscreen] = useState(false);
  const [mapReady, setMapReady] = useState(false);
  const isOpen = open;
  const [prevIsOpen, setPrevIsOpen] = useState(isOpen);

  // The dialog component stays mounted while DialogContent unmounts, so reset
  // readiness on every closed→open transition — otherwise the remounted map
  // skips the cover and flashes. Adjusted during render so the stale value
  // cannot paint.
  if (isOpen !== prevIsOpen) {
    setPrevIsOpen(isOpen);
    if (isOpen) {
      setMapReady(false);
      setScrolled(false);
    }
  }

  // Error out only when the geometry source failed with nothing to render (a
  // vehicles-for-route failure alone still draws the line with the hint; an
  // arrivals failure still shows the route-level fallback map).
  const feedError =
    (arrivalsQuery.isError && directions.length === 0) ||
    ((hasTrip ? tripQueryA.isError : routeQuery.isError) && directions.length === 0);
  const noDepartures = arrivalsQuery.isSuccess && arrivals.length === 0;
  const arrivalsUnavailable = arrivalsQuery.isError;
  const loading = !feedError && directions.length === 0;
  const routeEmoji = routeTypeMeta(tracker?.route_type ?? null).emoji;
  const routeColor = tracker?.route_color || '3b82f6';

  // Countdown to the next live-vehicle poll — resets whenever new data lands.
  const secondsToNextUpdate = useMemo(() => {
    const updated = vehiclesQuery.dataUpdatedAt;
    if (!updated) return null;
    return Math.max(0, Math.ceil((updated + VEHICLE_POLL_MS - now) / 1000));
  }, [vehiclesQuery.dataUpdatedAt, now]);

  const MAP_HEIGHT = 264;
/** Inset the map by the sheet gutter (SHEET_GUTTER is padding; this is the margin twin). */
const SHEET_GUTTER_MX = 'mx-5 sm:mx-6';

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()} onExited={onExited}>
      {tracker && (
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
                <RouteBadge size="sm" name={formatRouteDisplayName(tracker.route_short_name, tracker.route_id)} color={tracker.route_color} textColor={tracker.route_text_color} />
                <span className="min-w-0 truncate">{activeStop?.stop_name || tracker.stop_name}</span>
              </span>
            }
            subtitle={
              <span className="inline-flex max-w-full items-center gap-1.5">
                {activeStop?.headsign ? (
                  <>
                    <RiArrowRightLine className="h-3 w-3 shrink-0 text-muted" aria-hidden />
                    <span className="min-w-0 truncate">{activeStop.headsign}</span>
                  </>
                ) : (
                  <>
                    <RiArrowLeftRightLine className="h-3 w-3 shrink-0 text-muted" aria-hidden />
                    All directions
                  </>
                )}
              </span>
            }
            scrolled={scrolled}
            accessory={
              trackerStops.length > 1 ? (
                <TrackedStopTabs stops={trackerStops} activeStopId={activeStop?.stop_id ?? activeStopId} onSelect={setActiveStopId} />
              ) : undefined
            }
          />

          <SheetBody onScrolledChange={setScrolled} className="pb-6">
            {/* Service alerts lead, above the map. */}
            <AlertsSection alerts={alerts} className={cn('mt-0 mb-3', SHEET_GUTTER_MX)} />

            {/* The route leads with the live vehicles on it; the departures card
                rests on its lower edge. */}
            {/* Same soft-radius finish as the trip sheet's preview map: a
                rounded, bordered map inset by the gutter, with nothing fading
                over its lower edge. */}
            <div className={cn('relative isolate overflow-hidden rounded-3xl border border-subtle bg-surface-overlay', SHEET_GUTTER_MX)} style={{ height: MAP_HEIGHT }}>
              {feedError ? (
                <div className="flex h-full flex-col items-center justify-center gap-1.5 bg-surface-overlay p-4">
                  <RiWifiOffLine className="h-5 w-5 text-tint-red/70" aria-hidden />
                  <span className="text-sm font-semibold text-secondary">Live feed unavailable</span>
                  <span className="text-xs text-muted">Retrying automatically…</span>
                </div>
              ) : (
                <>
                  <Suspense fallback={<MapFallback />}>
                    <LazyRouteMap
                      stops={enriched[0]?.stops ?? []}
                      polyline={enriched[0]?.polyline ?? []}
                      vehicles={
                        enriched[0]?.approxVehicle
                          ? [...(enriched[0].vehicles ?? []), enriched[0].approxVehicle]
                          : (enriched[0]?.vehicles ?? [])
                      }
                      secondaryLines={secondaryLines}
                      routeColor={routeColor}
                      height={MAP_HEIGHT}
                      onFullscreen={() => setShowFullscreen(true)}
                      onReady={() => setMapReady(true)}
                      onLoadError={() => setMapReady(true)}
                    />
                  </Suspense>
                  {(noDepartures || arrivalsUnavailable) && (
                    // Clear of the departures card, which overlaps the map's lower 48px.
                    <div className="pointer-events-none absolute inset-x-0 bottom-16 z-20 flex justify-center">
                      <span className="rounded-full border border-amber-400/30 bg-black/75 px-3 py-1 text-xs font-semibold text-amber-300 shadow-lg">
                        {arrivalsUnavailable ? 'Departure times unavailable · showing route' : 'No upcoming departures right now'}
                      </span>
                    </div>
                  )}
                </>
              )}
              <LoadingOverlay show={!feedError && (loading || !mapReady)} label="Loading route…" />
              {/* Fades the map into the sheet behind the card, as in the trip sheet. */}
              <div aria-hidden className="pointer-events-none absolute inset-x-0 bottom-0 z-10 h-24 bg-gradient-to-t from-surface-secondary/90 via-surface-secondary/40 to-transparent" />
            </div>

            <div className={cn('relative z-30 -mt-12 space-y-6', SHEET_GUTTER)}>
              {/* One card per tracked direction (all are tracked for an
                  all-directions tracker): destination, countdown and the
                  live progress of the vehicle towards this stop. */}
              {directions.length > 0 && (
                <section aria-label="Next departures" className="relative isolate mx-2">
                  {/* Haze behind the lead card so it melts into the map rather
                      than sitting on it as a hard-edged slab. */}
                  <div aria-hidden className="pointer-events-none absolute -inset-x-4 -top-5 -z-10 h-36 rounded-[2.5rem] bg-surface-secondary/70 blur-2xl" />
                  <div className="space-y-3">
                    {enriched.map((d, i) => (
                      <DirectionStrip
                        key={d.tripId ?? 'route'}
                        direction={d}
                        routeName={tracker.route_short_name}
                        routeId={tracker.route_id}
                        routeColor={routeColor}
                        routeTextColor={tracker.route_text_color}
                        now={now}
                        accent={i === 0 ? 'emerald' : 'violet'}
                        lead={i === 0}
                        frozenStopsLeft={frozenStops[d.tripId ?? `dir-${i}`]}
                      />
                    ))}
                  </div>
                </section>
              )}

              {directions.length > 0 && (
                <section aria-labelledby={`${titleId}-stops`} className="space-y-3">
                  <SheetSectionTitle
                    id={`${titleId}-stops`}
                    action={
                      <button
                        type="button"
                        onClick={() => setShowAll((v) => !v)}
                        aria-expanded={showAll}
                        className="inline-flex h-9 items-center rounded-full bg-surface-overlay px-3.5 text-sm font-bold text-primary transition-colors hover:bg-surface-tertiary cursor-pointer active:scale-95 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary"
                      >
                        {showAll ? 'Hide' : directions.length === 1 ? `Show all ${directions[0].stops.length}` : 'Show all'}
                      </button>
                    }
                  >
                    Stops
                  </SheetSectionTitle>
                  {showAll && (
                    <FormGroup className="overflow-hidden">
                      {enriched.map((d, i) => (
                        <div key={`list-${d.tripId ?? 'route'}`}>
                          {enriched.length > 1 && (
                            <p className="px-4 pb-1 pt-3 text-xs font-semibold text-secondary">
                              Towards {d.arrival?.headSign || d.stops[d.stops.length - 1]?.name}
                            </p>
                          )}
                          <DirectionStopList
                            direction={d}
                            routeEmoji={routeEmoji}
                            routeName={tracker.route_short_name}
                            routeId={tracker.route_id}
                            accent={i === 0 ? 'emerald' : 'violet'}
                          />
                        </div>
                      ))}
                    </FormGroup>
                  )}
                </section>
              )}

              <p className="flex items-center justify-center gap-2 text-xs text-muted" role="status">
                <RiRefreshLine className={cn('h-3.5 w-3.5', vehiclesQuery.isRefetching && 'animate-spin')} aria-hidden />
                <span className="tabular-nums">
                  {!vehiclesQuery.isSuccess
                    ? 'Preparing live position…'
                    : vehiclesQuery.isRefetching
                      ? 'Updating live position…'
                      : `Next update in ${secondsToNextUpdate ?? '—'}s`}
                </span>
              </p>
            </div>
          </SheetBody>

          <FullscreenMapDialog
            open={showFullscreen}
            onClose={() => setShowFullscreen(false)}
            routeShortName={tracker.route_short_name}
            routeColor={routeColor}
            routeTextColor={tracker.route_text_color}
            stopName={activeStop?.stop_name || tracker.stop_name}
            stops={enriched[0]?.stops ?? []}
            polyline={enriched[0]?.polyline ?? []}
            vehicles={
              enriched[0]?.approxVehicle
                ? [...(enriched[0].vehicles ?? []), enriched[0].approxVehicle]
                : (enriched[0]?.vehicles ?? [])
            }
            secondaryLines={secondaryLines}
            trackerStops={trackerStops}
            activeStopId={activeStop?.stop_id ?? activeStopId}
            onSelectStop={setActiveStopId}
          />
        </DialogContent>
      )}
    </Dialog>
  );
}
