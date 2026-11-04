import { RiArrowRightLine, RiArrowRightSLine, RiTimeLine, RiLoader4Line, RiNavigationLine } from '@remixicon/react';
import { RouteBadge } from '@/features/transit/route-badge';
import { formatPrimeCountdown } from '@/features/transit/transit-utils';
import { LiveSignalIcon } from '@/features/transit/transit-card';
import { cn } from '@/lib/utils';
import type { CommuteKind } from './commute';
import type { TripPlan } from './types';

interface CommuteCardProps {
  trip: TripPlan;
  kind: CommuteKind;
  departure: { minutes: number; isLive: boolean } | null;
  onTap: () => void;
}

/** The designated commute as a tappable card — shared by the transit hero and
 *  the dashboard so both render the same thing. */
export function CommuteCard({ trip, kind, departure, onTap }: CommuteCardProps) {
  const firstRoute = trip.legs[0]?.routes[0];
  const destination = trip.legs[trip.legs.length - 1]?.destinationStopName ?? '';
  const kindLabel = kind === 'to_uni' ? 'To uni' : 'Home';
  // A trip simply named after its kind reads once, not "To uni · To uni".
  const showKind = trip.name.trim().toLowerCase() !== kindLabel.toLowerCase();
  return (
    <button
      type="button"
      onClick={onTap}
      className="card-glass rounded-3xl p-4 w-full text-left cursor-pointer transition-transform active:scale-[0.99] focus:outline-none focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-primary"
    >
      <div className="flex items-center justify-between gap-3">
        <p className="flex min-w-0 items-center gap-1.5 text-xs font-semibold text-secondary">
          <RiNavigationLine className="h-3.5 w-3.5 shrink-0 text-tint-blue" aria-hidden />
          <span className="truncate">{trip.name}</span>
          {showKind && (
            <span className="ml-1 shrink-0 rounded-full bg-surface-overlay px-2 py-0.5 text-[10px] font-bold text-secondary">{kindLabel}</span>
          )}
        </p>
        <span className="inline-flex shrink-0 items-center gap-1 text-xs font-bold text-accent-primary">
          Open trip
          <RiArrowRightSLine className="h-3.5 w-3.5" aria-hidden />
        </span>
      </div>

      <div className="mt-3 flex items-center gap-3">
        {firstRoute && (
          <RouteBadge name={firstRoute.routeShortName} color={firstRoute.routeColor} textColor={firstRoute.routeTextColor} size="sm" />
        )}
        {destination && (
          <>
            <RiArrowRightLine className="h-3.5 w-3.5 shrink-0 text-muted" aria-hidden />
            <span className="min-w-0 flex-1 truncate text-sm font-bold text-primary">{destination}</span>
          </>
        )}
        {departure ? (
          <span
            className={cn(
              'ml-auto flex shrink-0 items-center gap-1.5 font-mono text-sm font-bold tabular-nums',
              departure.minutes <= 0 ? 'text-tint-emerald' : 'text-primary'
            )}
          >
            {departure.isLive ? (
              <LiveSignalIcon className="h-3.5 w-3.5 text-tint-emerald" />
            ) : (
              <RiTimeLine className="h-3.5 w-3.5 text-muted" aria-hidden />
            )}
            {formatPrimeCountdown(departure.minutes)}
          </span>
        ) : (
          <RiLoader4Line className="ml-auto h-4 w-4 shrink-0 animate-spin text-tint-blue" aria-label="Checking departures" />
        )}
      </div>
    </button>
  );
}
