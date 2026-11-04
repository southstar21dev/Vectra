import { useLayoutEffect, useMemo, useRef, useState, Suspense, lazy } from 'react';
import { motion, AnimatePresence, useReducedMotion } from 'framer-motion';
import { toast } from 'sonner';
import { RiArrowUpDownLine, RiArrowRightSLine, RiLoader4Line, RiResetLeftLine } from '@remixicon/react';
import { getErrorMessage } from '@/lib/error-utils';
import { cn } from '@/lib/utils';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { SheetBar, SheetBody, SheetFooter, SheetSectionTitle, SHEET_GUTTER } from '../sheet-chrome';
import { placeIcon } from './place-icon';
import { LoadingOverlay } from '@/components/ui/loading-overlay';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ConfirmDialog } from '@/components/ui/confirm-dialog';
import { EASE_DRAWER } from '@/components/ui/motion-tokens';
import { MapFallback } from '@/features/transit/map/map-fallback';
import type { CatalogStop } from '@/features/transit/catalog-types';
import { useAddTripPlan, useTripPlans, useUpdateTripPlan } from '../use-trip-plans';
import { buildAlternativeLeg } from '../alternative-leg';
import { useJourneyMapGeometry, type JourneyLeg } from '../journey-map';
import { normalizeStopName } from '../route-variants';
import type { RouteSuggestion } from '../route-suggest';
import type { TripDirection, TripLeg } from '../types';
import { DIRECTION_OPTIONS, endpointName, type CreateTripDialogProps, type Endpoint } from './constants';
import { buildJourneyOptions, type JourneyOption } from './journey-options';
import { usePlannerCatalog } from './use-planner-catalog';
import { PlaceSearchPanel } from './place-search-panel';
import { JourneyOptionList } from './journey-option-list';
import { JourneyReview } from './journey-review';
import { LegEditorPanel } from './leg-editor-panel';

// Lazy: the route preview pulls in maplibre, kept out of the eager bundle.
const LazyRoutePreviewMap = lazy(() => import('@/features/transit/route-preview-map').then((m) => ({ default: m.RoutePreviewMap })));

/** A place from a saved trip; commute trips tag their ends Home / Uni. */
type SavedPlace = Endpoint & { tag?: 'Home' | 'Uni' };

type View =
  | { kind: 'main' }
  | { kind: 'place'; target: 'from' | 'to' }
  | { kind: 'leg'; index: number }
  | { kind: 'append' };

const legStop = (id: string, name: string): CatalogStop => ({ id, name, lat: 0, lon: 0 });

function endpointsOf(legs: TripLeg[]): { from: Endpoint | null; to: Endpoint | null } {
  const first = legs[0];
  const last = legs[legs.length - 1];
  return {
    from: first ? { stop: legStop(first.departureStopId, first.departureStopName), address: first.originAddress ?? null } : null,
    to: last ? { stop: legStop(last.destinationStopId, last.destinationStopName), address: last.destinationAddress ?? null } : null,
  };
}

/**
 * Plan or edit a saved trip, journey first: choose where you start and where
 * you're going, take one of the complete journeys Vectra proposes, then fine-
 * tune it — extra either/or lines per ride, a different line, or a hand-built
 * chain when no proposal fits.
 */
