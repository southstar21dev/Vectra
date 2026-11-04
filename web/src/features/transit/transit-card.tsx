import { useEffect, useMemo } from 'react';
import { RiStarFill, RiAlertLine, RiTimeLine } from '@remixicon/react';
import type { TransitTracker } from './use-transit';
import type { Arrival } from './types';
import { cn } from '@/lib/utils';
import { RouteBadge } from './route-badge';
import { FormattedRouteTitle } from './formatted-route-title';
import {
  arrivalsStaleNow,
  formatRouteDisplayName,
  getTrackerStops,
  isArrivalsStale,
  parseAlerts,
  parseStopArrivals,
} from './transit-utils';
import { buildMonitorGrid, formatMonitorTime, minutesUntil } from './tracker-monitor';
import { useMultiStopArrivals, useNow } from './use-transit';
import { useOnlineStatus } from '@/hooks/use-online-status';
import { useReducedMotion } from 'framer-motion';
import { SleepingIcon } from '@/components/ui/sleeping-icon';

interface TransitCardProps {
  tracker: TransitTracker;
  onOpen: (tracker: TransitTracker) => void;
  onHasDeparturesChange?: (trackerId: string, hasDepartures: boolean) => void;
  /** True while this tracker has no times it can stand behind yet: still
   *  loading, or its snapshot went stale while online and a refresh is due. */
  onPendingChange?: (trackerId: string, pending: boolean) => void;
  /** The faint favorite star; off where every tracker is a favorite anyway. */
  showFavoriteMark?: boolean;
}

/**
 * Live-signal indicator: the classic angled wifi glyph (rotated 45° so the dot
 * sits bottom-left with the fan sweeping up-right) whose signal streaks pulse
 * outward from the dot. Stands in for the old "predicted" text label.
 */
export function LiveSignalIcon({ className, animate = true }: { className?: string; animate?: boolean }) {
  // prefers-reduced-motion: streaks render static at full opacity — the
  // "predicted/live" state stays fully legible without the outward pulse.
  const reduceMotion = useReducedMotion();
  const streak = animate && !reduceMotion ? 'animate-[wifi-streak_1.8s_ease-in-out_infinite]' : undefined;
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={cn('rotate-45 shrink-0', className)}
      aria-hidden="true"
    >
      {/* Dot — steady anchor */}
      <path d="M12 20h.01" className="opacity-70" />
      {/* Signal streaks — cascade from the innermost to the outermost */}
      <path d="M8.5 16.429a5 5 0 0 1 7 0" className={streak} style={{ animationDelay: '0.15s' }} />
      <path d="M5 12.859a10 10 0 0 1 14 0" className={streak} style={{ animationDelay: '0.45s' }} />
      <path d="M2 8.82a15 15 0 0 1 20 0" className={streak} style={{ animationDelay: '0.75s' }} />
    </svg>
  );
}

/** One departure cell: a sleeping Zzz when nothing is coming; otherwise the
 *  time behind a live-signal glyph (live) or a clock (timetable only). */
function MonitorTime({
  arrival,
  now,
  frozen,
  metro,
}: {
  arrival: Arrival | null;
  now: number;
  frozen: boolean;
  /** Metro live times carry a purple signal instead of the green one. */
  metro: boolean;
}) {
  if (!arrival) {
    return (
      <span className="flex justify-end" title="No upcoming departures">
        <SleepingIcon className="h-4 w-4 text-(--monitor-muted)" />
        <span className="sr-only">no upcoming departures</span>
      </span>
    );
  }
  const minutes = minutesUntil(arrival.arrivalMs, now);
  // The metro feed carries no vehicle positions, but metro runs to its
  // timetable closely enough to read as live: purple signal, bright time.
  const trusted = arrival.isPredicted || metro;
  const live = trusted && !frozen;
  return (
    <span className={cn('flex items-center justify-end gap-2', frozen && 'opacity-50')}>
      {live ? (
        <LiveSignalIcon className={cn('h-3 w-3', metro ? 'text-tint-purple' : 'text-tint-emerald')} />
      ) : (
        <RiTimeLine className="h-3 w-3 shrink-0 text-(--monitor-muted)" aria-hidden="true" />
      )}
      <span
        className={cn(
          'text-[17px] leading-none tracking-tight tabular-nums',
          minutes <= 0 ? 'font-medium' : 'font-extrabold',
          minutes < 2 && live
            ? 'text-tint-emerald'
            : live
              ? 'text-(--monitor-live)'
              : 'text-(--monitor-sched)'
        )}
      >
        {/* Offline or an old snapshot is never announced as live. */}
        <span className="sr-only">{live ? 'live ' : trusted ? 'last known ' : 'scheduled '}</span>
        {formatMonitorTime(arrival.arrivalMs, now)}
      </span>
    </span>
  );
}

