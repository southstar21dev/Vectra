import { Fragment, useMemo } from 'react';
import { RiArrowRightSLine, RiFootprintLine, RiPencilLine, RiAddLine, RiRepeatLine } from '@remixicon/react';
import { RouteBadge } from '@/features/transit/route-badge';
import { formatDistanceMeters } from '@/features/transit/nearby-stops';
import { cn } from '@/lib/utils';
import { suggestRoutes, type RouteSuggestion } from '../route-suggest';
import { normalizeStopName } from '../route-variants';
import type { TripLeg } from '../types';
import { endpointName, type Endpoint } from './constants';
import type { PlannerCatalog } from './use-planner-catalog';

interface JourneyReviewProps {
  catalog: PlannerCatalog;
  from: Endpoint;
  to: Endpoint;
  legs: TripLeg[];
  /** Re-run a leg with this set of either/or lines. */
  onSetLegRoutes: (index: number, routes: RouteSuggestion[]) => void;
  onEditLeg: (index: number) => void;
  /** The journey stops short of the destination: choose the next ride. */
  onContinue: () => void;
  onOtherJourneys: () => void;
}

function Node({ kind }: { kind: 'start' | 'end' | 'change' }) {
  return (
    <span aria-hidden className="relative z-10 flex w-3.5 shrink-0 justify-center pt-1">
    <span
      className={cn(
        'block rounded-full',
        kind === 'start' && 'h-3 w-3 bg-text-primary',
        kind === 'end' && 'h-3.5 w-3.5 border-[3px] border-accent-primary bg-surface-secondary',
        kind === 'change' && 'h-2.5 w-2.5 border-2 border-text-muted bg-surface-secondary'
      )}
    />
    </span>
  );
}

/**
 * The chosen journey as one vertical line, start to destination. Each ride
 * shows its line(s) and, inline, the other lines that run the same stretch —
 * one tap adds or drops an either/or alternative. Tapping a ride opens the
 * full line editor.
 */
