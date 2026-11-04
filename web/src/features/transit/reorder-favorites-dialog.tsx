import { useId, useMemo, useState, type KeyboardEvent } from 'react';
import { Reorder, useDragControls, useReducedMotion } from 'framer-motion';
import { RiSortNumberAsc, RiDraggable, RiLoader4Line, RiStarLine, RiStarFill, RiArrowRightSLine, RiStackLine } from '@remixicon/react';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { ListStatus } from './tracker-list-row';
import { SheetBar, SheetFooter, SHEET_GUTTER } from '@/features/trips/sheet-chrome';
import { RouteBadge } from './route-badge';
import { FormattedRouteTitle } from './formatted-route-title';
import { formatRouteDisplayName, getTrackerStops, MAX_FAVORITE_TRACKERS } from './transit-utils';
import {
  applyFavoriteOrder,
  reorderAllSlots,
  reorderFavoriteSlots,
  sortTrackersByNumber,
} from './reorder-favorites-utils';
import { useReorderTransitTrackers, useTransitTrackers } from './use-transit';
import type { TransitTracker } from './use-transit';
import { vibrate } from '@/lib/haptics';
import { EASE_OUT } from '@/components/ui/motion-tokens';
import { cn } from '@/lib/utils';
import { toast } from 'sonner';
import { getErrorMessage } from '@/lib/error-utils';

export interface ReorderFavoritesDialogProps {
  open: boolean;
  onClose: () => void;
  trackers?: TransitTracker[];
  defaultTab?: 'all' | 'favorites';
  /** Pins / unpins a tracker on the dashboard (saved immediately). */
  onToggleFavorite?: (tracker: TransitTracker) => void;
  favoriteBusyId?: string | null;
  /** Opens the stop editor for a tracker. */
  onEdit?: (tracker: TransitTracker) => void;
}

const sameOrder = (a: TransitTracker[], b: TransitTracker[]) =>
  a.length === b.length && a.every((t, i) => t.id === b[i].id);

