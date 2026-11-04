import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { RiEqualizer2Line, RiArrowDownSLine, RiMapPinLine, RiNavigationLine, RiAddLine } from '@remixicon/react';
import { Button } from '@/components/ui/button';
import { OfflineBanner } from '@/components/ui/offline-banner';
import { EmptyState } from '@/components/ui/empty-state';
import { Reveal } from '@/components/ui/reveal';
import { toast } from 'sonner';
import type { TransitTracker } from './use-transit';
import { TransitCard } from './transit-card';
import { RouteBadge } from './route-badge';
import { AddTrackerDialog } from './add-tracker/add-tracker-dialog';
import { EditTrackerDialog } from './edit-tracker-dialog';
import { LivePositionDialog } from '@/features/transit/live-position/live-position-dialog';
import { ReorderFavoritesDialog } from './reorder-favorites-dialog';
import NearbyExplorerDialog from './nearby-explorer-dialog';
import { cn } from '@/lib/utils';
import { formatRouteDisplayName, getTrackerStops, MAX_FAVORITE_TRACKERS } from './transit-utils';
import {
  useAddTransitTracker,
  useDeleteTransitTracker,
  useToggleTransitFavorite,
  useTransitTrackers,
  useUpdateTrackerStops,
} from './use-transit';
import { useSetNavbarActions } from '@/features/navbar/navbar-action-context';
import type { NavbarActionItem } from '@/features/navbar/navbar-action-context';
import { useOnlineStatus } from '@/hooks/use-online-status';
import { useDialogPresence } from '@/hooks/use-dialog-presence';
import { getErrorMessage } from '@/lib/error-utils';
import { TripPlannerSection } from '@/features/trips/trip-planner-section';
import { dedupeTrips } from '@/features/trips/trip-utils';
import { useTripPlans } from '@/features/trips/use-trip-plans';
import { CreateTripDialog } from '@/features/trips/create-trip/create-trip-dialog';
import { TripDetailDialog } from '@/features/trips/trip-detail-dialog';
import type { TripPlan } from '@/features/trips/types';
import { NearbyMapStrip } from './nearby-map-strip';
import { LoadingOverlay } from '@/components/ui/loading-overlay';
import { ThinkingOrb } from 'thinking-orbs';
import { PostageStamp } from '@/components/ui/postage-stamp';
import { useNearbyStops } from './use-nearby-data';
import { SleepingIcon } from '@/components/ui/sleeping-icon';

/** Tracker monitors stacked in one column, phone and desktop alike. */
function TrackerGrid({ className, children }: { className?: string; children: ReactNode }) {
  return <div className={cn('grid grid-cols-1 gap-2.5', className)}>{children}</div>;
}

/** A section title as a postage stamp stuck onto the card's edge. */
function SectionStamp({
  title,
  caption,
  covered,
  className,
}: {
  title: string;
  caption: string;
  /** Its section is under the loading cover: the stamp steps away with it. */
  covered: boolean;
  className?: string;
}) {
  return (
    // Above the loading covers (z-30) so a map-only cover never hides it; when
    // the section itself is covered the stamp fades out instead.
    <PostageStamp
      className={cn('absolute left-4 z-40 transition-opacity duration-300', covered && 'opacity-0', className)}
      innerClassName="px-3 pt-1.5 pb-1"
    >
      <h2 className="text-base font-extrabold leading-none tracking-tight text-primary">{title}</h2>
      <span className="text-[11px] font-semibold leading-none text-accent-primary">{caption}</span>
    </PostageStamp>
  );
}

type CoverMode = 'all' | 'map' | 'content';

