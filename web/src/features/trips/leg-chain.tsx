import { RiArrowRightSLine, RiTimeLine, RiFootprintLine } from '@remixicon/react';
import { motion, useReducedMotion } from 'framer-motion';
import { cn } from '@/lib/utils';
import { STATUS_TONE_CLASS } from '@/components/ui/status-label';
import { RouteBadge } from '@/features/transit/route-badge';
import { LiveSignalIcon } from '@/features/transit/transit-card';
import { formatTripClock } from './trip-timeline';
import { normalizeStopName } from './route-variants';
import type { TripLeg } from './types';

interface LegChainProps {
  legs: TripLeg[];
  departureMap?: Map<number, { countdown: string; isLive: boolean } | null>;
  variant?: 'compact' | 'full';
}

/** Halo keyframes for the chosen "first to depart" badge. The rest state is an
 *  explicit transparent ring — passing `animate={undefined}` to stop the pulse
 *  freezes it at its last painted frame instead of animating the halo away. */
const BADGE_HALO_PULSE = {
  boxShadow: ['0 0 0 0 rgba(16, 185, 129, 0.5)', '0 0 0 6px rgba(16, 185, 129, 0)'],
};
const BADGE_HALO_REST = { boxShadow: '0 0 0 0px rgba(16, 185, 129, 0)' };
const BADGE_HALO_PULSE_TRANSITION = { duration: 1.8, repeat: Infinity, ease: 'easeOut' } as const;
const BADGE_HALO_REST_TRANSITION = { duration: 0.2, ease: 'easeOut' } as const;

function RouteBadges({ leg, size = 'xs' }: { leg: TripLeg; size?: 'xs' | '2xs' }) {
  // prefers-reduced-motion: the resolved badge keeps its static ring instead
  // of the infinite halo pulse.
  const reduceMotion = useReducedMotion();
  if (leg.routes.length === 0) return null;
  if (leg.routes.length === 1) {
    const r = leg.routes[0];
    return <RouteBadge name={r.routeShortName} color={r.routeColor} textColor={r.routeTextColor} size={size} />;
  }
  return (
    <div className="flex items-center gap-0.5 flex-wrap">
      {leg.routes.map((r, j) => {
        const highlighted = leg.resolvedRouteId === r.routeId;
        const pulse = highlighted && !reduceMotion;
        return (
          <span key={j} className="flex items-center gap-0.5">
            {/* The chosen "first to depart" alternative blooms with an animated
                halo instead of a static ring. */}
            <motion.span
              className={cn(
                'inline-flex rounded-md',
                highlighted && 'border border-tint-emerald/40'
              )}
              animate={pulse ? BADGE_HALO_PULSE : BADGE_HALO_REST}
              transition={pulse ? BADGE_HALO_PULSE_TRANSITION : BADGE_HALO_REST_TRANSITION}
            >
              <RouteBadge name={r.routeShortName} color={r.routeColor} textColor={r.routeTextColor} size="2xs" />
            </motion.span>
            {j < leg.routes.length - 1 && <span className="text-[8px] text-muted-foreground font-bold">/</span>}
          </span>
        );
      })}
    </div>
  );
}

export function LegChain({ legs, departureMap, variant = 'compact' }: LegChainProps) {
  if (variant === 'compact') {
    return (
      <div className="flex items-center gap-1.5 flex-wrap">
        {legs.map((leg, i) => (
          <div key={i} className="flex items-center gap-1.5">
            <RouteBadges leg={leg} />
            {i < legs.length - 1 && (
              <RiArrowRightSLine className="h-3 w-3 text-muted-foreground shrink-0" />
            )}
          </div>
        ))}
      </div>
    );
  }

  return <JourneySteps legs={legs} departureMap={departureMap} />;
}

/** One time on the right edge of a step, tabular so a column of them lines up. */
function StepTime({ ms }: { ms?: number }) {
  if (ms == null) return null;
  return <span className="shrink-0 font-mono text-xs font-bold tabular-nums text-secondary">{formatTripClock(ms)}</span>;
}

/**
 * The journey as one continuous line, the way you'll live it: walk, board,
 * ride, change, alight. Rides draw their rail in the line's own colour between
 * the boarding and alighting stops; walks are dotted; a change shows the wait.
 * The live countdown sits on the boarding row it belongs to.
 */
