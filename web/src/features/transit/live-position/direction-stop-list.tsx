import { cn } from '@/lib/utils';
import { formatRouteDisplayName } from '../transit-utils';
import type { RouteMapVehicle } from '../map/types';
import type { DirectionView } from './direction-strip';

/** Full ordered stop list for one direction — the "Show all stops" expansion.
 *  The vehicle chip row sits above the vehicle's next stop, the tracked stop
 *  is highlighted and the termini carry start/end tags (the old list UI). */
export function DirectionStopList({
  direction,
  routeEmoji,
  routeName,
  routeId,
  accent,
}: {
  direction: DirectionView & { approxVehicle?: RouteMapVehicle | null };
  routeEmoji: string;
  routeName: string;
  routeId?: string;
  accent: 'emerald' | 'violet';
}) {
  const { stops, progress, arrival, approxVehicle } = direction;
  const displayRouteName = formatRouteDisplayName(routeName, routeId || arrival?.routeId, true);
  const violet = accent === 'violet';
  const approx = approxVehicle != null;
  const rows: React.ReactNode[] = [];
  stops.forEach((s, i) => {
    if (progress.vehicleIndex >= 0 && i === progress.vehicleIndex) {
      rows.push(
        <div
          key={`veh-${i}`}
          className={cn(
            'flex items-center gap-3 px-4 py-2.5',
            approx ? 'bg-pink-500/10' : violet ? 'bg-violet-500/10' : 'bg-emerald-500/10'
          )}
        >
          <span
            className={cn(
              'w-2 h-2 rounded-full shrink-0',
              approx ? 'bg-pink-400' : violet ? 'bg-violet-400' : 'bg-emerald-400'
            )}
          />
          <span
            className={cn(
              'truncate text-xs font-bold',
              approx ? 'text-pink-500' : violet ? 'text-violet-500' : 'text-tint-emerald'
            )}
          >
            {routeEmoji} {displayRouteName} · vehicle here
          </span>
        </div>
      );
    }
    rows.push(
      <div
        key={`st-${i}`}
        className={cn(
          'flex items-center gap-3 px-4 py-2.5 text-sm transition-colors',
          s.isSelected
            ? 'bg-accent-primary/10 font-bold text-primary'
            : s.isTracked
              ? 'font-semibold text-primary'
              : 'text-secondary'
        )}
      >
        <span
          className={cn(
            'w-2 h-2 rounded-full shrink-0',
            s.isSelected
              ? 'bg-accent-primary ring-2 ring-accent-primary/50'
              : s.isTracked
                ? 'bg-blue-400 ring-1 ring-blue-400/40'
                : 'bg-zinc-500'
          )}
        />
        <span className="min-w-0 break-words">{s.name}</span>
        {s.isSelected ? (
          <span className="ml-auto shrink-0 text-[10px] font-bold text-tint-blue">This stop</span>
        ) : s.isTracked ? (
          <span className="ml-auto shrink-0 text-[10px] font-semibold text-tint-blue/80">Tracked</span>
        ) : i === 0 ? (
          <span className="ml-auto shrink-0 text-[10px] text-muted">Start</span>
        ) : i === stops.length - 1 ? (
          <span className="ml-auto shrink-0 text-[10px] text-muted">End</span>
        ) : null}
      </div>
    );
  });
  return <div className="flex flex-col divide-y divide-subtle/60">{rows}</div>;
}