/**
 * A tracker as a stop display: the line badge on top, directions as columns,
 * every tracked stop as a row with its next departure per direction. Each row
 * opens the live dialog at that stop.
 */
export function TransitCard({
  tracker,
  onOpen,
  onHasDeparturesChange,
  onPendingChange,
  showFavoriteMark = true,
}: TransitCardProps) {
  const now = useNow(1000);
  const isOffline = !useOnlineStatus();
  const stops = useMemo(() => getTrackerStops(tracker), [tracker]);
  const stopIds = useMemo(() => stops.map((s) => s.stop_id), [stops]);
  const arrivalsMap = useMultiStopArrivals(stopIds);

  const pages = useMemo(
    () =>
      stops.map((stop) => {
        const entry = arrivalsMap[stop.stop_id];
        const headsign = stop.headsign || (stops.length === 1 ? tracker.headsign : '') || null;
        return {
          stop,
          headsign,
          data: entry?.data,
          isError: entry?.isError ?? false,
          dataUpdatedAt: entry?.dataUpdatedAt ?? 0,
          arrivals: parseStopArrivals(entry?.data, tracker.route_id, headsign),
        };
      }),
    [stops, arrivalsMap, tracker.route_id, tracker.headsign]
  );

  const grid = useMemo(
    () => buildMonitorGrid(pages.map((p) => ({ stopId: p.stop.stop_id, headsign: p.headsign, arrivals: p.arrivals }))),
    [pages]
  );

  // Alerts stay attached to the tracker's primary stop.
  const primary = pages[0];
  const alerts = useMemo(
    () =>
      parseAlerts(
        primary?.data,
        {
          routeId: tracker.route_id,
          routeShortName: tracker.route_short_name,
          routeType: tracker.route_type,
          stopId: primary?.stop.stop_id ?? tracker.stop_id,
        },
        now
      ),
    [primary, tracker, now]
  );

  const hasLoaded = pages.some((p) => p.data !== undefined);
  const hasUpcomingDepartures = pages.some((p) => p.arrivals.length > 0);
  useEffect(() => {
    if (!hasLoaded) return;
    onHasDeparturesChange?.(tracker.id, hasUpcomingDepartures);
  }, [tracker.id, hasLoaded, hasUpcomingDepartures, onHasDeparturesChange]);

  // Offline never counts as pending: the page would stay covered for as long
  // as the connection is gone, so offline keeps the frozen, dimmed snapshot.
  const pending = pages.some(
    (p) => !p.isError && (p.data === undefined || isArrivalsStale(p.dataUpdatedAt, now, false))
  ) && !isOffline;
  useEffect(() => {
    onPendingChange?.(tracker.id, pending);
  }, [tracker.id, pending, onPendingChange]);

  const displayName = formatRouteDisplayName(tracker.route_short_name, tracker.route_id);
  const favoriteSuffix = tracker.is_favorite ? ', dashboard favorite' : '';
  // Two directions get room for their names; a third squeezes in narrower.
  const columnWidth = grid.columns.length >= 3 ? '4.5rem' : '5.5rem';
  const columnTemplate = { gridTemplateColumns: `minmax(0,1fr) repeat(${Math.max(1, grid.columns.length)}, ${columnWidth})` };

  const openStop = (index: number) => {
    const stop = stops[index] ?? stops[0];
    onOpen({ ...tracker, stop_id: stop.stop_id, stop_name: stop.stop_name, headsign: stop.headsign || '' });
  };

  return (
    <div className="transit-monitor card-glass-interactive relative overflow-hidden px-3 pt-2.5">
      {/* Dashboard favorites carry a faint star stamped into the panel, like
          the watermark on a cancelled class. */}
      {tracker.is_favorite && showFavoriteMark && (
        <div aria-hidden className="pointer-events-none absolute -bottom-3 -left-3 -rotate-12 select-none text-(--monitor-live) opacity-[0.08] dark:opacity-[0.1]">
          <RiStarFill className="h-16 w-16" />
        </div>
      )}
      {/* The whole panel opens the first stop; rows open their own stop and
          the header icons do their own thing, all layered above this. When
          rows exist, the first row already is this control for keyboard and
          screen-reader users, so the overlay stays a pointer-only target. */}
      <button
        type="button"
        onClick={() => openStop(0)}
        aria-hidden={grid.rows.length > 0 ? true : undefined}
        tabIndex={grid.rows.length > 0 ? -1 : undefined}
        aria-label={`Open live departures for ${displayName} at ${stops[0]?.stop_name ?? tracker.stop_name}${favoriteSuffix}`}
        className="absolute inset-0 cursor-pointer focus:outline-none focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent-primary"
      />
      {/* One header row: the line (with its favorite star and any alert) in
          the stop column, the direction names over their time columns. */}
      <div className="pointer-events-none relative mb-2 grid items-end gap-x-2" style={columnTemplate}>
        <span className="flex min-w-0 items-center gap-1.5 [&>button]:pointer-events-auto">
          <RouteBadge size="sm" name={displayName} color={tracker.route_color} textColor={tracker.route_text_color} />
          {/* Service alerts: a small amber rubber stamp beside the line. It
              opens the stop, where the full alert text lives. */}
          {alerts.length > 0 && (
            <button
              type="button"
              onClick={() => openStop(0)}
              title={alerts[0].header}
              aria-label={`${alerts.length} service alert${alerts.length === 1 ? '' : 's'}: ${alerts[0].header}`}
              className="-rotate-6 shrink-0 cursor-pointer rounded-[5px] border-[1.5px] border-current bg-tint-amber px-1.5 py-0.5 text-[11px] font-extrabold leading-none tracking-wide text-tint-amber transition-transform hover:-rotate-3 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-primary"
            >
              <span className="flex items-center gap-1">
                <RiAlertLine className="h-3 w-3" aria-hidden="true" />
                {alerts.length > 1 ? `${alerts.length} Alerts` : 'Alert'}
              </span>
            </button>
          )}
        </span>
        {grid.columns.map((name) => (
          <span key={name} title={name} className="line-clamp-2 text-right text-xs font-semibold leading-tight text-(--monitor-muted)">
            {name}
          </span>
        ))}
      </div>

      {grid.rows.map((row, index) => {
        const page = pages[index];
        const frozen = isArrivalsStale(page.dataUpdatedAt, now, isOffline);
        const cellNow = arrivalsStaleNow(page.dataUpdatedAt, now, isOffline);
        return (
          <button
            key={row.stopId}
            type="button"
            onClick={() => openStop(index)}
            aria-label={`Open live departures for ${displayName} at ${page.stop.stop_name}${index === 0 ? favoriteSuffix : ''}`}
            className="relative -mx-3 grid w-[calc(100%+1.5rem)] cursor-pointer items-center gap-x-2 border-t border-(--monitor-rule) px-3 py-2.5 text-left transition-colors hover:bg-(--monitor-hover) focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent-primary"
            style={columnTemplate}
          >
            <span className="truncate text-[13.5px] font-semibold text-(--monitor-text)">
              <FormattedRouteTitle title={page.stop.stop_name} />
            </span>
            {page.isError && page.data === undefined ? (
              <span className="text-right text-xs font-semibold text-tint-red" style={{ gridColumn: '2 / -1' }}>
                Live feed unavailable
              </span>
            ) : grid.columns.length === 0 ? (
              <MonitorTime arrival={null} now={cellNow} frozen={false} metro={false} />
            ) : (
              row.cells.map((cell, i) => <MonitorTime key={grid.columns[i]} arrival={cell} now={cellNow} frozen={frozen} metro={tracker.route_type === 'SUBWAY'} />)
            )}
          </button>
        );
      })}

      {grid.extraColumns > 0 && (
        <p className="pointer-events-none relative border-t border-(--monitor-rule) py-2 text-xs font-medium text-(--monitor-muted)">
          {grid.extraColumns} more direction{grid.extraColumns === 1 ? '' : 's'}, open a stop to see them
        </p>
      )}
    </div>
  );
}
