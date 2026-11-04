import { RiTimeLine, RiTimerLine } from '@remixicon/react';
import { cn } from '@/lib/utils';
import { LiveSignalIcon } from '@/features/transit/transit-card';
import { formatTripClock } from './trip-timeline';
import type { TripDepartureOption } from './trip-timeline';

interface DepartureOptionsProps {
  options: TripDepartureOption[];
  now: number;
  /** Highlight this option (modal selection). Omit for informational cards. */
  selectedIndex?: number;
  onSelect?: (index: number) => void;
}

/**
 * Upcoming departures as a horizontal strip of cards, the way a transit app
 * lists the next few trains: departure time large, the countdown under it,
 * the arrival on the bottom line. The selected card is accent-filled; a
 * departure too tight to catch on foot is amber and says so.
 */
export function DepartureOptions({ options, now, selectedIndex, onSelect }: DepartureOptionsProps) {
  if (options.length === 0) return null;

  return (
    <div
      role={onSelect ? 'radiogroup' : undefined}
      aria-label="Departures"
      className={cn(
        '-mx-5 -my-5 flex snap-x snap-mandatory gap-2 overflow-x-auto px-5 py-5 scroll-pl-5 no-scrollbar sm:-mx-6 sm:px-6 sm:scroll-pl-6',
        // Cards scrolled past the gutter dissolve into it instead of being cut
        // off in a hard line (the glow on the selected card made that clip obvious).
        '[mask-image:linear-gradient(to_right,transparent,black_1.25rem,black_calc(100%-1.25rem),transparent)]',
        'sm:[mask-image:linear-gradient(to_right,transparent,black_1.5rem,black_calc(100%-1.5rem),transparent)]'
      )}
    >
      {options.map((opt, index) => {
        const countdown = Math.max(0, Math.round((opt.departureMs - now) / 60_000));
        const depart = formatTripClock(opt.departureMs);
        const arrive = opt.timeline.arriveAt != null ? formatTripClock(opt.timeline.arriveAt) : null;
        const selected = selectedIndex != null && index === selectedIndex;
        const tooTight = !!opt.tooTight;
        const caption = countdown === 0 ? 'now' : `in ${countdown} min`;
        return (
          <button
            key={opt.departureMs}
            type="button"
            role={onSelect ? 'radio' : undefined}
            aria-checked={onSelect ? selected : undefined}
            disabled={!onSelect}
            onClick={() => onSelect?.(index)}
            aria-label={`Leave ${depart}, ${caption}${arrive ? `, arrive ${arrive}` : ''}${tooTight ? ', too tight to catch on foot' : ''}`}
            className={cn(
              'flex w-[7.25rem] shrink-0 snap-start flex-col items-start rounded-2xl border px-3.5 py-3 text-left transition-[background-color,border-color,box-shadow,transform] duration-150',
              onSelect && 'cursor-pointer active:scale-[0.97]',
              'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-primary',
              selected
                ? 'border-accent-primary/60 bg-accent-primary/15 shadow-[0_0_18px_color-mix(in_srgb,var(--accent-primary)_22%,transparent)]'
                : tooTight
                  ? 'border-tint-amber/30 bg-tint-amber/10'
                  : 'card-glass'
            )}
          >
            <span
              className={cn(
                'font-mono text-xl font-black leading-none tabular-nums',
                selected ? 'text-primary' : tooTight ? 'text-tint-amber' : 'text-primary'
              )}
            >
              {depart}
            </span>
            <span
              className={cn(
                'mt-1.5 flex items-center gap-1 text-xs font-bold',
                tooTight ? 'text-tint-amber' : opt.isLive ? 'text-tint-emerald' : 'text-secondary'
              )}
            >
              {tooTight ? (
                <RiTimerLine className="h-3 w-3 shrink-0" aria-hidden />
              ) : opt.isLive ? (
                <LiveSignalIcon className="h-3 w-3 shrink-0" />
              ) : (
                <RiTimeLine className="h-3 w-3 shrink-0" aria-hidden />
              )}
              {tooTight ? 'Too tight' : caption}
            </span>
            {arrive && (
              <span className="mt-2 text-xs text-muted">
                Arrive <span className="font-mono font-bold tabular-nums text-secondary">{arrive}</span>
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