export function ReorderFavoritesDialog({
  open,
  onClose,
  trackers: initialTrackers,
  defaultTab = 'all',
  onToggleFavorite,
  favoriteBusyId = null,
  onEdit,
}: ReorderFavoritesDialogProps) {
  const { data: allFetchedTrackers = [] } = useTransitTrackers();
  const [activeTab, setActiveTab] = useState<'all' | 'favorites'>(defaultTab);
  const titleId = useId();
  const reduceMotion = useReducedMotion();

  const sourceTrackers = useMemo(() => {
    if (initialTrackers && initialTrackers.length >= allFetchedTrackers.length) {
      return initialTrackers;
    }
    if (allFetchedTrackers.length > 0) {
      return allFetchedTrackers;
    }
    return initialTrackers || [];
  }, [initialTrackers, allFetchedTrackers]);

  const [allItems, setAllItems] = useState<TransitTracker[]>(sourceTrackers);
  const reorderMutation = useReorderTransitTrackers();

  // Seed the working order from the current trackers each time the dialog
  // opens (or the source/default tab changes) — adjusted during render so a
  // stale order can never paint.
  const [prevSync, setPrevSync] = useState({ open, defaultTab, sourceTrackers });
  if (
    open !== prevSync.open ||
    defaultTab !== prevSync.defaultTab ||
    sourceTrackers !== prevSync.sourceTrackers
  ) {
    const opening = open && !prevSync.open;
    setPrevSync({ open, defaultTab, sourceTrackers });
    if (opening || (open && defaultTab !== prevSync.defaultTab)) {
      setAllItems(sourceTrackers);
      setActiveTab(defaultTab);
    } else if (open) {
      // A refetch while open (e.g. after a star toggle) refreshes each row's
      // data but keeps the unsaved order; added/removed trackers follow along.
      const fresh = new Map(sourceTrackers.map((t) => [t.id, t]));
      setAllItems((prev) => [
        ...prev.filter((t) => fresh.has(t.id)).map((t) => fresh.get(t.id) as TransitTracker),
        ...sourceTrackers.filter((t) => !prev.some((p) => p.id === t.id)),
      ]);
    }
  }

  const favItems = useMemo(() => allItems.filter((t) => t.is_favorite), [allItems]);
  const isFavoritesTab = activeTab === 'favorites';
  const displayList = isFavoritesTab ? favItems : allItems;
  const isPending = reorderMutation.isPending;
  const isDirty = !sameOrder(allItems, sourceTrackers);
  const favoritesFull = favItems.length >= MAX_FAVORITE_TRACKERS;

  const handleReorder = (next: TransitTracker[]) => {
    setAllItems((prev) => (isFavoritesTab ? applyFavoriteOrder(prev, next) : next));
  };

  const moveItem = (index: number, direction: -1 | 1) => {
    setAllItems((prev) =>
      isFavoritesTab ? reorderFavoriteSlots(prev, index, direction) : reorderAllSlots(prev, index, direction)
    );
  };

  const handleSortByNumber = () => {
    setAllItems((prev) =>
      isFavoritesTab ? applyFavoriteOrder(prev, sortTrackersByNumber(favItems)) : sortTrackersByNumber(prev)
    );
    vibrate(10);
  };

  const handleSave = async () => {
    try {
      await reorderMutation.mutateAsync(allItems.map((t) => t.id));
      toast.success(isFavoritesTab ? 'Favorites order updated' : 'Transit trackers order updated');
      onClose();
    } catch (err) {
      toast.error('Failed to update order', {
        description: getErrorMessage(err),
      });
    }
  };

  return (
    <Dialog open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <DialogContent
        edgeToEdge
        aria-labelledby={titleId}
        className="sm:max-w-md h-[80vh] max-h-[640px] min-h-[460px] rounded-[28px] p-0 overflow-hidden flex flex-col gap-0"
      >
        <SheetBar
          leading="close"
          onLeading={onClose}
          titleId={titleId}
          title="Manage trackers"
          accessory={
            <Tabs value={activeTab} onValueChange={(val) => setActiveTab(val as 'all' | 'favorites')}>
              <TabsList className="grid h-auto w-full grid-cols-2">
                <TabsTrigger value="all" className="h-9 text-sm">
                  All
                  <span className="tabular-nums opacity-60">{allItems.length}</span>
                </TabsTrigger>
                <TabsTrigger value="favorites" className="h-9 text-sm">
                  Dashboard
                  <span className="tabular-nums opacity-60">
                    {favItems.length}/{MAX_FAVORITE_TRACKERS}
                  </span>
                </TabsTrigger>
              </TabsList>
            </Tabs>
          }
        />

        {displayList.length === 0 ? (
          <ListStatus className={cn('flex-1 justify-center', SHEET_GUTTER)}>
            <div className="flex h-12 w-12 items-center justify-center rounded-full bg-surface-overlay text-muted">
              {isFavoritesTab ? <RiStarLine className="h-6 w-6 text-amber-400" /> : <RiStackLine className="h-6 w-6" />}
            </div>
            <div>
              <p className="text-sm font-bold text-primary">
                {isFavoritesTab ? 'Nothing on your dashboard yet' : 'No trackers saved yet'}
              </p>
              <p className="mx-auto mt-1 max-w-[260px] text-xs text-muted">
                {isFavoritesTab
                  ? 'Star a tracker under All to pin it to your dashboard.'
                  : 'Add trackers from the transit page to organize them here.'}
              </p>
            </div>
          </ListStatus>
        ) : (
          <>
            <div className={cn('flex shrink-0 items-center justify-between gap-3 pb-2 pt-3', SHEET_GUTTER)}>
              <p className="text-xs text-muted">
                {isFavoritesTab ? 'The order they appear on your dashboard' : 'Drag to reorder · star to pin'}
              </p>
              <button
                type="button"
                onClick={handleSortByNumber}
                disabled={displayList.length <= 1 || isPending}
                className="-mr-2 flex h-8 shrink-0 items-center gap-1 rounded-full px-2.5 text-xs font-semibold text-accent-primary transition-colors hover:bg-accent-primary/10 cursor-pointer active:scale-95 disabled:cursor-not-allowed disabled:opacity-40 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary"
                title="Sort lowest to highest by line number"
              >
                <RiSortNumberAsc className="h-3.5 w-3.5" aria-hidden="true" />
                Sort by line
              </button>
            </div>

            {/* Keyed by tab: a row in both lists would otherwise keep its
                layout and fly from its old slot to its new one. */}
            <Reorder.Group
              key={activeTab}
              as="ul"
              axis="y"
              layoutScroll
              values={displayList}
              onReorder={handleReorder}
              initial={reduceMotion ? false : { opacity: 0 }}
              animate={{ opacity: 1 }}
              transition={{ duration: 0.18, ease: EASE_OUT }}
              className={cn('min-h-0 flex-1 space-y-2 overflow-y-auto overscroll-contain no-scrollbar pb-4', SHEET_GUTTER)}
            >
              {displayList.map((tracker, idx) => (
                <TrackerRow
                  key={tracker.id}
                  tracker={tracker}
                  index={idx}
                  count={displayList.length}
                  disabled={isPending}
                  onMove={(direction) => moveItem(idx, direction)}
                  onToggleFavorite={onToggleFavorite}
                  favoriteBusy={favoriteBusyId === tracker.id}
                  favoriteLocked={favoritesFull && !tracker.is_favorite}
                  onEdit={onEdit}
                />
              ))}
            </Reorder.Group>
          </>
        )}

        <SheetFooter>
          <Button
            type="button"
            size="lg"
            className="h-12 w-full rounded-2xl"
            onClick={handleSave}
            disabled={isPending || !isDirty}
          >
            {isPending ? (
              <RiLoader4Line className="h-4 w-4 animate-spin" aria-hidden="true" />
            ) : isDirty ? (
              'Save order'
            ) : (
              'No changes'
            )}
          </Button>
        </SheetFooter>
      </DialogContent>
    </Dialog>
  );
}

interface TrackerRowProps {
  tracker: TransitTracker;
  index: number;
  count: number;
  disabled: boolean;
  onMove: (direction: -1 | 1) => void;
  onToggleFavorite?: (tracker: TransitTracker) => void;
  favoriteBusy: boolean;
  /** The dashboard is full and this tracker is not on it. */
  favoriteLocked: boolean;
  onEdit?: (tracker: TransitTracker) => void;
}

