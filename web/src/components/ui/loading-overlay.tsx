import { useEffect, useState, type ReactNode } from 'react';
import { cn } from '@/lib/utils';
import { Spinner } from './spinner';

export interface LoadingOverlayProps {
  /** Whether the overlay is covering its container. */
  show: boolean;
  label?: string;
  className?: string;
  /** Safety cap: release the cover after this long even if `show` stays true. */
  maxDurationMs?: number;
  /** Replaces the default spinner. */
  indicator?: ReactNode;
  /** Classes for the indicator + label stack, e.g. to keep it in view on a
   *  container taller than the screen. */
  contentClassName?: string;
}

/**
 * Full-container loading cover for dialogs and map surfaces. The parent must
 * be `relative` (or otherwise positioned) so the overlay fills it. Fades out
 * over the loaded content instead of hard-swapping, so nothing pops in.
 *
 * `maxDurationMs` caps how long the overlay can stay up: a readiness signal
 * that never arrives (broken WebGL, failed style fetch, offline tiles) must
 * never trap the spinner.
 */
export function LoadingOverlay({
  show,
  label = 'Loading…',
  className,
  maxDurationMs = 3000,
  indicator,
  contentClassName,
}: LoadingOverlayProps) {
  const [expired, setExpired] = useState(false);
  const [prevShow, setPrevShow] = useState(show);

  // Reset the cap on every show transition (the effect below clears any armed
  // timer), adjusted during render so a new load can never inherit the
  // previous load's expiry.
  if (show !== prevShow) {
    setPrevShow(show);
    setExpired(false);
  }

  useEffect(() => {
    if (!show) return;
    const timer = window.setTimeout(() => setExpired(true), maxDurationMs);
    return () => window.clearTimeout(timer);
  }, [show, maxDurationMs]);

  const visible = show && !expired;

  return (
    <div
      aria-hidden={!visible}
      role={visible ? 'status' : undefined}
      className={cn(
        'pointer-events-none absolute inset-0 z-[5] flex flex-col items-center justify-center gap-3 bg-surface-secondary transition-opacity duration-300',
        visible ? 'opacity-100' : 'opacity-0',
        className
      )}
    >
      <div className={cn('flex flex-col items-center gap-3', contentClassName)}>
        {indicator ?? <Spinner />}
        <p className="text-xs font-medium text-secondary">{label}</p>
      </div>
    </div>
  );
}
