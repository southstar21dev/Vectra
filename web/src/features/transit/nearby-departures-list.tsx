import { useMemo } from 'react';
import { RiTimeLine, RiWalkLine } from '@remixicon/react';
import { SleepingIcon } from '@/components/ui/sleeping-icon';
import { cn } from '@/lib/utils';
import { groupByLine } from './nearby-lines';
import type { RadarDepartureItem } from './nearby-stops';
import { RouteBadge } from './route-badge';
import { LiveSignalIcon } from './transit-card';
import { formatPrimeCountdown } from './transit-utils';
import type { NearbyLocationStatus } from './use-nearby-data';

/**
 * The lines at the stop nearest you, one row per line with both directions:
 * a heading with the stop, its distance and the walk, then hairline rows.
 */
export function NearbyDeparturesList({
  items,
  status,
  maxLines = 3,
}: {
  items: RadarDepartureItem[];
  status: NearbyLocationStatus;
  maxLines?: number;
}) {
  const lead = items[0] ?? null;
  const groups = useMemo(() => (lead ? groupByLine(items, lead.stopId, maxLines) : []), [items, lead, maxLines]);

  if (!lead || groups.length === 0) {
    return (
      <p className="mt-3 flex items-center gap-2 px-0.5 text-sm font-semibold text-secondary">
        <SleepingIcon className="h-4 w-4 shrink-0 text-indigo-400 dark:text-indigo-300" aria-hidden />
        {status === 'tracking'
          ? 'No departures near you right now'
          : status === 'failed'
            ? 'Allow location to see departures'
            : 'Departures appear once you are located'}
      </p>
    );
  }

  return (
    <>
      <p className="flex items-center justify-between gap-3 px-0.5 pt-3 pb-1 text-[11px] font-bold uppercase tracking-wider text-muted">
        <span className="min-w-0 truncate">
          {lead.stopName} · {Math.round(lead.distanceMeters)} m
        </span>
        <span className="inline-flex shrink-0 items-center gap-1">
          <RiWalkLine className="h-3.5 w-3.5" aria-hidden />
          {lead.walkMinutes} min
        </span>
      </p>
      <ul>
        {groups.map((group) => (
          <li key={group.routeId} className="flex items-center gap-3 py-2.5 [&+&]:border-t [&+&]:border-subtle">
            <RouteBadge size="md" name={group.shortName} color={group.color} textColor={group.textColor} />
            <div className="min-w-0 flex-1 space-y-1">
              {group.directions.map((departure) => {
                const tone =
                  departure.countdownMinutes <= 0
                    ? 'text-tint-emerald'
                    : departure.feasibility === 'hurry'
                      ? 'text-tint-amber'
                      : 'text-primary';
                return (
                  <p key={departure.id} className="flex items-center gap-2">
                    <span className="min-w-0 flex-1 truncate text-xs text-secondary">→ {departure.headsign}</span>
                    <span className="flex shrink-0 items-center gap-1.5">
                      {departure.isPredicted ? (
                        <LiveSignalIcon className="h-4 w-4 shrink-0 text-tint-emerald" />
                      ) : (
                        <RiTimeLine className="h-4 w-4 shrink-0 text-muted" aria-hidden />
                      )}
                      <span className={cn('font-mono text-lg font-extrabold leading-none tabular-nums', tone)}>
                        {formatPrimeCountdown(departure.countdownMinutes)}
                      </span>
                    </span>
                  </p>
                );
              })}
            </div>
          </li>
        ))}
      </ul>
    </>
  );
}
