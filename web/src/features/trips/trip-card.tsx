import { useMemo } from 'react';
import { RiTimeLine, RiTimerLine } from '@remixicon/react';
import { LiveSignalIcon } from '@/features/transit/transit-card';
import { cn } from '@/lib/utils';
import { useNow } from '@/hooks/use-now';
import { useTripTimeline, useFirstLegDeparture } from './use-trip-timeline';
import { formatPrimeCountdown } from '@/features/transit/transit-utils';
import { tripSummary, formatTripOptionLeaveTime } from './trip-timeline';
import { ShimmerText } from './shimmer-text';
import type { TripPlan } from './types';
import { SleepingIcon } from '@/components/ui/sleeping-icon';
import { StatusLabel } from '@/components/ui/status-label';

interface TripCardProps {
  trip: TripPlan;
  onTap: () => void;
  /** 'row' drops the card chrome for a list that sits on a section card. */
  variant?: 'card' | 'row';
}

/**
 * Compact trip row: the leave-at time is the hero (or the next departure
 * countdown when the timeline can't resolve) and the next suggested departures
 * sit beside the trip name without being clipped on narrow screens.
 */
export function TripCard({ trip, onTap, variant = 'card' }: TripCardProps) {
  const now = useNow(1000);
  const { timeline, options, tooTightOptions } = useTripTimeline(trip);
  const tightDeparture = tooTightOptions[0] ?? null;
  const summary = tripSummary(timeline);
  const { departure: firstDeparture, stale } = useFirstLegDeparture(trip.legs[0], now);
  const accent = trip.legs[0]?.routes[0]?.routeColor || 'a855f7';

  const firstTransit = timeline.legs?.find((l) => l.mode !== 'WALK');
  // Offline or an old snapshot: the times are the last known, never "live".
  const isLive = !stale && (!!firstTransit?.isPredicted || !!firstDeparture?.isLive);

  const leftMinutes =
    timeline.leaveAt != null ? Math.max(0, Math.round((timeline.leaveAt - now) / 60_000)) : null;

  // Later leave-at times of the same journey — the card's second-most
  // prominent information after the leave hero. Deduped, capped at three
  // (the third pill only shows when there is room for it).
  const nextLeaves = useMemo(() => {
    if (timeline.leaveAt == null) return [];
    const leaveAt = timeline.leaveAt;
    const seen = new Set<number>();
    const out: Array<{ key: number; time: string }> = [];
    for (const option of options) {
      const t = option.timeline.leaveAt;
      const time = formatTripOptionLeaveTime(option);
      if (t == null || time == null || t <= leaveAt || seen.has(t)) continue;
      seen.add(t);
      out.push({ key: t, time });
      if (out.length === 3) break;
    }
    return out;
  }, [options, timeline.leaveAt]);

  const tightLeaveTime = tightDeparture ? formatTripOptionLeaveTime(tightDeparture) : null;

  const liveCaption =
    summary && leftMinutes != null
      ? leftMinutes <= 0
        ? 'now'
        : `in ${formatPrimeCountdown(leftMinutes)}`
      : firstDeparture
        ? firstDeparture.isLive
          ? 'live'
          : stale
            ? 'last known'
            : 'scheduled'
        : null;
  const captionIsLive = summary ? isLive : !!firstDeparture?.isLive;
  const hasDepartures = !!summary || !!firstDeparture;

  if (variant === 'row') {
    return (
      <button
        type="button"
        onClick={onTap}
        className="transit-monitor card-glass-interactive flex h-full w-full cursor-pointer items-stretch gap-3 px-3.5 py-3 text-left focus:outline-none focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-primary"
      >
        {!hasDepartures ? (
          <span className="flex min-w-0 flex-1 items-center gap-3">
            <SleepingIcon className="h-5 w-5 shrink-0 text-indigo-500 dark:text-indigo-300" />
            <span className="min-w-0">
              <span className="block truncate text-sm font-semibold text-(--monitor-text)">{trip.name}</span>
              <span className="block text-xs font-medium text-(--monitor-muted)">No departures right now</span>
            </span>
          </span>
        ) : (
          <>
            {/* The trip and its other leave times */}
            <span className="flex min-w-0 flex-1 flex-col justify-center gap-2">
              <span className="truncate text-sm font-semibold text-(--monitor-text)">{trip.name}</span>
              {(tightLeaveTime || nextLeaves.length > 0) && (
                <span className="flex min-w-0 items-center gap-1.5 overflow-hidden">
                  {tightLeaveTime && (
                    <StatusLabel
                      tone="amber"
                      icon={RiTimerLine}
                      title="This leave time has passed"
                      className="h-6 shrink-0 leading-none tabular-nums"
                    >
                      {tightLeaveTime}
                    </StatusLabel>
                  )}
                  {/* "or" joins the times to the passed-leave pill; with nothing
                      before it, a leading "or" reads as a dangling word. */}
                  {tightLeaveTime && nextLeaves.length > 0 && (
                    <span className="shrink-0 text-xs font-medium text-(--monitor-muted)">or</span>
                  )}
                  {nextLeaves.map(({ key, time }, i) => (
                    <span
                      key={key}
                      className={cn(
                        'inline-flex h-6 shrink-0 items-center rounded-full bg-(--monitor-chip) px-2 text-xs font-bold leading-none tabular-nums text-(--monitor-sched)',
                        i > 1 && 'hidden sm:inline-flex'
                      )}
                    >
                      {time}
                    </span>
                  ))}
                </span>
              )}
            </span>

            {/* When to leave, on the right like every time on the page */}
            <span className="flex shrink-0 flex-col items-end justify-center border-l border-(--monitor-rule) pl-3">
              <span className="text-xs font-medium text-(--monitor-muted)">{summary ? 'Leave at' : 'Next in'}</span>
              <span
                className={cn(
                  'mt-0.5 text-[22px] font-extrabold leading-none tracking-tight tabular-nums',
                  isLive ? 'text-(--monitor-live)' : 'text-(--monitor-sched)'
                )}
              >
                {summary
                  ? summary.leaveAt
                  : firstDeparture
                    ? formatPrimeCountdown(firstDeparture.minutes)
                    : '--'}
              </span>
              {captionIsLive ? (
                <span className="mt-1.5 flex items-center gap-1 text-xs font-bold tabular-nums text-tint-emerald">
                  <LiveSignalIcon className="h-3 w-3 shrink-0" />
                  {liveCaption}
                </span>
              ) : (
                <span className="mt-1.5 flex items-center gap-1 text-xs font-semibold tabular-nums text-(--monitor-muted)">
                  <RiTimeLine className="h-3 w-3 shrink-0" />
                  {liveCaption}
                </span>
              )}
            </span>
          </>
        )}
      </button>
    );
  }

  return (
    <div
      className={cn(
        'group relative w-full overflow-hidden min-h-24 flex items-stretch',
        variant === 'card' ? 'rounded-3xl card-glass-interactive' : 'rounded-2xl transition-colors hover:bg-surface-overlay'
      )}
    >
      {variant === 'card' && (
        <div
          aria-hidden
          className="pointer-events-none absolute inset-0 opacity-70 transition-opacity duration-300 group-hover:opacity-100"
          style={{
            background: `radial-gradient(180px 160px at 0% 0%, #${accent}33, transparent 70%)`,
          }}
        />
      )}

      <button
        type="button"
        onClick={onTap}
        className={cn(
          'flex flex-1 min-w-0 items-stretch py-3.5 text-left cursor-pointer focus:outline-none focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-primary',
          variant === 'card' ? 'px-4 sm:px-5 rounded-3xl' : 'px-1.5 rounded-2xl'
        )}
      >
        {!hasDepartures ? (
          <div className="relative flex flex-1 items-center justify-center gap-2.5 min-w-0 px-2">
            <SleepingIcon className="w-6 h-6 shrink-0 text-indigo-400 dark:text-indigo-300 drop-shadow-[0_0_8px_rgba(165,180,252,0.35)]" />
            <div className="min-w-0">
              <p className="text-sm font-bold tracking-tight text-primary truncate">{trip.name}</p>
              <p className="text-xs font-semibold text-muted">No departures right now</p>
            </div>
          </div>
        ) : (
          <>
            {/* Leave-at hero */}
            <div className="relative flex flex-col justify-center shrink-0 w-[84px] sm:w-[96px] min-w-0">
              <span className="mb-1 text-xs font-semibold text-secondary">{summary ? 'Leave at' : 'Next in'}</span>
              <span
                className={cn(
                  'font-mono font-black leading-none tracking-tight text-2xl',
                  isLive ? 'text-tint-emerald' : 'text-primary'
                )}
              >
                {summary
                  ? summary.leaveAt
                  : firstDeparture
                    ? formatPrimeCountdown(firstDeparture.minutes)
                    : '--'}
              </span>
              {captionIsLive ? (
                <span className="mt-1.5 self-start flex max-w-full items-center gap-1 text-xs font-bold tabular-nums text-tint-emerald">
                  <LiveSignalIcon className="w-3 h-3 shrink-0" />
                  <ShimmerText>{liveCaption}</ShimmerText>
                </span>
              ) : (
                <span className="mt-1.5 self-start flex items-center gap-1 text-xs font-semibold tabular-nums text-muted">
                  <RiTimeLine className="w-3 h-3 shrink-0" />
                  <span className="truncate">{liveCaption}</span>
                </span>
              )}
            </div>

            {/* Journey block */}
            <div className="relative flex-1 min-w-0 flex flex-col justify-center gap-1 border-l border-subtle/60 pl-3 sm:pl-4">
              <div className="flex items-center gap-2 min-w-0">
                <p className="text-sm font-bold tracking-tight text-primary truncate sm:text-base">
                  {trip.name}
                </p>
              </div>

              {(tightLeaveTime || nextLeaves.length > 0) && (
                <div className="flex flex-wrap items-center gap-1.5 min-w-0">
                  {tightLeaveTime && (
                    <StatusLabel
                      tone="amber"
                      icon={RiTimerLine}
                      title="This leave time has passed"
                      className="h-6 font-mono leading-none tabular-nums"
                    >
                      {tightLeaveTime}
                    </StatusLabel>
                  )}
                  {nextLeaves.length > 0 && (
                    <span className="shrink-0 text-xs font-semibold text-muted">Then</span>
                  )}
                  {nextLeaves.map(({ key, time }, i) => (
                    <span
                      key={key}
                      className={cn(
                        'inline-flex h-6 shrink-0 items-center rounded-full bg-surface-tertiary px-2 font-mono text-xs font-bold leading-none tabular-nums text-primary',
                        i > 1 && 'hidden sm:inline-flex'
                      )}
                    >
                      <ShimmerText tone="primary">{time}</ShimmerText>
                    </span>
                  ))}
                </div>
              )}
            </div>
          </>
        )}
      </button>
    </div>
  );
}