function JourneySteps({ legs, departureMap }: Pick<LegChainProps, 'legs' | 'departureMap'>) {
  return (
    <ol className="rounded-3xl card-glass px-4 py-4 sm:px-5">
      {legs.map((leg, i) => {
        const isWalk = leg.mode === 'WALK';
        const departure = departureMap?.get(i);
        const next = legs[i + 1];
        const showChange = !isWalk && !!next && next.mode !== 'WALK';
        const isLastStep = i === legs.length - 1;
        // Changing at the very same stop: the next ride's boarding row names it,
        // so this ride's alighting row would only repeat it.
        const changeInPlace =
          showChange && normalizeStopName(next.departureStopName) === normalizeStopName(leg.destinationStopName);

        if (isWalk) {
          return (
            <li key={i} className="relative flex gap-3.5 pb-4 last:pb-0">
              {!isLastStep && (
                <span aria-hidden className="absolute bottom-0 left-[11px] top-7 border-l-2 border-dotted border-text-muted/60" />
              )}
              <span className="relative z-10 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-surface-overlay text-secondary">
                <RiFootprintLine className="h-3.5 w-3.5" aria-hidden />
              </span>
              <div className="flex min-w-0 flex-1 items-start justify-between gap-3 pt-0.5">
                <div className="min-w-0">
                  <p className="text-sm font-semibold text-primary">
                    Walk{leg.durationMinutes != null ? ` ${leg.durationMinutes} min` : ''}
                  </p>
                  <p className="mt-0.5 text-xs leading-snug text-muted">
                    {leg.distanceMeters != null ? `${Math.round(leg.distanceMeters)} m · ` : ''}to {leg.destinationStopName || '—'}
                  </p>
                </div>
                <StepTime ms={leg.departureMs} />
              </div>
            </li>
          );
        }

        const color = `#${leg.routes[0]?.routeColor ?? '64748b'}`;
        const multi = leg.routes.length > 1;
        const uniqueRoutes = leg.routes.filter((r, j, all) => all.findIndex((x) => x.routeShortName === r.routeShortName) === j);
        return (
          <li key={i} className="relative pb-4 last:pb-0">
            {/* A walk follows: a dotted connector down to its node. */}
            {!isLastStep && !showChange && (
              <span aria-hidden className="absolute bottom-0 left-[11px] h-4 border-l-2 border-dotted border-text-muted/60" />
            )}
            <div className="relative">
            {/* The ride's rail, in the line's colour, from boarding to alighting. */}
            <span
              aria-hidden
              className={cn('absolute left-[9px] top-2.5 w-1.5 rounded-full', changeInPlace ? 'bottom-1.5' : 'bottom-2.5')}
              style={{ backgroundColor: color }}
            />
            {/* Changing in place: the alight row is merged into the next boarding row, so cap the rail instead. */}
            {changeInPlace && (
              <span aria-hidden className="absolute bottom-0 left-[6px] h-3 w-3 rounded-full" style={{ backgroundColor: color }} />
            )}

            {/* Board */}
            <div className="relative flex gap-3.5">
              <span className="relative z-10 mt-0.5 flex w-6 shrink-0 justify-center">
                <span className="h-4 w-4 rounded-full border-[3px] bg-surface-secondary" style={{ borderColor: color }} />
              </span>
              <div className="flex min-w-0 flex-1 items-start justify-between gap-3">
                <p className="min-w-0 text-sm font-bold leading-snug text-primary">{leg.departureStopName || '—'}</p>
                <StepTime ms={leg.departureMs} />
              </div>
            </div>

            {/* Ride */}
            <div className="flex gap-3.5 py-3">
              <span aria-hidden className="w-6 shrink-0" />
              <div className="min-w-0 flex-1 space-y-1.5">
                <div className="flex items-center justify-between gap-3">
                  <span className="flex flex-wrap items-center gap-1">
                    {uniqueRoutes.map((r) => {
                      const dimmed = multi && !!leg.resolvedRouteId && leg.resolvedRouteId !== r.routeId;
                      return (
                        <span key={r.routeId} className={cn('transition-opacity', dimmed && 'opacity-45')}>
                          <RouteBadge name={r.routeShortName} color={r.routeColor} textColor={r.routeTextColor} size="sm" />
                        </span>
                      );
                    })}
                  </span>
                  {departure && (
                    <span className="inline-flex shrink-0 items-center gap-1.5 rounded-full bg-surface-overlay px-2.5 py-1 font-mono text-xs font-bold tabular-nums text-secondary">
                      {departure.isLive ? (
                        <LiveSignalIcon className={cn('h-3 w-3', STATUS_TONE_CLASS.emerald)} />
                      ) : (
                        <RiTimeLine className="h-3 w-3" />
                      )}
                      {departure.countdown}
                    </span>
                  )}
                </div>
                {(leg.resolvedHeadsign || leg.headsign) && (
                  <p className="text-xs leading-snug text-secondary">towards {leg.resolvedHeadsign || leg.headsign}</p>
                )}
                {multi && leg.resolvedRouteShortName && (
                  <p className="text-xs text-tint-emerald">{leg.resolvedRouteShortName} leaves first</p>
                )}
              </div>
            </div>

            {/* Alight */}
            {!changeInPlace && (
            <div className="relative flex gap-3.5">
              <span className="relative z-10 mt-0.5 flex w-6 shrink-0 justify-center">
                <span className="h-4 w-4 rounded-full border-[3px] bg-surface-secondary" style={{ borderColor: color }} />
              </span>
              <div className="flex min-w-0 flex-1 items-start justify-between gap-3">
                <p className="min-w-0 text-sm font-semibold leading-snug text-primary">{leg.destinationStopName || '—'}</p>
                <StepTime ms={leg.arrivalMs} />
              </div>
            </div>
            )}
            </div>

            {showChange && (
              <div className="relative flex gap-3.5 pt-3">
                {/* Dotted connector through the change, from this ride's end to the next ride's boarding. */}
                <span aria-hidden className="absolute left-[11px] top-0 h-3 border-l-2 border-dotted border-text-muted/60" />
                <span aria-hidden className="absolute -bottom-4 left-[11px] top-7 border-l-2 border-dotted border-text-muted/60" />
                <span className="flex w-6 shrink-0 justify-center">
                  <RiTimeLine className="h-3.5 w-3.5 text-tint-amber" aria-hidden />
                </span>
                <p className="flex min-w-0 flex-1 items-start justify-between gap-3 text-xs font-semibold text-tint-amber">
                  <span>
                    {changeInPlace ? 'Get off and change' : 'Change'}
                    {leg.waitMinutes > 0 ? ` · ${leg.waitMinutes} min` : ''}
                  </span>
                  {changeInPlace && <StepTime ms={leg.arrivalMs} />}
                </p>
              </div>
            )}
          </li>
        );
      })}
    </ol>
  );
}
