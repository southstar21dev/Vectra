import { RiArrowRightLine } from '@remixicon/react';
import { cn } from '@/lib/utils';
import { formatBudapestHm } from '@/lib/date-utils';
import { RouteBadge } from '../route-badge';
import type { RouteMapStop, RouteMapVehicle } from '../map/types';
import type { RouteProgress } from '../live-position';
import type { LatLon } from '../route-geometry';
import { formatCountdown, formatRouteDisplayName } from '../transit-utils';
import type { Arrival } from '../types';
import { StopsProgressVisualizer } from './stops-progress-visualizer';
import { SleepingIcon } from '@/components/ui/sleeping-icon';

/** One direction of the tracked route — stops, geometry, vehicles and progress.
 *  An all-directions tracker yields two of these; a direction tracker one. */
export interface DirectionView {
  tripId: string | null;
  stops: RouteMapStop[];
  polyline: LatLon[];
  vehicles: RouteMapVehicle[];
  arrival: Arrival | null;
  /** The next departures after `arrival`, same direction. */
  upcoming: Arrival[];
  trackedIndex: number;
  progress: RouteProgress;
  /** Raw timed stopTimes from the trip entry — approximate-position input (Task 9). */
  stopTimes: Array<{ departureTime?: number; shapeDistTraveled?: number }>;
}

/** One compact departure card per direction: route badge, destination, ETA countdown,
 *  scheduled clock time, and the animated visualizer showing how many stops left to the user. */
export function DirectionStrip({
  direction,
  routeName,
  routeId,
  routeColor,
  routeTextColor,
  now,
  accent,
  lead = false,
  frozenStopsLeft,
}: {
  direction: DirectionView & { approxVehicle?: RouteMapVehicle | null };
  routeName: string;
  routeId?: string;
  routeColor: string;
  routeTextColor?: string | null;
  now: number;
  /** Second direction gets violet so the two tracked directions are distinct. */
  accent: 'emerald' | 'violet';
  /** The lead direction stands proud of the others. */
  lead?: boolean;
  /** Frozen stopsLeft for this direction while dialog is open. */
  frozenStopsLeft?: number | null;
}) {
  const { stops, arrival, upcoming, progress, approxVehicle } = direction;
  const displayRouteName = formatRouteDisplayName(routeName, routeId || arrival?.routeId, true);
  const minutes = arrival ? Math.max(0, Math.ceil((arrival.arrivalMs - now) / 60_000)) : null;
  const eta = minutes != null ? formatCountdown(minutes) : null;
  const clockTime = arrival?.arrivalMs ? formatBudapestHm(arrival.arrivalMs) : null;
  const hasVehicle = progress.vehicleIndex >= 0 || approxVehicle != null;
  const stopsLeft = frozenStopsLeft !== undefined ? frozenStopsLeft : progress.stopsLeft;
  const approx = approxVehicle != null;
  const destination = arrival?.headSign || stops[stops.length - 1]?.name || '';

  return (
    <div className={cn('w-full rounded-3xl card-glass p-4', lead && 'card-depth')}>
      {/* Direction & ETA Row */}
      <div className="flex items-start justify-between gap-3 min-w-0">
        <div className="min-w-0 flex-1">
          <p className="text-xs font-semibold text-secondary">Towards</p>
          <p className="mt-1 flex items-center gap-2 min-w-0">
            <RouteBadge size="sm" name={displayRouteName} color={routeColor} textColor={routeTextColor} />
            <RiArrowRightLine className="h-3.5 w-3.5 shrink-0 text-muted" aria-hidden />
            <span className="truncate text-base font-extrabold tracking-tight text-primary" title={destination}>
              {destination}
            </span>
          </p>
        </div>

        {/* ETA & Clock Time */}
        <div className="flex shrink-0 flex-col items-center">
          {eta != null ? (
            <span
              className={cn(
                'font-mono text-2xl font-black leading-none tracking-tight tabular-nums',
                minutes != null && minutes <= 0
                  ? 'text-tint-emerald'
                  : minutes != null && minutes <= 2
                    ? 'text-tint-amber'
                    : 'text-primary'
              )}
            >
              {eta}
            </span>
          ) : (
            <span className="flex h-6 items-center" title="No scheduled departure">
              <SleepingIcon className="h-6 w-6 text-indigo-400 dark:text-indigo-300" aria-label="No scheduled departure" />
            </span>
          )}
          {clockTime && (
            <span className="mt-1.5 text-xs tabular-nums text-secondary">
              {clockTime}
            </span>
          )}
        </div>
      </div>

      {/* Visualized real-time position for stops left to the user */}
      <StopsProgressVisualizer
        stopsLeft={stopsLeft}
        liveProgress={progress}
        hasVehicle={hasVehicle}
        accent={accent}
        approx={approx}
      />

      {/* Following departures in the same direction */}
      {upcoming.length > 0 && (
        <div className="mt-4">
          <div className="flex items-center gap-3" aria-hidden>
            <span className="h-px flex-1 bg-border-subtle" />
            <span className="text-[10px] font-bold uppercase tracking-[0.14em] text-muted">Next</span>
            <span className="h-px flex-1 bg-border-subtle" />
          </div>
          <ul className="mt-3 grid grid-cols-3 gap-2" aria-label="Next departures">
            {upcoming.map((a) => {
              const mins = Math.max(0, Math.ceil((a.arrivalMs - now) / 60_000));
              return (
                <li
                  key={a.tripId || a.arrivalMs}
                  className="flex min-w-0 flex-col items-center gap-1 rounded-2xl border border-subtle bg-surface-overlay px-2 py-2"
                >
                  <span className="font-mono text-sm font-extrabold leading-none tabular-nums text-primary">
                    {formatBudapestHm(a.arrivalMs)}
                  </span>
                  <span className="font-mono text-[11px] font-bold leading-none tabular-nums text-muted">
                    {formatCountdown(mins)}
                  </span>
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </div>
  );
}
