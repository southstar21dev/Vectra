import { useCallback, useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import {
  animate,
  motion,
  useDragControls,
  useMotionValue,
  useMotionValueEvent,
  type PanInfo,
} from 'framer-motion';
import { RiArrowRightLine, RiTimeLine } from '@remixicon/react';
import { cn } from '@/lib/utils';
import { withHash } from './map/colors';
import { LiveSignalIcon } from './transit-card';
import { RouteBadge } from './route-badge';
import {
  formatNearbyCountdown,
  type RadarDepartureItem,
} from './nearby-stops';
import { createClickSuppressor, shouldPullSheet } from './nearby-sheet-gesture';

const SHEET_FRACTION = 0.72;
const PEEK_VISIBLE_FRACTION = 0.34;

const MODE_CHIPS = [
  { id: 'ALL', label: 'All' },
  { id: 'SUBWAY', label: 'Metro' },
  { id: 'TRAM', label: 'Tram' },
  { id: 'BUS', label: 'Bus' },
  { id: 'TROLLEYBUS', label: 'Trolley' },
] as const;

export interface NearbySelectionKey {
  stopId: string;
  routeId: string;
  headsign: string;
  shortName: string;
  color: string;
  textColor: string;
}

export interface NearbyDeparturesSheetProps {
  feed: RadarDepartureItem[];
  selection: NearbySelectionKey | null;
  onSelectItem: (item: RadarDepartureItem) => void;
  modeFilter: string[];
  onToggleMode: (id: string) => void;
  arrivalsEnabled: boolean;
  isLoading: boolean;
  /** Stops inside the map area the sheet leaves uncovered. */
  visibleStopCount: number;
  /** Stops actually polled for departures (the arrivals cap). */
  polledStopCount: number;
  /**
   * Reports how much viewport height the sheet covers, from the bottom.
   * `settled` is false for the per-frame updates during a drag or spring: the
   * map viewport only needs the settled value, so consumers should avoid
   * React state updates for unsettled frames (a full map re-render on every
   * pointer move makes the drag itself stutter on phones).
   */
  onCoverageChange?: (coveredPx: number, settled: boolean) => void;
}

function useViewportHeight(): number {
  const [height, setHeight] = useState(() =>
    typeof window === 'undefined' ? 800 : window.innerHeight
  );
  useEffect(() => {
    const onResize = () => setHeight(window.innerHeight);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);
  return height;
}

export function NearbyDeparturesSheet({
  feed,
  selection,
  onSelectItem,
  modeFilter,
  onToggleMode,
  arrivalsEnabled,
  isLoading,
  visibleStopCount,
  polledStopCount,
  onCoverageChange,
}: NearbyDeparturesSheetProps) {
  const viewportHeight = useViewportHeight();
  const expandedHeight = Math.round(viewportHeight * SHEET_FRACTION);
  const peekOffset = Math.round(viewportHeight * (SHEET_FRACTION - PEEK_VISIBLE_FRACTION));
  // Open the explorer with the departures sheet already pulled up. Users can
  // still drag it down to the compact map-first state after the first view.
  const y = useMotionValue(0);
  const dragControls = useDragControls();
  const animationRef = useRef<ReturnType<typeof animate> | null>(null);
  const sheetRef = useRef<HTMLDivElement>(null);
  const [feedScrolled, setFeedScrolled] = useState(false);

  const onCoverageChangeRef = useRef(onCoverageChange);

  useEffect(() => {
    onCoverageChangeRef.current = onCoverageChange;
  }, [onCoverageChange]);

  const reportCoverage = useCallback(
    (settled: boolean) => {
      onCoverageChangeRef.current?.(Math.round(expandedHeight - y.get()), settled);
    },
    [expandedHeight, y]
  );

  useEffect(() => {
    // Keep the sheet expanded when the viewport changes (rotation/browser
    // chrome resize); resetting to peek here caused a surprising collapse.
    y.set(0);
  }, [y]);

  // Coverage on mount, on resize/peek-offset changes, on every drag frame
  // (unsettled, so the consumer can skip React work while the finger moves) and
  // on the settled snap position (the map debounces before re-querying).
  useEffect(() => {
    reportCoverage(true);
  }, [reportCoverage]);
  useMotionValueEvent(y, 'change', () => reportCoverage(false));

  const snapTo = useCallback((target: number) => {
    animationRef.current?.stop();
    animationRef.current = animate(y, target, {
      type: 'spring',
      stiffness: 520,
      damping: 44,
      mass: 0.8,
      restDelta: 0.5,
    });
    animationRef.current.then(
      () => reportCoverage(true),
      () => {}
    );
  }, [reportCoverage, y]);

  useEffect(() => () => animationRef.current?.stop(), []);

  const handleDragEnd = (_: unknown, info: PanInfo) => {
    let target = y.get() < peekOffset / 2 ? 0 : peekOffset;
    if (info.velocity.y < -350) target = 0;
    if (info.velocity.y > 350) target = peekOffset;
    snapTo(target);
  };

  const toggleSnap = useCallback(() => {
    snapTo(y.get() > peekOffset / 2 ? 0 : peekOffset);
  }, [peekOffset, snapTo, y]);

  // The grabber and the sheet title are one drag surface. Framer's drag
  // precondition preventDefaults the pointer event, so a tap on the grabber
  // never produces a `click` — the toggle is detected on pointer-up instead,
  // by checking that the press did not move the sheet.
  const dragStartYRef = useRef(0);
  const sheetGestureRef = useRef<{
    pointerId: number;
    startY: number;
    started: boolean;
    feed: HTMLDivElement | null;
  } | null>(null);
  const [suppressNextClick] = useState(createClickSuppressor);
  const beginDrag = (event: ReactPointerEvent<HTMLElement>) => {
    dragStartYRef.current = y.get();
    dragControls.start(event);
  };
  const handleGrabberPointerUp = () => {
    if (Math.abs(y.get() - dragStartYRef.current) > 3) return;
    toggleSnap();
  };

  // The whole sheet is a drag surface. At the expanded snap point, wait for
  // the gesture direction before taking control: upward swipes stay native
  // list scrolling, while a downward swipe at the top of the list pulls the
  // sheet down — including when it starts on a departure card.
  const handleSheetPointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (y.get() > 1) {
      beginDrag(event);
      return;
    }
    const target = event.target instanceof Element
      ? event.target.closest('[data-explorer-sheet-feed]')
      : null;
    const feed = target instanceof HTMLDivElement ? target : null;
    if (feed && feed.scrollTop > 0) return;
    sheetGestureRef.current = {
      pointerId: event.pointerId,
      startY: event.clientY,
      started: false,
      feed,
    };
  };

  const handleSheetPointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    const gesture = sheetGestureRef.current;
    if (!gesture || gesture.pointerId !== event.pointerId) return;
    const deltaY = event.clientY - gesture.startY;
    if (gesture.started) {
      event.preventDefault();
      y.set(Math.min(peekOffset, Math.max(0, deltaY)));
      return;
    }
    if (deltaY < -6) {
      // This is a normal upward list scroll, not a sheet drag.
      sheetGestureRef.current = null;
      return;
    }
    if (shouldPullSheet({
      sheetY: y.get(),
      deltaY,
      feedScrollTop: gesture.feed ? gesture.feed.scrollTop : null,
      threshold: 6,
    })) {
      // Native scrolling owns the gesture until its direction is clear. Once
      // the list is at its top and the finger is moving down, take over
      // manually so the sheet can collapse from a departure card as well.
      event.preventDefault();
      gesture.started = true;
      suppressNextClick.arm();
      event.currentTarget.setPointerCapture(event.pointerId);
    }
  };

  const handleSheetPointerEnd = (event: ReactPointerEvent<HTMLDivElement>) => {
    const gesture = sheetGestureRef.current;
    if (!gesture || gesture.pointerId !== event.pointerId) return;
    if (gesture.started) {
      event.currentTarget.releasePointerCapture?.(event.pointerId);
      snapTo(y.get() < peekOffset / 2 ? 0 : peekOffset);
    }
    sheetGestureRef.current = null;
  };

  // Touch input cannot rely on the pointer handlers alone: with the default
  // `touch-action`, the browser owns the vertical pan and fires `pointercancel`
  // the moment it starts scrolling the list. Cancelling a pointer event cannot
  // suppress that pan, so a pull-down at the top of the list never reaches the
  // pointer handlers and the sheet stays put. This non-passive `touchmove`
  // listener cancels the browser's scroll for exactly the gestures the sheet
  // should own, which keeps the pointer stream alive so the handlers above can
  // drag the sheet. It lives on the sheet root so it also covers a pull that
  // starts on the mode chips.
  useEffect(() => {
    const node = sheetRef.current;
    if (!node) return;
    const onTouchMove = (event: TouchEvent) => {
      if (event.touches.length !== 1) return;
      const gesture = sheetGestureRef.current;
      if (!gesture) return;
      const deltaY = event.touches[0].clientY - gesture.startY;
      const takeOver =
        gesture.started ||
        shouldPullSheet({
          sheetY: y.get(),
          deltaY,
          feedScrollTop: gesture.feed ? gesture.feed.scrollTop : null,
          // Take over before the browser's own scroll slop kicks in.
          threshold: 3,
        });
      if (takeOver && event.cancelable) event.preventDefault();
    };
    node.addEventListener('touchmove', onTouchMove, { passive: false });
    return () => node.removeEventListener('touchmove', onTouchMove);
  }, [y]);

  const groupedFeed = useMemo(() => {
    const groups = new Map<string, RadarDepartureItem[]>();
    for (const item of feed) {
      // One card per route, stop and direction; the next departures for that
      // service are shown together in the ETA column instead of as duplicates.
      const key = `${item.routeId}|${item.stopId}|${item.headsign}`;
      const group = groups.get(key);
      if (group) group.push(item);
      else groups.set(key, [item]);
    }
    return Array.from(groups, ([key, items]) => ({ key, items }));
  }, [feed]);

  const handleDepartureSelect = (item: RadarDepartureItem) => {
    onSelectItem(item);
    // Selecting a route should reveal its line and vehicles instead of leaving
    // the map hidden behind the expanded feed. The compact state remains a
    // usable handle, so the user can pull it back up immediately.
    snapTo(peekOffset);
  };

  return (
    <motion.div
      ref={sheetRef}
      drag="y"
      dragListener={false}
      dragControls={dragControls}
      dragConstraints={{ top: 0, bottom: peekOffset }}
      dragDirectionLock
      dragElastic={0.04}
      dragMomentum={false}
      style={{ y, height: expandedHeight, willChange: 'transform' }}
      onDragEnd={handleDragEnd}
      onPointerDownCapture={suppressNextClick.beginPress}
      onPointerDown={handleSheetPointerDown}
      onPointerMove={handleSheetPointerMove}
      onPointerUp={handleSheetPointerEnd}
      onPointerCancel={handleSheetPointerEnd}
      className="absolute inset-x-0 bottom-0 z-30 flex flex-col rounded-t-[28px] border-t border-black/10 bg-surface-secondary shadow-[0_-24px_60px_rgba(0,0,0,0.22)] dark:border-white/10 dark:shadow-[0_-24px_60px_rgba(0,0,0,0.5)]"
    >
      {/* Drag surface: grabber + title row. `touch-none` stops the browser
          from claiming the vertical drag; the feed list below keeps its own
          native scrolling and the chips row keeps horizontal panning. */}
      <div className="shrink-0 touch-none">
        <button
          type="button"
          onPointerDown={(e) => {
            e.stopPropagation();
            beginDrag(e);
          }}
          onPointerUp={handleGrabberPointerUp}
          aria-label="Toggle departures sheet"
          className="mx-auto flex h-8 w-32 cursor-grab items-center justify-center active:cursor-grabbing"
        >
          <span className="h-1.5 w-11 rounded-full bg-text-secondary/45" />
        </button>

      </div>

      <div className="card-glass mx-3 mt-2 grid shrink-0 grid-cols-5 gap-1 rounded-2xl p-1">
        {MODE_CHIPS.map((chip) => {
          const active = chip.id === 'ALL' ? modeFilter.length === 0 : modeFilter.includes(chip.id);
          return (
            <button
              key={chip.id}
              type="button"
              aria-pressed={active}
              onClick={() => onToggleMode(chip.id)}
              className={cn(
                'flex h-9 min-w-0 w-full items-center justify-center rounded-xl border px-1 text-[11px] font-semibold transition-[color,background-color,border-color,box-shadow,transform,opacity]',
                active
                  ? 'border-tint-blue/30 bg-surface-primary text-tint-blue shadow-sm'
                  : 'border-transparent text-secondary hover:border-subtle hover:bg-surface-primary/60 hover:text-primary'
              )}
            >
              {chip.label}
            </button>
          );
        })}
      </div>

      <div
        data-explorer-sheet-feed
        onScroll={(event) => setFeedScrolled(event.currentTarget.scrollTop > 2)}
        onPointerDown={(event) => {
          // Start the sheet drag directly from the list while it is peeked,
          // before native scrolling can claim an upward swipe on a card.
          if (y.get() > 1) {
            event.stopPropagation();
            beginDrag(event);
          }
        }}
        className="mt-2 min-h-0 flex-1 overflow-y-auto overscroll-y-contain px-3 pb-[calc(env(safe-area-inset-bottom)+0.75rem)]"
        style={
          feedScrolled
            ? {
                WebkitMaskImage: 'linear-gradient(to bottom, transparent 0, black 28px)',
                maskImage: 'linear-gradient(to bottom, transparent 0, black 28px)',
              }
            : undefined
        }
      >
        <div className="relative z-0">
        {!arrivalsEnabled ? (
          <div className="p-6 text-center text-xs text-muted">
            Zoom in to see departures
          </div>
        ) : feed.length === 0 ? (
          <div className="p-6 text-center text-xs text-muted">
            {isLoading
              ? 'Scanning nearby stops…'
              : 'No upcoming departures near this area — pan or zoom in.'}
          </div>
        ) : (
          <div className="space-y-2">
            {groupedFeed.map((group) => {
              const item = group.items[0];
              const isSelected = group.items.some(
                (departure) =>
                  !!selection &&
                  selection.stopId === departure.stopId &&
                  selection.routeId === departure.routeId &&
                  selection.headsign === departure.headsign
              );
              return (
                <button
                  key={group.key}
                  type="button"
                  onClick={(event) => {
                    event.stopPropagation();
                    if (suppressNextClick.consume()) {
                      event.preventDefault();
                      return;
                    }
                    handleDepartureSelect(item);
                  }}
                  className={cn(
                    'group relative flex h-[96px] min-h-0 w-full items-center gap-3 overflow-hidden rounded-2xl border px-3 py-2.5 text-left shadow-sm transition-[background-color,border-color,transform] active:scale-[0.985]',
                    isSelected
                      ? 'border-black/10 bg-black/[0.04] shadow-md dark:border-white/15 dark:bg-white/[0.11]'
                      : 'border-black/[0.08] bg-surface-secondary hover:border-black/15 hover:bg-surface-tertiary dark:border-white/[0.06] dark:hover:border-white/10'
                  )}
                  style={
                    isSelected
                      ? {
                          borderColor: `${withHash(item.color)}80`,
                          backgroundColor: `${withHash(item.color)}18`,
                          boxShadow: `0 5px 18px ${withHash(item.color)}18`,
                        }
                      : undefined
                  }
                >
                  <span
                    aria-hidden
                    className="absolute inset-y-2 left-0 w-1 rounded-r-full"
                    style={{ background: `#${item.color}` }}
                  />                  <span className="flex h-12 w-12 shrink-0 items-center justify-center">
                    <RouteBadge
                      name={item.shortName}
                      color={item.color}
                      textColor={item.textColor}
                      size="lg"
                    />
                  </span>
                  <span className="flex min-w-0 flex-1 self-stretch flex-col justify-center py-0.5">
                    <span className="flex items-center gap-1.5">
                      <span className="flex h-3 w-3 shrink-0 items-center justify-center">
                        <RiArrowRightLine className="h-3 w-3 text-secondary" />
                      </span>
                      <span className="truncate text-[11px] font-bold text-primary">
                        {item.headsign || 'Any direction'}
                      </span>
                    </span>
                    <span className="mt-1 flex min-w-0 items-center gap-1.5 truncate text-[10px] font-medium text-muted">
                      <span className="flex h-3 w-3 shrink-0 items-center justify-center">
                        <span aria-hidden className="h-1.5 w-1.5 rounded-full bg-text-secondary/60" />
                      </span>
                      <span className="truncate">{item.stopName}</span>
                    </span>
                  </span>
                  <span
                    className={cn(
                      'ml-auto flex w-fit shrink-0 flex-nowrap items-center gap-2 whitespace-nowrap font-mono text-sm font-black tabular-nums tracking-tight',
                      item.isPredicted? 'text-tint-emerald'
                                : 'text-primary/80'
                    )}
                    aria-label={item.isPredicted ? 'Live arrival' : 'Scheduled arrival'}
                  >
                    <span className="flex w-fit flex-col items-start gap-1.5">
                      {Array.from({ length: 3 }, (_, index) => group.items[index]).map((departure, index) => (
                        departure ? (
                          <span
                            key={departure.id}
                            className={cn(
                              'nearby-eta-row font-mono text-sm font-black tabular-nums tracking-tight',
                              departure.isPredicted
                                ? 'text-tint-emerald'
                                : 'text-primary/80'
                            )}
                          >
                            <span className="flex h-3.5 w-3.5 shrink-0 items-center justify-center">
                              {departure.isPredicted ? (
                                <LiveSignalIcon className="h-3.5 w-3.5" />
                              ) : (
                                <RiTimeLine className="h-3.5 w-3.5" />
                              )}
                            </span>
                            <span className="text-left">{formatNearbyCountdown(departure.countdownMinutes)}</span>
                          </span>
                        ) : (
                          <span key={`empty-eta-${index}`} aria-hidden className="invisible flex w-full items-center justify-end gap-2 whitespace-nowrap font-mono text-sm font-black">
                            <span className="flex h-3.5 w-3.5 shrink-0 items-center justify-center">
                              <RiTimeLine className="h-3.5 w-3.5" />
                            </span>
                            <span className="text-left">0'</span>
                          </span>
                        )
                      ))}
                    </span>
                  </span>
                </button>
              );
            })}
            {visibleStopCount > polledStopCount && (
              <p className="px-2 pt-2 text-center text-[10px] font-medium text-muted">
                Showing departures from the {polledStopCount} closest stops of{' '}
                {visibleStopCount} in view
              </p>
            )}
          </div>
        )}
        </div>
      </div>
    </motion.div>
  );
}
