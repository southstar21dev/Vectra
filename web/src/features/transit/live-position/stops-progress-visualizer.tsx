import { RiArrowRightFill, RiTimeLine } from '@remixicon/react';
import { motion, useReducedMotion } from 'framer-motion';
import { cn } from '@/lib/utils';
import type { RouteProgress } from '../live-position';

/**
 * Visualized animation for how many stops left to the user:
 * - Dynamic pulse track linking the approaching vehicle to the target "YOU" node
 * - Staggered glowing pearls for each intermediate stop
 * - Sweeping energetic light beam flowing along the track
 * - Special celebratory pulse capsule when arriving now (0 stops)
 * - Accessible with prefers-reduced-motion support
 */
/**
 * Visualized real-time position for how many stops left to the user:
 * - Shows the full line of stops to YOU (origin stop, intermediate station pearls, destination YOU node)
 * - The vehicle arrow sits between the two stops in REAL TIME (between its departed stop and approaching stop)
 * - As the vehicle travels in real time, it smoothly advances towards the approaching stop dot
 * - When the vehicle reaches that stop and starts approaching the next stop, it moves to the next dot segment
 * - Traversed track is illuminated behind the vehicle
 */
export function StopsProgressVisualizer({
  stopsLeft,
  liveProgress,
  hasVehicle,
  accent,
  approx,
}: {
  stopsLeft: number | null;
  liveProgress?: RouteProgress | null;
  hasVehicle: boolean;
  accent: 'emerald' | 'violet';
  approx: boolean;
}) {
  const shouldReduceMotion = useReducedMotion();
  const violet = accent === 'violet';
  const themeBg = approx
    ? 'bg-pink-500 dark:bg-pink-400'
    : violet
      ? 'bg-violet-500 dark:bg-violet-400'
      : 'bg-emerald-500 dark:bg-emerald-400';
  const themeText = approx
    ? 'text-pink-500 dark:text-pink-400'
    : violet
      ? 'text-violet-600 dark:text-violet-400'
      : 'text-emerald-600 dark:text-emerald-400';

  // Live remaining stops from real-time telemetry
  const liveStops = liveProgress?.stopsLeft ?? stopsLeft;

  if (liveStops === 0) {
    return (
      <div className="mt-3 pt-2.5 border-t border-subtle/40">
        <div className="relative flex items-center justify-between px-3 py-2 rounded-xl bg-emerald-500/10 border border-emerald-500/30 overflow-hidden shadow-sm">
          <div className="relative flex items-center gap-2">
            <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-emerald-500 shrink-0" />
            <span className="text-sm font-bold text-emerald-600 dark:text-tint-emerald">
              Arriving now
            </span>
          </div>
          <span className="relative text-xs font-semibold text-secondary">0 stops left</span>
        </div>
      </div>
    );
  }

  if (stopsLeft != null && stopsLeft > 0) {
    // Total segments to YOU for this session's track layout (up to 14 stops)
    const totalStops = Math.min(Math.max(stopsLeft, 1), 14);
    const intermediateDots = totalStops - 1;

    // Responsive station pearl size so higher stop counts fit gracefully without crowding
    const dotSizeClass =
      intermediateDots > 10
        ? 'w-1.5 h-1.5'
        : intermediateDots > 6
          ? 'w-2 h-2'
          : 'w-2.5 h-2.5';

    // Intermediate stop dots along the rail evenly spaced between origin (0%) and YOU (100%)
    const dotPositions = Array.from({ length: intermediateDots }).map((_, idx) => {
      return ((idx + 1) / totalStops) * 100;
    });

    // Real-time vehicle segment calculation:
    // When liveStops === totalStops: in segment 0 (between origin 0% and dot 0)
    // When liveStops drops (e.g. approaches next stop): advances to segment 1, 2, ...
    const activeLiveStops = liveStops != null && liveStops > 0 ? liveStops : stopsLeft;
    const segIdx = Math.max(0, Math.min(totalStops - 1, totalStops - activeLiveStops));
    const segStart = (segIdx / totalStops) * 100;
    const segEnd = ((segIdx + 1) / totalStops) * 100;

    // Fraction between the previous stop and the approaching stop from live telemetry
    const frac =
      liveProgress?.segmentFraction != null
        ? Math.max(0.12, Math.min(0.88, liveProgress.segmentFraction))
        : 0.45;

    // Real-time arrow position on the rail between the two stops
    const vehiclePosPercent = segStart + frac * (segEnd - segStart);

    return (
      <div className="mt-3 pt-2.5 border-t border-subtle/40 flex flex-col gap-2.5">
        {/* Counter Header: shows how many stops are to me */}
        <div className="flex items-center justify-between">
          <span
            className={cn(
              'text-sm font-bold tracking-tight',
              activeLiveStops === 1
                ? 'text-emerald-600 dark:text-tint-emerald'
                : 'text-primary'
            )}
          >
            {activeLiveStops === 1 ? 'Next stop' : `${activeLiveStops} stops to you`}
          </span>
        </div>

        {/* Animated Progress Track */}
        <div className="relative flex items-center px-1.5 py-1">
          {/* Track area spanning from vehicle origin to destination */}
          <div className="relative flex-1 h-7 flex items-center">
            {/* Base rail track - clearly visible in both light mode (#E4E4E7) and dark mode (white/20) */}
            <div className="absolute inset-x-0 top-1/2 -translate-y-1/2 h-1 bg-zinc-200 dark:bg-white/20 rounded-full" />

            {/* Trailing luminous track fill extending from origin stop up to the vehicle's real-time position */}
            <motion.div
              className={cn(
                'absolute left-0 top-1/2 -translate-y-1/2 h-1 rounded-full bg-gradient-to-r from-transparent via-current to-current opacity-90 pointer-events-none z-10',
                themeText
              )}
              animate={{ width: `${vehiclePosPercent}%` }}
              transition={
                shouldReduceMotion
                  ? { duration: 0 }
                  : { type: 'spring', damping: 24, stiffness: 90 }
              }
            />

            {/* Origin stop dot on the far left (vehicle departure stop) */}
            <div className="absolute left-0 top-1/2 -translate-y-1/2 -translate-x-1/2 z-[15] flex items-center justify-center pointer-events-none">
              <div
                className={cn(
                  dotSizeClass,
                  'rounded-full border-2 transition-[color,background-color,border-color,box-shadow,transform,opacity] duration-300',
                  vehiclePosPercent > 0
                    ? cn(themeBg, 'border-transparent shadow-sm')
                    : 'bg-white dark:bg-zinc-800 border-zinc-300 dark:border-zinc-600 shadow-sm'
                )}
              />
            </div>

            {/* Intermediate stop dots along the line */}
            {dotPositions.map((posPercent, idx) => {
              const isPassed = posPercent <= vehiclePosPercent;
              const isApproaching = idx === segIdx;
              return (
                <div
                  key={idx}
                  className="absolute top-1/2 -translate-y-1/2 -translate-x-1/2 z-[15] flex items-center justify-center pointer-events-none"
                  style={{ left: `${posPercent}%` }}
                >
                  <div
                    className={cn(
                      dotSizeClass,
                      'rounded-full border-2 transition-[color,background-color,border-color,box-shadow,transform,opacity] duration-300',
                      isPassed
                        ? cn(themeBg, 'border-transparent shadow-sm')
                        : isApproaching
                          ? cn(
                              'bg-white dark:bg-zinc-900 ring-2 shadow-sm',
                              approx
                                ? 'border-pink-500 text-pink-500 ring-pink-500/25'
                                : violet
                                  ? 'border-violet-500 text-violet-500 ring-violet-500/25'
                                  : 'border-emerald-500 text-emerald-500 ring-emerald-500/25'
                            )
                          : 'bg-white dark:bg-zinc-800 border-zinc-300 dark:border-zinc-600 shadow-sm'
                    )}
                  />
                </div>
              );
            })}

            {/* Destination "YOU" stop dot on the far right (100%) */}
            <div
              className="absolute top-1/2 -translate-y-1/2 -translate-x-1/2 z-[15] flex items-center justify-center pointer-events-none"
              style={{ left: '100%' }}
            >
              <div
                className={cn(
                  dotSizeClass,
                  'rounded-full border-2 border-blue-500 dark:border-blue-400 bg-blue-500 dark:bg-blue-500 shadow-sm ring-2 ring-blue-500/25'
                )}
                title="YOU"
              />
            </div>

            {/* Vehicle puck with solid non-transparent forward arrow sitting between the stops in real time */}
            <motion.div
              className="absolute top-1/2 -translate-y-1/2 -translate-x-1/2 z-20 flex items-center pointer-events-none"
              animate={{ left: `${vehiclePosPercent}%` }}
              transition={
                shouldReduceMotion
                  ? { duration: 0 }
                  : { type: 'spring', damping: 24, stiffness: 90 }
              }
            >
              {/* Solid, non-transparent vehicle beacon with forward-pointing arrow */}
              <div
                className={cn(
                  'relative flex items-center justify-center w-5 h-5 rounded-full border border-white/80 dark:border-white/40 shadow-md',
                  approx
                    ? 'bg-pink-500 text-white shadow-pink-500/30'
                    : violet
                      ? 'bg-violet-600 dark:bg-violet-500 text-white shadow-violet-500/30'
                      : 'bg-emerald-600 dark:bg-emerald-500 text-white shadow-emerald-500/30'
                )}
              >
                <RiArrowRightFill className="w-3 h-3 text-white" />
              </div>
            </motion.div>
          </div>
        </div>
      </div>
    );
  }

  // Fallback: has vehicle but approaching calculation not yet active
  if (hasVehicle) {
    return (
      <div className="mt-3 pt-2.5 border-t border-subtle/40 flex items-center justify-between text-sm">
        <div className="flex items-center gap-2 text-secondary">
          <span className="relative flex h-2 w-2 shrink-0">
            <span className={cn('relative inline-flex h-2 w-2 rounded-full', themeBg)} />
          </span>
          <span className="font-semibold text-secondary">Vehicle on route</span>
        </div>
        <span className="text-xs text-muted">Awaiting approach</span>
      </div>
    );
  }

  // Fallback: scheduled departure
  return (
    <div className="mt-3 pt-2.5 border-t border-subtle/40 flex items-center justify-between text-sm">
      <div className="flex items-center gap-2 text-muted">
        <RiTimeLine className="w-3.5 h-3.5 shrink-0" />
        <span>Scheduled departure</span>
      </div>
      <span className="text-xs text-muted">No live vehicle</span>
    </div>
  );
}
