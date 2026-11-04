import { useMemo, useState, Suspense, lazy } from 'react';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { LoadingOverlay } from '@/components/ui/loading-overlay';
import { RiDeleteBinLine, RiPencilLine, RiArrowRightLine, RiTimeLine, RiLoader4Line } from '@remixicon/react';
import { cn } from '@/lib/utils';
import { SheetBar, SheetBody, SheetSectionTitle, SHEET_GUTTER } from './sheet-chrome';
import { LiveSignalIcon } from '@/features/transit/transit-card';
import { ShimmerText } from './shimmer-text';
import { toast } from 'sonner';
import { getErrorMessage } from '@/lib/error-utils';
import { useAddTripPlan, useTripPlans } from './use-trip-plans';
import { useMultiStopArrivals, useTripDetails, useVehiclesForRoutes } from '@/features/transit/use-transit';
import { DEEP_ARRIVALS_LIMIT } from '@/features/transit/futar-api';
import { isArrivalsStale, parseStopArrivals, normalizeStopIdToStation } from '@/features/transit/transit-utils';
import { useOnlineStatus } from '@/hooks/use-online-status';
import { MapFallback } from '@/features/transit/map/map-fallback';

// Lazy: the route preview pulls in maplibre, kept out of the eager bundle.
const LazyRoutePreviewMap = lazy(() => import('@/features/transit/route-preview-map').then((m) => ({ default: m.RoutePreviewMap })));
import { decodePolyline } from '@/features/transit/route-geometry';
import { approachFromVehicle, resolveTrackedStopIndex } from '@/features/transit/live-position';
import { useNow } from '@/hooks/use-now';
import { useDeleteTripPlan } from './use-trip-plans';
import { useTripTimeline } from './use-trip-timeline';
import { tripSummary, legsWithAddressWalks } from './trip-timeline';
import { tripHasDepartures } from './trip-utils';
import { useJourneyMapGeometry, type JourneyLeg } from './journey-map';
import { LegChain } from './leg-chain';
import { DepartureOptions } from './departure-options';
import type { TripPlan, TripLeg } from './types';
import { SleepingIcon } from '@/components/ui/sleeping-icon';

/** Blend a `RRGGBB` hex toward white so the approach reads as a lighter shade
 *  of the route color. Returns hex without the leading #. */
function lightenHex(hex: string, amount = 0.45): string {
  const n = parseInt(hex.replace('#', ''), 16);
  const mix = (c: number) => Math.round(c + (255 - c) * amount);
  const r = mix((n >> 16) & 255);
  const g = mix((n >> 8) & 255);
  const b = mix(n & 255);
  return ((r << 16) | (g << 8) | b).toString(16).padStart(6, '0');
}

interface TripDetailDialogProps {
  open: boolean;
  trip: TripPlan | null;
  onOpenChange: (open: boolean) => void;
  onEdit: (trip: TripPlan) => void;
  /** Called after the close animation finishes; the parent keeps the data prop until then. */
  onExited?: () => void;
}