export function CreateTripDialog({ open, onOpenChange, editingTrip }: CreateTripDialogProps) {
  const reduceMotion = useReducedMotion();
  const [view, setView] = useState<View>({ kind: 'main' });
  const [from, setFrom] = useState<Endpoint | null>(null);
  const [to, setTo] = useState<Endpoint | null>(null);
  const [legs, setLegs] = useState<TripLeg[]>([]);
  const [choosingJourney, setChoosingJourney] = useState(false);
  const [tripName, setTripName] = useState('');
  const [direction, setDirection] = useState<TripDirection | null>(null);
  const [dirty, setDirty] = useState(false);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const [mapReady, setMapReady] = useState(false);
  const [scrolled, setScrolled] = useState(false);

  const { catalog, error: catalogError, retry, memberMeta } = usePlannerCatalog(open);
  const { data: savedTrips = [] } = useTripPlans();
  const addTrip = useAddTripPlan();
  const updateTrip = useUpdateTripPlan();
  const isEditing = !!editingTrip;
  const saving = addTrip.isPending || updateTrip.isPending;

  // Seed the form whenever the dialog opens or the edited trip changes —
  // adjusted during render so the previous trip can never paint for a frame.
  const [seededFor, setSeededFor] = useState<{ open: boolean; trip: typeof editingTrip }>({ open: false, trip: undefined });
  if (seededFor.open !== open || seededFor.trip !== editingTrip) {
    setSeededFor({ open, trip: editingTrip });
    if (open) {
      const seeded = editingTrip?.legs ?? [];
      const ends = endpointsOf(seeded);
      setView({ kind: 'main' });
      setFrom(ends.from);
      setTo(ends.to);
      setLegs(seeded);
      setChoosingJourney(false);
      setTripName(editingTrip?.name ?? '');
      setDirection(editingTrip?.direction ?? null);
      setDirty(false);
      setConfirmDiscard(false);
      setMapReady(false);
    }
  }

  // Every sub-view starts at the top of the scroll area.
  const scrollRef = useRef<HTMLDivElement>(null);
  const viewKey = view.kind === 'leg' ? `leg-${view.index}` : view.kind === 'place' ? `place-${view.target}` : view.kind;
  // Going deeper slides forward (+1), returning to the main view slides back (-1).
  const viewDepth = view.kind === 'main' ? 0 : 1;
  const [prevView, setPrevView] = useState({ key: viewKey, depth: viewDepth });
  const [viewDirection, setViewDirection] = useState(1);
  if (prevView.key !== viewKey) {
    setPrevView({ key: viewKey, depth: viewDepth });
    setViewDirection(viewDepth < prevView.depth ? -1 : 1);
  }
  useLayoutEffect(() => {
    scrollRef.current?.scrollTo({ top: 0 });
  }, [viewKey]);

  const showOptions = !!from && !!to && (legs.length === 0 || choosingJourney);
  const options = useMemo<JourneyOption[] | null>(() => {
    if (!showOptions || !from || !to) return null;
    if (!catalog) return null;
    return buildJourneyOptions({ routes: catalog.routes, index: catalog.index, stops: catalog.stops, from: from.stop, to: to.stop });
  }, [showOptions, catalog, from, to]);

  const lastLeg = legs[legs.length - 1];
  const journeyComplete =
    !!to && !!lastLeg && normalizeStopName(lastLeg.destinationStopName) === normalizeStopName(to.stop.name);

  // Places from saved trips, offered in the search before typing.
  const savedPlaces = useMemo<SavedPlace[]>(() => {
    const out: SavedPlace[] = [];
    const seen = new Set<string>();
    const stopFor = (id: string, name: string) => catalog?.stops.find((s) => s.id === id) ?? legStop(id, name);
    const add = (endpoint: SavedPlace) => {
      const key = endpoint.address?.name ?? `stop:${endpoint.stop.id}`;
      if (seen.has(key)) return;
      seen.add(key);
      out.push(endpoint);
    };
    // Commute trips name their ends: a "To uni" trip runs Home → Uni, "Home" the reverse.
    const tagged = [...savedTrips].sort((x, y) => Number(!!y.direction) - Number(!!x.direction));
    for (const trip of tagged) {
      const ends = endpointsOf(trip.legs);
      const fromTag = trip.direction === 'to_uni' ? 'Home' : trip.direction === 'to_home' ? 'Uni' : undefined;
      const toTag = trip.direction === 'to_uni' ? 'Uni' : trip.direction === 'to_home' ? 'Home' : undefined;
      if (ends.from) add({ ...ends.from, stop: stopFor(ends.from.stop.id, ends.from.stop.name), tag: fromTag });
      if (ends.to) add({ ...ends.to, stop: stopFor(ends.to.stop.id, ends.to.stop.name), tag: toTag });
    }
    return out.slice(0, 6);
  }, [savedTrips, catalog]);

  const buildLeg = (routes: RouteSuggestion[], legFrom: CatalogStop, legTo: CatalogStop): TripLeg | null =>
    buildAlternativeLeg({
      index: catalog?.index ?? null,
      suggestions: routes,
      departure: { id: legFrom.id, name: legFrom.name },
      destination: { id: legTo.id, name: legTo.name },
      memberMeta,
    }) ??
    // A line the direction index can't confirm (rare catalog gaps) still
    // builds from the suggestion's own headsign rather than failing outright.
    buildAlternativeLeg({
      index: null,
      suggestions: routes,
      departure: { id: legFrom.id, name: legFrom.name },
      destination: { id: legTo.id, name: legTo.name },
      memberMeta,
    });

  const changed = () => setDirty(true);

  const pickEndpoint = (target: 'from' | 'to', endpoint: Endpoint) => {
    if (target === 'from') setFrom(endpoint);
    else setTo(endpoint);
    // A new start or destination invalidates the journey; offer fresh options.
    setLegs([]);
    setChoosingJourney(false);
    setMapReady(false);
    changed();
    const other = target === 'from' ? to : from;
    setView(other ? { kind: 'main' } : { kind: 'place', target: target === 'from' ? 'to' : 'from' });
  };

  const swap = () => {
    setFrom(to);
    setTo(from);
    setLegs([]);
    setChoosingJourney(false);
    setMapReady(false);
    changed();
  };

  const chooseJourney = (option: JourneyOption) => {
    const built = option.legs.map((leg) => buildLeg(leg.routes, leg.from, leg.to));
    if (built.some((leg) => !leg)) {
      toast.error('This journey could not be built. Try another one, or pick the line yourself.');
      return;
    }
    setLegs(built as TripLeg[]);
    setChoosingJourney(false);
    setMapReady(false);
    changed();
  };

  const setLegRoutes = (index: number, routes: RouteSuggestion[]) => {
    const leg = legs[index];
    if (!leg || routes.length === 0) return;
    const next = buildLeg(routes, legStop(leg.departureStopId, leg.departureStopName), legStop(leg.destinationStopId, leg.destinationStopName));
    if (!next) {
      toast.error(`${routes[routes.length - 1].shortName} doesn’t run this stretch in that direction.`);
      return;
    }
    setLegs((prev) => prev.map((l, i) => (i === index ? next : l)));
    changed();
  };

  const confirmLeg = (routes: RouteSuggestion[], legTo: CatalogStop) => {
    if (view.kind === 'leg') {
      setLegRoutes(view.index, routes);
    } else if (view.kind === 'append' && from) {
      // From the options list a hand-built ride starts a fresh journey;
      // from the review it continues where the journey stops.
      const fresh = choosingJourney || !lastLeg;
      const boardAt = fresh ? from.stop : legStop(lastLeg.destinationStopId, lastLeg.destinationStopName);
      // Ending at the destination's own stop keeps its exact id for the address walk.
      const end = to && normalizeStopName(legTo.name) === normalizeStopName(to.stop.name) ? to.stop : legTo;
      const leg = buildLeg(routes, boardAt, end);
      if (!leg) {
        toast.error('This ride could not be added. Try another line.');
        return;
      }
      setLegs((prev) => (fresh ? [leg] : [...prev, leg]));
      setChoosingJourney(false);
      setMapReady(false);
      changed();
    }
    setView({ kind: 'main' });
  };

  const removeLastLeg = () => {
    setLegs((prev) => prev.slice(0, -1));
    setMapReady(false);
    changed();
    setView({ kind: 'main' });
  };

  // The SAME journey map the saved-trip detail shows.
  const journeyLegs = useMemo<JourneyLeg[]>(
    () =>
      legs.map((leg) => ({
        routeType: leg.routeType,
        fromName: leg.departureStopName,
        toName: leg.destinationStopName,
        routes: leg.routes.map((r) => ({ routeId: r.routeId, color: r.routeColor, headsign: r.headsign ?? leg.headsign })),
      })),
    [legs]
  );
  const showReview = !!from && !!to && legs.length > 0 && !choosingJourney;
  const mapData = useJourneyMapGeometry(open && showReview, journeyLegs, from?.address ?? null, to?.address ?? null);

  const handleSave = async () => {
    if (!from || !to || !journeyComplete) return;
    const stamped = legs.map((leg, i) => {
      const { originAddress: _o, destinationAddress: _d, ...rest } = leg;
      void _o;
      void _d;
      const out: TripLeg = { ...rest };
      // An address attaches only to the leg that boards / alights at its snapped stop.
      if (i === 0 && from.address?.stopId && leg.departureStopId === from.address.stopId) out.originAddress = from.address;
      if (i === legs.length - 1 && to.address?.stopId && leg.destinationStopId === to.address.stopId) out.destinationAddress = to.address;
      return out;
    });
    const name = tripName.trim() || `Trip to ${endpointName(to)}`;
    try {
      if (isEditing && editingTrip) await updateTrip.mutateAsync({ id: editingTrip.id, name, legs: stamped, direction });
      else await addTrip.mutateAsync({ name, legs: stamped, direction });
      toast.success(isEditing ? 'Trip updated' : 'Trip saved');
      onOpenChange(false);
    } catch (err) {
      toast.error(getErrorMessage(err, 'Could not save the trip. Please try again.'));
    }
  };

  // Guarded close: a stray scrim tap, Escape or back gesture must never
  // silently erase a planned journey.
  const requestClose = () => {
    if (dirty) setConfirmDiscard(true);
    else onOpenChange(false);
  };

  const title =
    view.kind === 'place'
      ? view.target === 'from'
        ? 'Where from?'
        : 'Where to?'
      : view.kind === 'leg'
        ? `Ride ${view.index + 1}`
        : view.kind === 'append'
          ? 'Next ride'
          : isEditing
            ? 'Edit trip'
            : 'New trip';

  // The ride editors name their stretch in the bar, under the title.
  const subtitle = (() => {
    if (view.kind === 'leg') {
      const leg = legs[view.index];
      return leg ? `${leg.departureStopName} → ${leg.destinationStopName}` : undefined;
    }
    if (view.kind === 'append' && from && to) {
      const boardAt = lastLeg && !choosingJourney ? lastLeg.destinationStopName : from.stop.name;
      return `${boardAt} → ${to.stop.name}`;
    }
    return undefined;
  })();

  // Sub-views push in from the right and pop back to the left, overlapping
  // (popLayout) so there is never an empty frame between them. Opacity plus a
  // `transform` string keeps the slide on the compositor.
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

  const renderEndpoints = () => (
    <div className="relative rounded-3xl card-glass card-depth p-1.5">
      <span aria-hidden className="absolute left-[26px] top-[38px] bottom-[38px] w-0.5 rounded-full bg-border-primary" />
      {(['from', 'to'] as const).map((target) => {
        const endpoint = target === 'from' ? from : to;
        return (
          <button
            key={target}
            type="button"
            onClick={() => setView({ kind: 'place', target })}
            aria-label={`${target === 'from' ? 'From' : 'To'}: ${endpoint ? endpointName(endpoint) : 'not set'}`}
            className="group flex min-h-[3.75rem] w-full items-center gap-3.5 rounded-[20px] py-2.5 pl-4 pr-16 text-left transition-colors hover:bg-surface-overlay cursor-pointer active:scale-[0.99] focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary"
          >
            <span aria-hidden className="relative z-10 flex w-3.5 shrink-0 justify-center">
              {target === 'from' ? (
                <span className="h-3 w-3 rounded-full bg-text-primary" />
              ) : (
                <span className="h-3.5 w-3.5 rounded-full border-[3px] border-accent-primary bg-surface-secondary" />
              )}
            </span>
            <span className="min-w-0 flex-1">
              <span className="block text-xs font-semibold text-muted">{target === 'from' ? 'From' : 'To'}</span>
              {endpoint ? (
                <>
                  <span className="block truncate text-base font-bold text-primary">{endpointName(endpoint)}</span>
                  {endpoint.address && <span className="block truncate text-xs text-muted">Near {endpoint.stop.name}</span>}
                </>
              ) : (
                <span className="block text-base font-semibold text-secondary">
                  {target === 'from' ? 'Choose where you start' : 'Choose where you’re going'}
                </span>
              )}
            </span>
          </button>
        );
      })}
      <button
        type="button"
        onClick={swap}
        disabled={!from || !to}
        aria-label="Swap start and destination"
        className="absolute right-4 top-1/2 flex h-10 w-10 -translate-y-1/2 items-center justify-center rounded-full bg-surface-tertiary text-secondary shadow-sm transition-colors hover:text-primary cursor-pointer active:scale-95 disabled:pointer-events-none disabled:opacity-40 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary"
      >
        <RiArrowUpDownLine className="h-4 w-4" />
      </button>
    </div>
  );

  const renderMain = () => (
    <div className="space-y-7">
      {renderEndpoints()}

      {(!from || !to) && catalog && savedPlaces.length > 0 && (
        <section aria-labelledby="quick-places" className="space-y-2">
          <SheetSectionTitle id="quick-places">{!from ? 'Start from' : 'Going to'}</SheetSectionTitle>
          <div className="overflow-hidden rounded-3xl card-glass">
            {savedPlaces
              .filter((p) => !from || p.stop.id !== from.stop.id)
              .map((place) => {
                const Icon = placeIcon(place.tag);
                return (
                  <button
                    key={`${place.stop.id}:${place.address?.name ?? ''}`}
                    type="button"
                    onClick={() => pickEndpoint(!from ? 'from' : 'to', place)}
                    className="group flex min-h-14 w-full items-center gap-3 border-b border-subtle px-4 py-2.5 text-left transition-colors last:border-b-0 hover:bg-surface-overlay cursor-pointer active:bg-surface-overlay focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent-primary"
                  >
                    <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-surface-overlay text-secondary group-hover:text-accent-primary">
                      <Icon className="h-4 w-4" />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-semibold text-primary">{endpointName(place)}</span>
                      <span className="block truncate text-xs text-muted">
                        {[place.tag, place.address ? `Near ${place.stop.name}` : 'BKK stop'].filter(Boolean).join(' · ')}
                      </span>
                    </span>
                    <RiArrowRightSLine className="h-4 w-4 shrink-0 text-muted" aria-hidden />
                  </button>
                );
              })}
          </div>
        </section>
      )}

      {catalogError && (
        <div role="alert" className="flex items-center gap-3 rounded-2xl panel-glass px-4 py-3">
          <p className="min-w-0 flex-1 text-xs text-secondary">Couldn’t load the BKK line catalog. Check your connection.</p>
          <Button type="button" variant="outline" size="sm" onClick={retry}>
            <RiResetLeftLine className="h-3.5 w-3.5" /> Retry
          </Button>
        </div>
      )}

      {showOptions && (
        <div className="space-y-2">
          <JourneyOptionList
            options={catalogError ? [] : options}
            onChoose={chooseJourney}
            onPickLine={() => setView({ kind: 'append' })}
          />
          {legs.length > 0 && (
            <Button type="button" variant="ghost" className="h-10 w-full rounded-2xl" onClick={() => setChoosingJourney(false)}>
              Keep my current journey
            </Button>
          )}
        </div>
      )}

      {showReview && from && to && (
        <>
          {catalog ? (
            <JourneyReview
              catalog={catalog}
              from={from}
              to={to}
              legs={legs}
              onSetLegRoutes={setLegRoutes}
              onEditLeg={(index) => setView({ kind: 'leg', index })}
              onContinue={() => setView({ kind: 'append' })}
              onOtherJourneys={() => setChoosingJourney(true)}
            />
          ) : (
            <div role="status" aria-label="Loading journey" className="h-48 rounded-3xl card-glass motion-safe:animate-pulse" />
          )}

          <div className="relative h-44 overflow-hidden rounded-3xl card-glass">
            {mapData.polylines.length > 0 && (
              <Suspense fallback={<MapFallback />}>
                <LazyRoutePreviewMap
                  polylines={mapData.polylines}
                  stops={mapData.stops}
                  vehicles={[]}
                  routeColor={legs[0]?.routes[0]?.routeColor || '64748b'}
                  polylineColors={mapData.colors}
                  walkPolylines={mapData.walks}
                  height={176}
                  onReady={() => setMapReady(true)}
                />
              </Suspense>
            )}
            <LoadingOverlay
              show={mapData.status !== 'failed' && (mapData.polylines.length === 0 || !mapReady)}
              label="Drawing your journey…"
            />
            {mapData.status === 'failed' && (
              <p className="absolute inset-0 flex items-center justify-center px-4 text-center text-xs text-muted">
                Couldn&apos;t load the route map.
              </p>
            )}
          </div>

          {/* How the trip is saved: one grouped list, settings-style. */}
          <section aria-labelledby="trip-details" className="space-y-3">
            <SheetSectionTitle id="trip-details">Save as</SheetSectionTitle>
            <div className="rounded-3xl card-glass divide-y divide-subtle">
              <div className="space-y-2 px-4 pb-4 pt-3.5">
                <label htmlFor="trip-name" className="block text-xs font-semibold text-secondary">Trip name</label>
                <Input
                  id="trip-name"
                  value={tripName}
                  onChange={(e) => {
                    setTripName(e.target.value);
                    changed();
                  }}
                  placeholder={`Trip to ${endpointName(to)}`}
                  className="h-11 text-base sm:text-sm"
                />
              </div>
              <div className="space-y-2 px-4 pb-4 pt-3.5">
                <p id="trip-commute" className="text-xs font-semibold text-secondary">Show on dashboard</p>
                <div role="radiogroup" aria-labelledby="trip-commute" className="card-glass grid grid-cols-3 gap-1 rounded-2xl p-1">
                  {DIRECTION_OPTIONS.map((option) => {
                    const active = direction === option.value;
                    return (
                      <button
                        key={option.value ?? 'none'}
                        type="button"
                        role="radio"
                        aria-checked={active}
                        onClick={() => {
                          setDirection(option.value);
                          changed();
                        }}
                        className={cn(
                          'h-10 rounded-xl border text-sm font-bold transition-colors cursor-pointer active:scale-[0.98] focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary',
                          active ? option.selected : 'border-transparent text-secondary hover:text-primary'
                        )}
                      >
                        {option.label}
                      </button>
                    );
                  })}
                </div>
                <p className="text-xs text-muted">
                  {direction === 'to_uni'
                    ? 'The dashboard shows this trip when you’re heading to uni.'
                    : direction === 'to_home'
                      ? 'The dashboard shows this trip when you’re heading home.'
                      : 'Pick one and the dashboard shows this trip at the right time of day.'}
                </p>
              </div>
            </div>
          </section>
        </>
      )}
    </div>
  );

  const renderView = () => {
    if (view.kind === 'place') {
      if (!catalog) {
        return catalogError ? (
          <div className="space-y-3 py-6 text-center">
            <p className="text-sm text-secondary">Couldn’t load BKK stops.</p>
            <Button type="button" variant="outline" onClick={retry}>Retry</Button>
          </div>
        ) : (
          <div role="status" aria-label="Loading stops" className="flex justify-center py-10">
            <RiLoader4Line className="h-5 w-5 animate-spin text-muted" />
          </div>
        );
      }
      const other = view.target === 'from' ? to : from;
      return (
        <PlaceSearchPanel
          target={view.target}
          stops={catalog.stops}
          savedPlaces={savedPlaces.filter((p) => !other || p.stop.id !== other.stop.id)}
          onPick={(endpoint) => pickEndpoint(view.target, endpoint)}
        />
      );
    }
    if ((view.kind === 'leg' || view.kind === 'append') && catalog && from && to) {
      if (view.kind === 'leg') {
        const leg = legs[view.index];
        if (!leg) return null;
        return (
          <LegEditorPanel
            key={`leg-${view.index}`}
            catalog={catalog}
            mode="edit"
            from={legStop(leg.departureStopId, leg.departureStopName)}
            to={legStop(leg.destinationStopId, leg.destinationStopName)}
            initialRouteIds={leg.routes.map((r) => r.routeId)}
            onConfirm={confirmLeg}
            onRemove={view.index === legs.length - 1 ? removeLastLeg : undefined}
          />
        );
      }
      return (
        <LegEditorPanel
          key="append"
          catalog={catalog}
          mode="append"
          from={lastLeg && !choosingJourney ? legStop(lastLeg.destinationStopId, lastLeg.destinationStopName) : from.stop}
          to={to.stop}
          onConfirm={confirmLeg}
        />
      );
    }
    return renderMain();
  };

  return (
    <>
    <Dialog open={open} onOpenChange={requestClose}>
      <DialogContent
        className="sm:max-w-xl max-h-[92vh] rounded-[28px] p-0 overflow-hidden flex flex-col gap-0"
        edgeToEdge
        aria-labelledby="trip-dialog-title"
      >
        <SheetBar
          leading={view.kind === 'main' ? 'close' : 'back'}
          onLeading={view.kind === 'main' ? requestClose : () => setView({ kind: 'main' })}
          title={title}
          titleId="trip-dialog-title"
          subtitle={subtitle}
          scrolled={scrolled}
        />

        <SheetBody
          ref={scrollRef}
          onScrolledChange={setScrolled}
          fadeBottom={view.kind !== 'leg' && view.kind !== 'append'}
          className={cn('pb-6 pt-2', SHEET_GUTTER)}
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

        {/* The save action docks only once there is a journey to save; before
            that the sheet has nothing to promise, so it shows nothing. */}
        {view.kind === 'main' && showReview && (
          <SheetFooter>
            <Button type="button" size="lg" className="h-12 w-full rounded-2xl" onClick={handleSave} disabled={!journeyComplete || saving}>
              {saving ? (
                <RiLoader4Line className="h-4 w-4 animate-spin" />
              ) : !journeyComplete ? (
                `Finish the journey to ${endpointName(to!)}`
              ) : isEditing ? (
                'Save changes'
              ) : (
                'Save trip'
              )}
            </Button>
          </SheetFooter>
        )}
      </DialogContent>
    </Dialog>

      {/* A sibling, not a child, of the trip dialog: nested inside it, the
          confirmation stayed frozen on screen while the trip dialog exited and
          then vanished without its own exit animation. */}
      <ConfirmDialog
        open={confirmDiscard}
        onClose={() => setConfirmDiscard(false)}
        onConfirm={() => {
          setConfirmDiscard(false);
          onOpenChange(false);
        }}
        title={isEditing ? 'Discard your changes?' : 'Discard this trip?'}
        description={isEditing ? 'The trip stays as it was saved.' : 'The journey you planned will be lost.'}
        confirmText="Discard"
        cancelText="Keep editing"
      />
    </>
  );
}