export function JourneyReview({ catalog, from, to, legs, onSetLegRoutes, onEditLeg, onContinue, onOtherJourneys }: JourneyReviewProps) {
  const alternatives = useMemo(
    () => legs.map((leg) => suggestRoutes(catalog.routes, catalog.index, leg.departureStopName, leg.destinationStopName)),
    [catalog, legs]
  );

  const last = legs[legs.length - 1];
  const complete = !!last && normalizeStopName(last.destinationStopName) === normalizeStopName(to.stop.name);

  return (
    <section aria-labelledby="journey-title" className="space-y-3">
      <div className="flex items-center justify-between gap-3">
        <h3 id="journey-title" className="text-base font-extrabold tracking-tight text-primary">
          Your journey
        </h3>
        <button
          type="button"
          onClick={onOtherJourneys}
          className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1.5 text-xs font-semibold text-secondary transition-colors hover:bg-surface-overlay hover:text-primary cursor-pointer"
        >
          <RiRepeatLine className="h-3.5 w-3.5" /> Other ways
        </button>
      </div>

      <ol className="relative rounded-3xl card-glass px-4 py-4 sm:px-5">
        {/* The spine: one continuous line behind every node. */}
        <span aria-hidden className="absolute bottom-7 left-[22px] top-7 w-0.5 rounded-full bg-border-primary sm:left-[26px]" />

        <li className="flex gap-3.5">
          <Node kind="start" />
          <div className="min-w-0 flex-1 pb-4">
            <p className="truncate text-sm font-bold text-primary">{endpointName(from)}</p>
            {from.address ? (
              <p className="mt-0.5 flex items-center gap-1.5 text-xs text-muted">
                <RiFootprintLine className="h-3.5 w-3.5 shrink-0" />
                <span className="truncate">
                  {formatDistanceMeters(from.address.distanceMeters)} walk to {from.stop.name}
                </span>
              </p>
            ) : (
              <p className="mt-0.5 text-xs text-muted">BKK stop</p>
            )}
          </div>
        </li>

        {legs.map((leg, i) => {
          const options = alternatives[i] ?? [];
          const legIds = new Set(leg.routes.map((r) => r.routeId));
          const chosen = options.filter((s) => s.routeIds.some((id) => legIds.has(id)));
          // Quick either/or picks skip night lines (N-prefixed): they aren't a
          // daytime alternative. The full editor still lists them.
          const others = options
            .filter((s) => !s.routeIds.some((id) => legIds.has(id)) && !/^N\d/i.test(s.shortName))
            .slice(0, 4);
          // Lines on the leg that the suggestions don't cover still count as chosen.
          const uncovered = leg.routes.length > 0 && chosen.flatMap((s) => s.routeIds).filter((id) => legIds.has(id)).length < legIds.size;
          return (
            <Fragment key={`${leg.departureStopId}-${i}`}>
            <li className="flex gap-3.5">
              <span aria-hidden className="w-3.5 shrink-0" />
              <div className="min-w-0 flex-1 space-y-2.5 pb-4">
                <button
                  type="button"
                  onClick={() => onEditLeg(i)}
                  aria-label={`Edit ride ${i + 1}: ${leg.routes.map((r) => r.routeShortName).join(' or ')} from ${leg.departureStopName} to ${leg.destinationStopName}`}
                  className="group -mx-2 flex w-[calc(100%+1rem)] items-center gap-3 rounded-2xl px-2 py-2 text-left transition-colors hover:bg-surface-overlay cursor-pointer active:scale-[0.99] focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary"
                >
                  <span className="flex shrink-0 flex-wrap items-center gap-1">
                    {leg.routes
                      .filter((r, j, all) => all.findIndex((x) => x.routeShortName === r.routeShortName) === j)
                      .map((r) => (
                        <RouteBadge key={r.routeId} name={r.routeShortName} color={r.routeColor} textColor={r.routeTextColor} size="sm" />
                      ))}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block text-xs leading-snug text-secondary line-clamp-2">
                      {leg.headsign ? `towards ${leg.headsign}` : leg.routeLongName}
                    </span>
                  </span>
                  <RiPencilLine className="h-3.5 w-3.5 shrink-0 text-muted opacity-70 group-hover:opacity-100" />
                </button>

                {(others.length > 0 || chosen.length > 1) && (
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="mr-0.5 text-xs font-semibold text-muted">Or ride</span>
                    {others.map((s) => (
                      <button
                        key={s.routeId}
                        type="button"
                        onClick={() => onSetLegRoutes(i, [...chosen, s])}
                        aria-label={`Also ride ${s.shortName} on this stretch`}
                        className="inline-flex h-8 items-center gap-1 rounded-full bg-surface-overlay pl-1 pr-2.5 transition-colors hover:bg-surface-tertiary cursor-pointer active:scale-[0.97]"
                      >
                        <RouteBadge name={s.shortName} color={s.color} textColor={s.textColor} size="xs" />
                        <RiAddLine className="h-3 w-3 text-muted" />
                      </button>
                    ))}
                    {chosen.length > 1 && !uncovered && (
                      <span className="text-xs text-muted">· first to leave wins</span>
                    )}
                  </div>
                )}
              </div>
            </li>
            {i < legs.length - 1 && (
              <li className="flex gap-3.5">
                <Node kind="change" />
                <p className="min-w-0 flex-1 pb-4 text-xs text-secondary">
                  Change at <span className="font-semibold text-primary">{leg.destinationStopName}</span>
                </p>
              </li>
            )}
            </Fragment>
          );
        })}

        {!complete && (
          <li className="flex gap-3.5">
            <span aria-hidden className="w-3.5 shrink-0" />
            <div className="min-w-0 flex-1 pb-4">
              <button
                type="button"
                onClick={onContinue}
                className="flex w-full items-center gap-3 rounded-2xl border border-dashed border-accent-primary/45 bg-accent-primary/5 px-3 py-3 text-left transition-colors hover:bg-accent-primary/10 cursor-pointer active:scale-[0.99]"
              >
                <RiAddLine className="h-4 w-4 shrink-0 text-accent-primary" />
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-semibold text-primary">Next ride</span>
                  <span className="block truncate text-xs text-secondary">
                    From {last ? last.destinationStopName : from.stop.name} towards {to.stop.name}
                  </span>
                </span>
                <RiArrowRightSLine className="h-4 w-4 shrink-0 text-muted" />
              </button>
            </div>
          </li>
        )}

        <li className="flex gap-3.5">
          <Node kind="end" />
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-bold text-primary">{endpointName(to)}</p>
            {to.address ? (
              <p className="mt-0.5 flex items-center gap-1.5 text-xs text-muted">
                <RiFootprintLine className="h-3.5 w-3.5 shrink-0" />
                <span className="truncate">
                  {formatDistanceMeters(to.address.distanceMeters)} walk from {to.stop.name}
                </span>
              </p>
            ) : (
              <p className="mt-0.5 text-xs text-muted">BKK stop</p>
            )}
          </div>
        </li>
      </ol>
    </section>
  );
}
