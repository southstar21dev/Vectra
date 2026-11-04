import { RiArrowRightSLine, RiRouteLine } from '@remixicon/react';
import { RouteBadge } from '@/features/transit/route-badge';
import { cn } from '@/lib/utils';
import type { JourneyOption } from './journey-options';

interface JourneyOptionListProps {
  options: JourneyOption[] | null;
  onChoose: (option: JourneyOption) => void;
  onPickLine: () => void;
}

function describe(option: JourneyOption): string {
  if (option.legs.length === 1) {
    const headsign = option.legs[0].routes[0]?.headsign;
    return headsign ? `Direct · towards ${headsign}` : 'Direct';
  }
  const changes = option.legs.slice(0, -1).map((leg) => leg.to.name);
  return `Change at ${changes.join(', then ')}`;
}

/**
 * The planner's answer to "how do I get there?": complete journeys ranked by
 * estimated time, transfers included. Choosing one fills in every leg; the
 * manual line picker stays one tap away for when none of them fit.
 */
export function JourneyOptionList({ options, onChoose, onPickLine }: JourneyOptionListProps) {
  return (
    <section aria-labelledby="journey-options-title" className="space-y-3">
      <h3 id="journey-options-title" className="text-base font-extrabold tracking-tight text-primary">
        Ways to get there
      </h3>

      {options === null ? (
        <div role="status" aria-label="Finding journeys" className="space-y-2">
          {[0, 1, 2].map((i) => (
            <div key={i} className="flex h-[72px] items-center gap-4 rounded-2xl card-glass px-4">
              <div className="h-7 w-10 rounded-lg bg-surface-overlay motion-safe:animate-pulse" />
              <div className="flex-1 space-y-2">
                <div className="h-5 w-28 rounded-md bg-surface-overlay motion-safe:animate-pulse" />
                <div className="h-3 w-40 rounded bg-surface-overlay motion-safe:animate-pulse" />
              </div>
            </div>
          ))}
        </div>
      ) : options.length === 0 ? (
        <div className="rounded-2xl panel-glass px-4 py-5">
          <p className="text-sm font-semibold text-primary">No connection found between these stops</p>
          <p className="mt-1 text-xs leading-relaxed text-secondary">
            Pick the line you take and where you get off. You can chain as many rides as you need.
          </p>
        </div>
      ) : (
        <ul className="space-y-2">
          {options.map((option, i) => (
            <li key={option.key}>
              <button
                type="button"
                onClick={() => onChoose(option)}
                className="group flex w-full items-center gap-4 rounded-2xl card-glass-interactive px-4 py-3.5 text-left cursor-pointer active:scale-[0.99] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-primary"
              >
                <span className="w-12 shrink-0 text-center">
                  <span className={cn('block font-mono text-xl font-black leading-none tabular-nums', i === 0 ? 'text-accent-primary' : 'text-primary')}>
                    {option.minutes}
                  </span>
                  <span className="mt-1 block text-xs font-semibold text-muted">min</span>
                </span>
                <span className="min-w-0 flex-1">
                  <span className="flex flex-wrap items-center gap-1">
                    {option.legs.map((leg, legIndex) => (
                      <span key={legIndex} className="flex items-center gap-1">
                        {/* Long either/or groups collapse to "59 +2" so a chain reads on one line. */}
                        {(leg.routes.length > 2 ? leg.routes.slice(0, 1) : leg.routes).map((route, routeIndex) => (
                          <span key={route.routeId} className="flex items-center gap-1">
                            {routeIndex > 0 && <span className="text-xs font-bold text-muted">/</span>}
                            <RouteBadge name={route.shortName} color={route.color} textColor={route.textColor} size="sm" />
                          </span>
                        ))}
                        {leg.routes.length > 2 && (
                          <span
                            className="text-xs font-bold text-muted"
                            aria-label={`or ${leg.routes.slice(1).map((r) => r.shortName).join(', ')}`}
                          >
                            +{leg.routes.length - 1}
                          </span>
                        )}
                        {legIndex < option.legs.length - 1 && <RiArrowRightSLine className="h-3.5 w-3.5 text-muted" aria-label="then" />}
                      </span>
                    ))}
                  </span>
                  <span className="mt-1.5 block text-xs leading-snug text-secondary line-clamp-2">{describe(option)}</span>
                  {i === 0 && options.length > 1 && (
                    <span className="mt-2 inline-flex rounded-full bg-accent-primary/15 px-2 py-0.5 text-xs font-bold text-accent-primary">Fastest</span>
                  )}
                </span>
                <RiArrowRightSLine className="h-4 w-4 shrink-0 text-muted transition-transform group-hover:translate-x-0.5 group-hover:text-primary" />
              </button>
            </li>
          ))}
        </ul>
      )}

      {options !== null && (
        <button
          type="button"
          onClick={onPickLine}
          className="flex w-full items-center gap-3 rounded-2xl px-4 py-3 text-left text-sm font-semibold text-secondary transition-colors hover:bg-surface-overlay hover:text-primary cursor-pointer focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary"
        >
          <RiRouteLine className="h-4 w-4 shrink-0" />
          <span className="flex-1">{options.length === 0 ? 'Pick a line' : 'None of these? Pick a line yourself'}</span>
          <RiArrowRightSLine className="h-4 w-4 shrink-0 text-muted" />
        </button>
      )}
    </section>
  );
}