function useLegDepartures(legs: TripLeg[], now: number) {
  const stopIds = useMemo(
    () => legs.map((leg) => leg.departureStopId || leg.destinationStopId || null),
    [legs]
  );
  const arrivalsMap = useMultiStopArrivals(stopIds, { limit: DEEP_ARRIVALS_LIMIT });
  const offline = !useOnlineStatus();

  const isLoading = useMemo(() => {
    const validIds = stopIds.filter((id): id is string => Boolean(id));
    if (validIds.length === 0) return false;
    return validIds.some((id) => {
      const entry = arrivalsMap[id];
      return !entry || (entry.isLoading && !entry.data);
    });
  }, [stopIds, arrivalsMap]);

  const departureMap = useMemo(() => {
    const map = new Map<number, { countdown: string; isLive: boolean } | null>();
    legs.forEach((leg, i) => {
      const stopId = leg.departureStopId || leg.destinationStopId;
      const entry = stopId ? arrivalsMap[stopId] : undefined;
      const arrivals = entry?.data;
      if (arrivals && stopId) {
        let best: { arrivalMs: number; isPredicted: boolean } | null = null;
        for (const r of leg.routes) {
          const parsed = parseStopArrivals(arrivals, r.routeId, (r.headsign ?? leg.headsign) || null);
          const next = parsed[0];
          if (!next) continue;
          if (!best || next.arrivalMs < best.arrivalMs) {
            best = { arrivalMs: next.arrivalMs, isPredicted: next.isPredicted };
          }
        }
        if (best) {
          const minutes = Math.max(0, Math.round((best.arrivalMs - now) / 60000));
          // Offline or an old snapshot is the last known time, never live.
          const isLive = best.isPredicted && !isArrivalsStale(entry?.dataUpdatedAt, now, offline);
          map.set(i, { countdown: minutes === 0 ? 'NOW' : `${minutes} MIN`, isLive });
          return;
        }
      }
      map.set(i, null);
    });
    return map;
  }, [legs, arrivalsMap, now, offline]);

  return { departureMap, isLoading };
}