/** Neighbours make room and the dropped row lands on this spring; Framer's
 *  default layout spring is soft enough that consecutive swaps overlap. */
const ROW_SPRING = { type: 'spring', stiffness: 700, damping: 50, mass: 0.8 } as const;
const ROW_SNAP_BACK = { bounceStiffness: 700, bounceDamping: 50 };

const ROW_ICON_BUTTON =
  'flex h-10 w-10 shrink-0 items-center justify-center rounded-full transition-colors cursor-pointer active:scale-95 disabled:cursor-not-allowed disabled:opacity-30 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary';

/**
 * One draggable tracker. Only the grip starts a drag, so the row still
 * scrolls under a finger; the grip also moves the row with the arrow keys.
 */
function TrackerRow({
  tracker,
  index,
  count,
  disabled,
  onMove,
  onToggleFavorite,
  favoriteBusy,
  favoriteLocked,
  onEdit,
}: TrackerRowProps) {
  const dragControls = useDragControls();
  const reduceMotion = useReducedMotion();

  const stops = getTrackerStops(tracker);
  const isMultiStop = stops.length > 1;
  const displayName = formatRouteDisplayName(tracker.route_short_name, tracker.route_id);
  const title = isMultiStop ? tracker.route_long_name || displayName : stops[0]?.stop_name || tracker.stop_name;
  const subtitle = isMultiStop
    ? `${stops.length} stops · ${stops.map((s) => s.stop_name).join(', ')}`
    : stops[0]?.headsign
      ? `Towards ${stops[0].headsign}`
      : null;

  const handleKeyDown = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (disabled) return;
    if (e.key === 'ArrowUp' && index > 0) {
      e.preventDefault();
      onMove(-1);
    } else if (e.key === 'ArrowDown' && index < count - 1) {
      e.preventDefault();
      onMove(1);
    }
  };

  const favoriteLabel = tracker.is_favorite
    ? 'Remove from dashboard'
    : favoriteLocked
      ? `Dashboard is full (${MAX_FAVORITE_TRACKERS} max)`
      : 'Pin to dashboard';

  return (
    <Reorder.Item
      as="li"
      value={tracker}
      dragListener={false}
      dragControls={dragControls}
      onDragStart={() => vibrate(10)}
      transition={reduceMotion ? { duration: 0 } : ROW_SPRING}
      dragTransition={ROW_SNAP_BACK}
      whileDrag={reduceMotion ? undefined : { scale: 1.02, boxShadow: '0 12px 32px -12px rgba(0,0,0,0.35)' }}
      className="relative flex items-center gap-1 rounded-2xl card-glass py-1.5 pl-1 pr-1.5 select-none"
    >
      <button
        type="button"
        disabled={disabled || count <= 1}
        onPointerDown={(e) => !disabled && dragControls.start(e)}
        onKeyDown={handleKeyDown}
        aria-label={`Reorder ${displayName}, position ${index + 1} of ${count}. Use arrow keys to move.`}
        className={cn(ROW_ICON_BUTTON, 'w-8 touch-none text-muted hover:text-secondary cursor-grab active:cursor-grabbing')}
      >
        <RiDraggable className="h-5 w-5" aria-hidden="true" />
      </button>

      <RouteBadge size="sm" name={displayName} color={tracker.route_color} textColor={tracker.route_text_color} />

      <div className="ml-2 min-w-0 flex-1 py-1">
        <p className="truncate text-sm font-semibold text-primary">
          <FormattedRouteTitle title={title} />
        </p>
        {subtitle && <p className="mt-0.5 truncate text-xs text-muted">{subtitle}</p>}
      </div>

      {onToggleFavorite && (
        <button
          type="button"
          onClick={() => onToggleFavorite(tracker)}
          disabled={favoriteBusy || favoriteLocked}
          aria-pressed={tracker.is_favorite}
          aria-label={favoriteLabel}
          title={favoriteLabel}
          className={cn(
            ROW_ICON_BUTTON,
            tracker.is_favorite ? 'text-amber-400 hover:bg-amber-500/10' : 'text-muted hover:bg-surface-overlay hover:text-primary'
          )}
        >
          {favoriteBusy ? (
            <RiLoader4Line className="h-5 w-5 animate-spin" aria-hidden="true" />
          ) : tracker.is_favorite ? (
            <RiStarFill className="h-5 w-5" aria-hidden="true" />
          ) : (
            <RiStarLine className="h-5 w-5" aria-hidden="true" />
          )}
        </button>
      )}
      {onEdit && (
        <button
          type="button"
          onClick={() => onEdit(tracker)}
          aria-label={`Edit ${displayName} tracker`}
          title="Manage stops or remove"
          className={cn(ROW_ICON_BUTTON, 'w-8 text-muted hover:bg-surface-overlay hover:text-primary')}
        >
          <RiArrowRightSLine className="h-5 w-5" aria-hidden="true" />
        </button>
      )}
    </Reorder.Item>
  );
}