export function TransitPage() {
  const { data: trackers = [], isFetching, isFetched } = useTransitTrackers();
  const { data: trips = [] } = useTripPlans();
  const tripCount = useMemo(() => dedupeTrips(trips).length, [trips]);

  // First ever fetch still in flight (no offline cache to seed from yet).
  const firstLoad = isFetching && !isFetched;
  const [showAdd, setShowAdd] = useState(false);
  const [showReorderDialog, setShowReorderDialog] = useState(false);
  const [reorderDefaultTab, setReorderDefaultTab] = useState<'all' | 'favorites'>('all');
  const [createTripOpen, setCreateTripOpen] = useState(false);
  const [editingTrip, setEditingTrip] = useState<TripPlan | undefined>(undefined);
  const detail = useDialogPresence<TripPlan>();
  const editTracker = useDialogPresence<TransitTracker>();
  const liveTracker = useDialogPresence<TransitTracker>();
  const nearby = useDialogPresence<true>();
  const nearbyStops = useNearbyStops();
  const peekStops = useMemo(
    () => nearbyStops.stops.slice(0, 8).map((stop) => ({ id: stop.id, lat: stop.lat, lon: stop.lon })),
    [nearbyStops.stops]
  );
  const openNearby = nearby.openWith;

  const favoriteTrackers = useMemo(() => trackers.filter((t) => t.is_favorite), [trackers]);

  // Departures tracking map to separate active trackers from those with no upcoming departures
  const [hasDeparturesMap, setHasDeparturesMap] = useState<Record<string, boolean>>({});
  const [showInactive, setShowInactive] = useState(() => {
    if (typeof window === 'undefined') return false;
    try {
      return localStorage.getItem('vectra_show_inactive_transits') === 'true';
    } catch {
      return false;
    }
  });

  const handleHasDeparturesChange = useCallback((trackerId: string, hasDepartures: boolean) => {
    setHasDeparturesMap((prev) => {
      if (prev[trackerId] === hasDepartures) return prev;
      return { ...prev, [trackerId]: hasDepartures };
    });
  }, []);

  // Loading / stale cover: while any tracker has no times it can stand behind,
  // one spinner covers everything below the map — or the whole card when the
  // map itself hasn't loaded yet. A loaded map is never covered again.
  const [pendingMap, setPendingMap] = useState<Record<string, boolean>>({});
  const handlePendingChange = useCallback((trackerId: string, pending: boolean) => {
    setPendingMap((prev) => (prev[trackerId] === pending ? prev : { ...prev, [trackerId]: pending }));
  }, []);
  const [mapPending, setMapPending] = useState(false);

  const toggleShowInactive = () => {
    setShowInactive((prev) => {
      const next = !prev;
      try {
        localStorage.setItem('vectra_show_inactive_transits', String(next));
      } catch {
        // Storage blocked: the toggle still applies for this session.
      }
      return next;
    });
  };

  // The active/inactive split waits until every tracker has reported its
  // departures verdict at least once (the map's keys are the reports). Without
  // this, no-departure trackers teleport from the grid into the collapsed row
  // a second after load — while the user is mid-glance.
  const departuresSettled =
    trackers.length > 0 && trackers.every((t) => hasDeparturesMap[t.id] !== undefined);

  const activeTrackers = useMemo(
    () =>
      departuresSettled
        ? trackers.filter((t) => hasDeparturesMap[t.id] !== false)
        : trackers,
    [trackers, hasDeparturesMap, departuresSettled]
  );

  const inactiveTrackers = useMemo(
    () =>
      departuresSettled
        ? trackers.filter((t) => hasDeparturesMap[t.id] === false)
        : [],
    [trackers, hasDeparturesMap, departuresSettled]
  );

  const allInactive = trackers.length > 0 && activeTrackers.length === 0 && inactiveTrackers.length > 0;

  const deleteTracker = useDeleteTransitTracker();
  const addTracker = useAddTransitTracker();
  const updateTrackerStops = useUpdateTrackerStops();
  const toggleFavorite = useToggleTransitFavorite();
  const [favoriteBusyId, setFavoriteBusyId] = useState<string | null>(null);
  const isOffline = !useOnlineStatus();

  // Warm the map components the tracker dialogs render (add/edit tracker, live
  // position, nearby explorer) so opening one shows its map immediately.
  // Importing a module only parses it — no map and no WebGL context is created
  // until the dialog actually mounts; maplibre itself is already warmed on idle
  // by route-prefetch.
  useEffect(() => {
    void import('./route-preview-map');
    void import('./route-map');
    void import('./nearby-explorer-map');
  }, []);

  // Register contextual actions with mobile navbar (Action Bubble)
  const navbarConfig = useMemo(() => {
    const actions: NavbarActionItem[] = [
      ...(trackers.length > 0
        ? [
            {
              id: 'reorder',
              label: 'Manage Trackers',
              icon: RiEqualizer2Line,
              variant: 'gray' as const,
              onClick: () => {
                setReorderDefaultTab('all');
                setShowReorderDialog(true);
              },
            },
          ]
        : []),
      {
        id: 'nearby',
        label: 'Show Nearby',
        icon: RiMapPinLine,
        variant: 'emerald' as const,
        onClick: () => openNearby(true),
      },
      {
        id: 'new-trip',
        label: 'New Trip',
        icon: RiNavigationLine,
        variant: 'blue' as const,
        onClick: () => {
          setEditingTrip(undefined);
          setCreateTripOpen(true);
        },
      },
      {
        id: 'add-tracker',
        label: 'Add Tracker',
        icon: RiAddLine,
        variant: 'primary' as const,
        onClick: () => setShowAdd(true),
      },
    ];
    return { icon: RiAddLine, actions };
  }, [trackers.length, openNearby]);

  useSetNavbarActions(navbarConfig);

  // Best-effort restore: the deleted row is gone (encrypted fields included),
  // so undo re-creates the tracker from the captured client copy — same stops,
  // direction, and favorite flag. Position in the manual order is not kept;
  // the restored tracker lands at the end of the list.
  const undoDelete = async (tracker: TransitTracker) => {
    try {
      const stops = getTrackerStops(tracker);
      const restored = await addTracker.mutateAsync({
        route_id: tracker.route_id,
        route_short_name: tracker.route_short_name ?? '',
        route_long_name: tracker.route_long_name ?? '',
        route_type: tracker.route_type ?? '',
        route_color: tracker.route_color ?? '',
        route_text_color: tracker.route_text_color ?? '',
        stop_id: tracker.stop_id,
        stop_name: tracker.stop_name,
        headsign: tracker.headsign ?? '',
        stops,
      });
      if (stops.length > 1) {
        await updateTrackerStops.mutateAsync({ id: restored.id, stops });
      }
      if (tracker.is_favorite) {
        await toggleFavorite.mutateAsync(restored.id);
      }
      toast.success('Tracker restored');
    } catch (err) {
      toast.error('Could not restore tracker', { description: getErrorMessage(err) });
    }
  };

  const handleDelete = async (tracker: TransitTracker) => {
    try {
      await deleteTracker.mutateAsync(tracker.id);
      toast.success('Tracker removed', {
        description: 'Moved to the end of your list if you undo.',
        action: { label: 'Undo', onClick: () => void undoDelete(tracker) },
        duration: 8000,
      });
    } catch (err) {
      toast.error('Failed to remove tracker', { description: getErrorMessage(err) });
    }
  };

  const handleToggleFavorite = async (tracker: TransitTracker) => {
    if (!tracker.is_favorite && favoriteTrackers.length >= MAX_FAVORITE_TRACKERS) {
      toast.error(`Maximum of ${MAX_FAVORITE_TRACKERS} favorite trackers allowed.`);
      return;
    }
    setFavoriteBusyId(tracker.id);
    try {
      const next = await toggleFavorite.mutateAsync(tracker.id);
      toast.success(next ? 'Added to dashboard favorites' : 'Removed from dashboard favorites');
    } catch (err) {
      toast.error('Could not update favorite', { description: getErrorMessage(err) });
    } finally {
      setFavoriteBusyId(null);
    }
  };

  const renderTrackerCard = (tracker: TransitTracker) => (
    <div key={tracker.id} className="min-w-0">
      <TransitCard
        tracker={tracker}
        onOpen={liveTracker.openWith}
        onHasDeparturesChange={handleHasDeparturesChange}
        onPendingChange={handlePendingChange}
      />
    </div>
  );

  const dataPending = firstLoad || trackers.some((t) => pendingMap[t.id] ?? true);
  const coverMode: CoverMode | null =
    dataPending && mapPending ? 'all' : dataPending ? 'content' : mapPending ? 'map' : null;
  const coverLabel = coverMode === 'map' ? 'Loading map…' : firstLoad || coverMode === 'all' ? 'Loading transit…' : 'Updating departures…';
  const sectionsCovered = coverMode === 'content' || coverMode === 'all';
  // One cover per region; only the matching one shows, each fades on its own.
  // Trackers and trips can run far below the fold, so their cover pins one
  // large orb near the top of the screen instead of centring it in the card.
  const renderCover = (region: CoverMode) => (
    <LoadingOverlay
      show={coverMode === region}
      maxDurationMs={15_000}
      label={coverLabel}
      indicator={
        <ThinkingOrb state="solving" size={64} className={region === 'map' ? undefined : 'scale-[1.75]'} />
      }
      className={cn('z-30', region !== 'map' && 'justify-start pt-24')}
      contentClassName={region === 'map' ? undefined : 'sticky top-[30vh] gap-10'}
    />
  );

  return (
    <>
      <h1 className="sr-only">Transit</h1>
      {isOffline && (
        <div className="mx-auto w-full max-w-3xl">
          <OfflineBanner message="No internet connection — live arrivals may be stale" badgeLabel="Offline" />
        </div>
      )}

      {/* One card: the map around you (tap for the nearby sheet), then your
          trackers as stop monitors, then your trips. */}
      <Reveal as="section" index={0} className="relative mx-auto w-full min-w-0 max-w-3xl overflow-hidden rounded-3xl card-glass">
        <div className="relative">
        <NearbyMapStrip
          className="lg:h-[220px]"
          status={nearbyStops.status}
          position={nearbyStops.position}
          stops={peekStops}
          onOpen={() => openNearby(true)}
          onLocate={nearbyStops.refreshLocation}
          onMapPendingChange={setMapPending}
          showLoadingOverlay={false}
        />
        {renderCover('map')}
        </div>

        <div className="relative">

        <div className="relative px-3 pt-11 pb-4 sm:px-6 sm:pt-16 sm:pb-6">
          <SectionStamp
            title="Trackers"
            caption={`${trackers.length} ${trackers.length === 1 ? 'line' : 'lines'}`}
            covered={sectionsCovered}
            className="-top-7 -rotate-3"
          />
          {/* Phones add and reorder from the navbar's + menu; desktop has no
              navbar button, so the actions live here. */}
          <div className="absolute right-4 top-3 hidden items-center gap-1.5 sm:flex sm:right-6">
            {trackers.length > 0 && (
              <Button
                variant="gray"
                onClick={() => {
                  setReorderDefaultTab('all');
                  setShowReorderDialog(true);
                }}
                className="h-9 px-3.5 text-xs rounded-full"
              >
                <RiEqualizer2Line className="w-3.5 h-3.5" />
                <span className="hidden lg:inline">Manage</span>
              </Button>
            )}
            <Button variant="primary" onClick={() => setShowAdd(true)} className="h-9 px-4 text-xs rounded-full">
              <RiAddLine className="w-3.5 h-3.5" />
              <span className="hidden lg:inline">Add tracker</span>
            </Button>
          </div>

          {trackers.length === 0 ? (
            <EmptyState className="mt-0 sm:mt-0" emoji="🚋" title="No trackers yet" />
          ) : allInactive ? (
            /* When all tracked lines are off-service (e.g. late night schedule) */
            <div className="space-y-3">
              <div className="flex items-center gap-3 px-1">
                <div className="w-9 h-9 rounded-full bg-indigo-500/10 border border-indigo-500/20 flex items-center justify-center shrink-0">
                  <SleepingIcon className="w-4 h-4 text-indigo-400 dark:text-indigo-300" />
                </div>
                <div>
                  <p className="text-sm font-bold text-primary">All tracked lines are currently off-service</p>
                  <p className="text-xs text-muted">No departures are running right now.</p>
                </div>
              </div>
              <TrackerGrid>{trackers.map(renderTrackerCard)}</TrackerGrid>
            </div>
          ) : (
            <>
              <TrackerGrid>{activeTrackers.map(renderTrackerCard)}</TrackerGrid>

              {/* Lines with no upcoming departures fold into one quiet row. */}
              {inactiveTrackers.length > 0 && (
                <div className="mt-2.5 mb-3">
                  <button
                    type="button"
                    onClick={toggleShowInactive}
                    aria-expanded={showInactive}
                    className="flex w-full cursor-pointer flex-col gap-2 rounded-xl px-1 py-2 text-left transition-colors hover:bg-surface-overlay focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary"
                  >
                    <span className="flex w-full items-center gap-2">
                      <span className="flex-1 text-sm font-medium text-secondary">Not running right now</span>
                      <RiArrowDownSLine
                        className={cn('h-4 w-4 shrink-0 text-muted transition-transform duration-200', showInactive && 'rotate-180')}
                        aria-hidden="true"
                      />
                    </span>
                    {/* Any number of lines wraps instead of squeezing the label. */}
                    <span className="flex flex-wrap items-center gap-1">
                      {inactiveTrackers.map((t) => (
                        <RouteBadge
                          key={t.id}
                          size="xs"
                          name={formatRouteDisplayName(t.route_short_name, t.route_id)}
                          color={t.route_color}
                          textColor={t.route_text_color}
                        />
                      ))}
                    </span>
                  </button>

                  {/* Inactive monitors stay mounted (hidden) so their arrivals keep polling */}
                  <TrackerGrid className={cn('mt-2', !showInactive && 'hidden')}>
                    {inactiveTrackers.map(renderTrackerCard)}
                  </TrackerGrid>
                </div>
              )}
            </>
          )}
        </div>

        <div className="relative border-t border-subtle px-3 pt-11 pb-4 sm:px-6 sm:pt-16 sm:pb-6">
          <SectionStamp
            title="Trips"
            caption={tripCount === 0 ? 'none yet' : `${tripCount} saved`}
            covered={sectionsCovered}
            className="-top-4 rotate-3"
          />
          <div className="absolute right-4 top-3 hidden sm:block sm:right-6">
            <Button
              variant="blue"
              onClick={() => { setEditingTrip(undefined); setCreateTripOpen(true); }}
              className="h-9 px-4 text-xs rounded-full"
            >
              <RiAddLine className="w-3.5 h-3.5" />
              <span className="hidden lg:inline">New trip</span>
            </Button>
          </div>

          <TripPlannerSection onOpenDetail={detail.openWith} />
        </div>

        {renderCover('content')}
        </div>
        {renderCover('all')}
      </Reveal>

      <AddTrackerDialog open={showAdd} onClose={() => setShowAdd(false)} existing={trackers} />
      <EditTrackerDialog
        open={editTracker.open}
        onClose={() => editTracker.onOpenChange(false)}
        onExited={editTracker.onExited}
        tracker={editTracker.value}
        onDelete={handleDelete}
      />
      <LivePositionDialog
        open={liveTracker.open}
        tracker={liveTracker.value}
        onClose={() => liveTracker.onOpenChange(false)}
        onExited={liveTracker.onExited}
      />
      <ReorderFavoritesDialog
        open={showReorderDialog}
        onClose={() => setShowReorderDialog(false)}
        onToggleFavorite={handleToggleFavorite}
        favoriteBusyId={favoriteBusyId}
        onEdit={(tracker) => {
          setShowReorderDialog(false);
          editTracker.openWith(tracker);
        }}
        trackers={trackers}
        defaultTab={reorderDefaultTab}
      />
      <CreateTripDialog
        open={createTripOpen || !!editingTrip}
        onOpenChange={(open) => {
          if (!open) { setCreateTripOpen(false); setEditingTrip(undefined); }
        }}
        editingTrip={editingTrip}
      />
      <TripDetailDialog
        open={detail.open}
        trip={detail.value}
        onOpenChange={detail.onOpenChange}
        onExited={detail.onExited}
        onEdit={(trip) => { detail.onOpenChange(false); setEditingTrip(trip); }}
      />

      {nearby.value !== null && (
        <NearbyExplorerDialog
          open={nearby.open}
          onClose={() => nearby.onOpenChange(false)}
          onExited={nearby.onExited}
        />
      )}
    </>
  );
}