export function TripDetailDialog({ open, trip, onOpenChange, onEdit, onExited }: TripDetailDialogProps) {
  const deleteTrip = useDeleteTripPlan();
  const addTrip = useAddTripPlan();
  const { data: allTrips } = useTripPlans();
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [detailMapReady, setDetailMapReady] = useState(false);
  const [scrolled, setScrolled] = useState(false);
  const now = useNow(30_000);
  // Fallback leg list with the address walks synthesized in — the computed
  // timeline carries walks, but saved legs don't, so without this the walks
  // vanish whenever no live departure resolves.
  const fallbackLegs = useMemo(() => legsWithAddressWalks(trip?.legs ?? []), [trip]);
  const { departureMap, isLoading } = useLegDepartures(fallbackLegs, now);
  const { timeline, options, tooTightOptions } = useTripTimeline(trip);
  // Tight departures lead the strip (earliest first), then the catchable ones.
  const allOptions = useMemo(() => [...tooTightOptions, ...options], [tooTightOptions, options]);
  // The selected departure option — null follows the recommendation, which is
  // the first catchable option (a too-tight chip is never the default).
  const [optionIndex, setOptionIndex] = useState<number | null>(null);
  // Retarget to the default departure when the dialog switches trips
  // (adjusted during render so the previous trip's option can't paint).
  const [prevTripId, setPrevTripId] = useState<string | undefined>(undefined);
  if (trip?.id !== prevTripId) {
    setPrevTripId(trip?.id);
    if (optionIndex !== null) setOptionIndex(null);
    setConfirmingDelete(false);
    setDetailMapReady(false);
  }

  // Two-tap guard + undo: the trip people rely on daily must not vanish to a
  // stray tap. Re-inserting from the captured client copy restores the whole
  // plan (it lands at the end of the list — stated in the toast copy).
  const undoDelete = async (deleted: TripPlan) => {
    try {
      // Restore the Home/Uni role too, unless another trip took it meanwhile.
      const directionTaken = (allTrips ?? []).some(
        (other) => other.id !== deleted.id && other.direction === deleted.direction
      );
      await addTrip.mutateAsync({
        name: deleted.name,
        legs: deleted.legs,
        direction: deleted.direction && !directionTaken ? deleted.direction : null,
      });
      toast.success('Trip restored');
    } catch (err) {
      toast.error('Could not restore trip', { description: getErrorMessage(err) });
    }
  };

  const handleDeleteTrip = async () => {
    if (!trip) return;
    if (!confirmingDelete) {
      setConfirmingDelete(true);
      return;
    }
    try {
      await deleteTrip.mutateAsync(trip.id);
      onOpenChange(false);
      toast.success('Trip deleted', {
        description: 'Restored to the end of your list if you undo.',
        action: { label: 'Undo', onClick: () => void undoDelete(trip) },
        duration: 8000,
      });
    } catch (err) {
      toast.error('Failed to delete trip', { description: getErrorMessage(err) });
    }
  };
  // The first catchable option anchors the default selection.
  const defaultOptionIndex = tooTightOptions.length;
  const selectedIndex = Math.min(optionIndex ?? defaultOptionIndex, Math.max(0, allOptions.length - 1));
  const selectedOption = allOptions[selectedIndex] ?? null;
  const selectedTimeline = selectedOption?.timeline ?? timeline;
  const summary = tripSummary(selectedTimeline);

  // The SAME journey map the new-trip builder shows: each leg's route clipped
  // to its boarding → destination segment, only the stops in range, and the
  // walk segments connecting them end to end (address → first stop, between
  // legs, last stop → address).
  const journeyLegs = useMemo<JourneyLeg[]>(
    () =>
      (trip?.legs ?? []).map((leg) => ({
        routeType: leg.routeType,
        fromName: leg.departureStopName,
        toName: leg.destinationStopName,
        routes: leg.routes.map((r) => ({
          routeId: r.routeId,
          color: r.routeColor,
          headsign: r.headsign ?? leg.headsign,
        })),
      })),
    [trip]
  );
  const journeyOrigin = useMemo(() => trip?.legs[0]?.originAddress ?? null, [trip]);
  const journeyDestination = useMemo(
    () => (trip && trip.legs.length > 0 ? trip.legs[trip.legs.length - 1].destinationAddress ?? null : null),
    [trip]
  );
  const mapData = useJourneyMapGeometry(open && !!trip, journeyLegs, journeyOrigin, journeyDestination);

  const { vehicles: liveVehicles } = useVehiclesForRoutes(
    journeyLegs.flatMap((l) => l.routes.map((r) => r.routeId))
  );

  // Only the vehicles the user will actually take: each leg's chosen departure
  // tripId (from the live timeline). Every other vehicle on the line is noise.
  const relevantTripIds = useMemo(() => {
    const ids = new Set<string>();
    for (const l of selectedTimeline.legs ?? []) {
      if (l.mode !== 'WALK' && l.departureTripId) ids.add(l.departureTripId);
    }
    return ids;
  }, [selectedTimeline.legs]);
  const mapVehicles = useMemo(
    () =>
      liveVehicles
        .filter((v) => v.tripId && relevantTripIds.has(v.tripId))
        .map((v) => ({ id: v.vehicleId, lat: v.location?.lat ?? 0, lon: v.location?.lon ?? 0 })),
    [liveVehicles, relevantTripIds]
  );

  // The first transit leg's boarding stop — whose next vehicle's remaining
  // path to that stop is drawn as the live approach.
  const trackedLeg = useMemo(() => {
    const legs = selectedTimeline.legs ?? trip?.legs ?? [];
    return legs.find((l) => l.mode !== 'WALK') ?? legs[0] ?? null;
  }, [selectedTimeline.legs, trip]);
  const approachTripId = trackedLeg?.departureTripId ?? null;
  const approachTrip = useTripDetails(approachTripId);
  const approachVehicle = useMemo(() => {
    if (!approachTripId) return null;
    return liveVehicles.find((v) => v.tripId === approachTripId)?.location ?? null;
  }, [liveVehicles, approachTripId]);
  const approach = useMemo(() => {
    const entry = approachTrip.data?.data?.entry;
    const refs = approachTrip.data?.data?.references?.stops ?? {};
    if (!approachVehicle || !trackedLeg || !entry?.polyline?.points || !entry.stopTimes?.length) {
      return null;
    }
    const polyline = decodePolyline(entry.polyline.points);
    const stops = entry.stopTimes.map((st) => {
      const ref = refs[st.stopId];
      const areaId = ref?.parentStationId || normalizeStopIdToStation(st.stopId);
      return {
        id: st.stopId,
        name: refs[areaId]?.name ?? ref?.name ?? st.stopHeadsign ?? st.stopId,
        lat: ref?.lat ?? 0,
        lon: ref?.lon ?? 0,
      };
    });
    const trackedIndex = resolveTrackedStopIndex(
      entry.stopTimes.map((st) => st.stopId),
      refs,
      trackedLeg.departureStopId
    );
    const clipped = approachFromVehicle(polyline, stops, trackedIndex, approachVehicle);
    if (!clipped) return null;
    return {
      polyline: clipped.polyline,
      stops: clipped.stops,
      color: lightenHex(trackedLeg.routes[0]?.routeColor ?? 'a855f7'),
    };
  }, [approachTrip.data, approachVehicle, trackedLeg]);

  // Highlight the trip's stop endpoints (each leg's departure/destination).
  const tripStops = useMemo(() => {
    const names = new Set<string>();
    if (trip) {
      for (const leg of trip.legs) {
        if (leg.departureStopName) names.add(leg.departureStopName);
        if (leg.destinationStopName) names.add(leg.destinationStopName);
      }
    }
    return mapData.stops.map((s) => ({ ...s, selected: names.has(s.name) }));
  }, [mapData.stops, trip]);
  const timelineDepartureMap = useMemo(() => {
    if (!selectedTimeline.legs) return null;
    const map = new Map<number, { countdown: string; isLive: boolean } | null>();
    selectedTimeline.legs.forEach((l, i) => {
      if (l.mode === 'WALK' || l.departureMs == null) {
        map.set(i, null);
        return;
      }
      const minutes = Math.max(0, Math.round((l.departureMs - now) / 60000));
      map.set(i, { countdown: minutes === 0 ? 'NOW' : `${minutes} MIN`, isLive: !!l.isPredicted });
    });
    return map;
  }, [selectedTimeline.legs, now]);

  const fallbackFirstTransitIndex = useMemo(
    () => fallbackLegs.findIndex((l) => l.mode !== 'WALK'),
    [fallbackLegs]
  );

  const firstDeparture = useMemo(() => {
    const firstTransit = (selectedTimeline.legs ?? []).find((l) => l.mode !== 'WALK');
    if (!firstTransit || firstTransit.departureMs == null) {
      return fallbackFirstTransitIndex >= 0
        ? departureMap.get(fallbackFirstTransitIndex) ?? null
        : null;
    }
    const minutes = Math.max(0, Math.round((firstTransit.departureMs - now) / 60000));
    return { countdown: minutes === 0 ? 'NOW' : `${minutes} MIN`, isLive: !!firstTransit.isPredicted };
  }, [selectedTimeline.legs, now, departureMap, fallbackFirstTransitIndex]);

  const hasTransit = useMemo(
    () => (trip?.legs ?? []).some((l) => l.mode !== 'WALK'),
    [trip]
  );

  const allTransitLegsHaveDepartures = useMemo(() => {
    if (!hasTransit) return true;
    for (let i = 0; i < fallbackLegs.length; i++) {
      if (fallbackLegs[i].mode !== 'WALK') {
        const dep = departureMap.get(i);
        if (!dep) return false;
      }
    }
    return true;
  }, [hasTransit, fallbackLegs, departureMap]);

  const hasDepartures = tripHasDepartures({
    hasTransit,
    optionsCount: allOptions.length,
    firstDepartureCountdown: firstDeparture?.countdown,
    allTransitLegsHaveDepartures,
  });

  if (!trip) return null;

  const firstLeg = trip.legs[0];
  const lastLeg = trip.legs[trip.legs.length - 1];
  const origin = firstLeg?.originAddress?.name || firstLeg?.departureStopName || '';
  const destination =
    lastLeg?.destinationAddress?.name || lastLeg?.destinationStopName || lastLeg?.departureStopName || 'No destination';
  const firstTransitLeg = (selectedTimeline.legs ?? []).find((l) => l.mode !== 'WALK');
  const heroLive = summary ? !!firstTransitLeg?.isPredicted : !!firstDeparture?.isLive;
  const leaveInMinutes =
    selectedTimeline.leaveAt != null ? Math.max(0, Math.round((selectedTimeline.leaveAt - now) / 60_000)) : null;

  const MAP_HEIGHT = 208;

  return (
    <Dialog open={open} onOpenChange={onOpenChange} onExited={onExited}>
      <DialogContent
        className="sm:max-w-md max-h-[92vh] rounded-[28px] overflow-hidden p-0 gap-0"
        edgeToEdge
        aria-labelledby="trip-detail-title"
      >
        <SheetBar
          leading="close"
          onLeading={() => onOpenChange(false)}
          title={trip.name}
          titleId="trip-detail-title"
          subtitle={
            <span className="inline-flex max-w-full items-center gap-1.5">
              {origin && <span className="min-w-0 truncate">{origin}</span>}
              {origin && <RiArrowRightLine className="h-3 w-3 shrink-0 text-muted" aria-hidden />}
              <span className="min-w-0 truncate">{destination}</span>
            </span>
          }
          trailing={
            <button
              type="button"
              onClick={() => onEdit(trip)}
              className="inline-flex h-10 items-center gap-1.5 rounded-full bg-surface-overlay px-3.5 text-sm font-bold text-primary transition-colors hover:bg-surface-tertiary cursor-pointer active:scale-95 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary"
            >
              <RiPencilLine className="h-3.5 w-3.5" aria-hidden /> Edit
            </button>
          }
          scrolled={scrolled}
        />

        <SheetBody onScrolledChange={setScrolled} className="pb-6">
          {/* The route on the map leads, the way a transit app opens on where
              you're going; the leave-at card sits on its lower edge. */}
          <div className="relative" style={{ height: MAP_HEIGHT }}>
            {mapData.polylines.length > 0 && (
              <Suspense fallback={<MapFallback />}>
                <LazyRoutePreviewMap
                  polylines={mapData.polylines}
                  stops={tripStops}
                  vehicles={mapVehicles}
                  routeColor="3b82f6"
                  polylineColors={mapData.colors}
                  walkPolylines={mapData.walks}
                  approach={approach}
                  height={MAP_HEIGHT}
                  onReady={() => setDetailMapReady(true)}
                />
              </Suspense>
            )}
            <LoadingOverlay
              show={mapData.status !== 'failed' && (mapData.polylines.length === 0 || !detailMapReady)}
              label="Loading route map…"
            />
            {mapData.status === 'failed' && (
              <p className="absolute inset-0 flex items-center justify-center px-4 text-center text-xs text-muted">
                Couldn&apos;t load the route map.
              </p>
            )}
            <div aria-hidden className="pointer-events-none absolute inset-x-0 bottom-0 h-16 bg-gradient-to-t from-surface-secondary to-transparent" />
          </div>

          <div className={cn('relative z-30 -mt-12 space-y-7', SHEET_GUTTER)}>
            {isLoading ? (
              <div role="status" aria-label="Checking departures" className="space-y-4">
                <div className="h-[132px] rounded-3xl card-glass motion-safe:animate-pulse" />
                <div className="flex gap-2">
                  {[0, 1, 2].map((i) => (
                    <div key={i} className="h-[88px] w-[7.25rem] rounded-2xl bg-surface-overlay motion-safe:animate-pulse" />
                  ))}
                </div>
                <div className="h-40 rounded-3xl bg-surface-overlay motion-safe:animate-pulse" />
              </div>
            ) : !hasDepartures ? (
              <>
                <div className="flex items-center gap-4 rounded-3xl card-glass p-5">
                  <SleepingIcon className="h-7 w-7 shrink-0 text-indigo-400 dark:text-indigo-300" aria-hidden />
                  <div>
                    <p className="text-base font-extrabold tracking-tight text-primary">No departures right now</p>
                    <p className="mt-0.5 text-xs text-secondary">This journey isn’t running at the moment. Check back later.</p>
                  </div>
                </div>
                {/* The trip itself stays visible even when nothing is running. */}
                <section aria-labelledby="trip-steps-idle" className="space-y-3">
                  <SheetSectionTitle id="trip-steps-idle">Journey</SheetSectionTitle>
                  <LegChain legs={fallbackLegs} variant="full" />
                </section>
              </>
            ) : (
              <>
                {/* When to leave: the one answer this screen exists for. */}
                <section aria-label="When to leave" className="rounded-3xl card-glass card-depth p-5">
                  <div className="flex items-baseline justify-between gap-3">
                    <p className="text-xs font-semibold text-secondary">{summary ? 'Leave at' : 'Next departure'}</p>
                    {summary && leaveInMinutes != null && (
                      <p
                        className={cn(
                          'flex items-center gap-1.5 text-sm font-bold tabular-nums',
                          heroLive ? 'text-tint-emerald' : 'text-secondary'
                        )}
                      >
                        {heroLive ? <LiveSignalIcon className="h-3.5 w-3.5" /> : <RiTimeLine className="h-3.5 w-3.5" aria-hidden />}
                        {heroLive ? (
                          <ShimmerText>{leaveInMinutes === 0 ? 'now' : `in ${leaveInMinutes} min`}</ShimmerText>
                        ) : leaveInMinutes === 0 ? (
                          'now'
                        ) : (
                          `in ${leaveInMinutes} min`
                        )}
                      </p>
                    )}
                  </div>
                  <p
                    className={cn(
                      'mt-1 font-mono text-[3.25rem] font-black leading-none tracking-tight tabular-nums',
                      heroLive ? 'text-tint-emerald' : 'text-primary'
                    )}
                  >
                    {summary ? summary.leaveAt : firstDeparture?.countdown ?? '--'}
                  </p>
                  {summary && (
                    <p className="mt-3 text-sm text-secondary">
                      Arrive <span className="font-mono font-bold tabular-nums text-primary">{summary.arriveAt}</span>
                      <span className="mx-1.5 text-muted">·</span>
                      {summary.durationMin} min door to door
                    </p>
                  )}
                </section>

                {allOptions.length > 1 && (
                  <section aria-labelledby="trip-departures" className="space-y-3">
                    <SheetSectionTitle id="trip-departures">Departures</SheetSectionTitle>
                    <DepartureOptions
                      options={allOptions}
                      now={now}
                      selectedIndex={selectedIndex}
                      onSelect={setOptionIndex}
                    />
                  </section>
                )}

                <section aria-labelledby="trip-steps" className="space-y-3">
                  <SheetSectionTitle id="trip-steps">Journey</SheetSectionTitle>
                  <LegChain
                    legs={selectedTimeline.legs ?? fallbackLegs}
                    departureMap={timelineDepartureMap ?? departureMap}
                    variant="full"
                  />
                </section>
              </>
            )}

            {/* Destructive action lives at the end of the content, quiet until
                tapped, and needs a second tap — the daily trip must not vanish
                to a stray touch. */}
            <button
              type="button"
              onClick={handleDeleteTrip}
              onBlur={() => setConfirmingDelete(false)}
              disabled={deleteTrip.isPending}
              aria-label={confirmingDelete ? `Tap again to delete ${trip.name}` : `Delete ${trip.name}`}
              className={cn(
                'flex h-12 w-full items-center justify-center gap-2 rounded-2xl text-sm font-bold transition-colors cursor-pointer active:scale-[0.98] focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary disabled:opacity-50',
                confirmingDelete
                  ? 'bg-tint-red text-tint-red border border-tint-red'
                  : 'text-tint-red hover:bg-surface-overlay'
              )}
            >
              {deleteTrip.isPending ? <RiLoader4Line className="h-4 w-4 animate-spin" aria-hidden /> : <RiDeleteBinLine className="h-4 w-4" aria-hidden />}
              {confirmingDelete ? 'Tap again to delete' : 'Delete trip'}
            </button>
          </div>
        </SheetBody>
      </DialogContent>
    </Dialog>
  );
}
