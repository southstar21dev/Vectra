import { Suspense, lazy, useEffect, useState } from 'react';
import { RiCrosshair2Line, RiFullscreenLine } from '@remixicon/react';
import { Button } from '@/components/ui/button';
import { LoadingOverlay } from '@/components/ui/loading-overlay';
import { cn } from '@/lib/utils';
import type { PeekPin, PeekStop } from './nearby-peek-map';
import type { LatLon } from './route-geometry';
import type { NearbyLocationStatus } from './use-nearby-data';

// Lazy: the map slice pulls in maplibre, kept out of the eager bundle.
const LazyPeekMap = lazy(() => import('./nearby-peek-map').then((m) => ({ default: m.NearbyPeekMap })));


interface NearbyMapStripProps {
  status: NearbyLocationStatus;
  position: LatLon | null;
  stops: PeekStop[];
  pins?: PeekPin[];
  highlightStopId?: string | null;
  /** Opens the nearby map sheet (tap anywhere, or the expand button). */
  onOpen: () => void;
  onLocate: () => void;
  /** Reports whether a map is still loading, for a parent that covers it with
   *  its own spinner (pair with `showLoadingOverlay={false}`). */
  onMapPendingChange?: (pending: boolean) => void;
  showLoadingOverlay?: boolean;
  /** Overrides the strip's height (default 132px). */
  className?: string;
}

const STATUS_TEXT: Record<Exclude<NearbyLocationStatus, 'tracking'>, string> = {
  locating: 'Finding your location…',
  failed: 'Location unavailable',
  off: 'Turn on location to see stops near you',
};

/**
 * The top of the transit cards: a slice of the map around you that opens the
 * nearby sheet, with a round expand button (and Locate when the fix failed).
 */
export function NearbyMapStrip({
  status,
  position,
  stops,
  pins,
  highlightStopId = null,
  onOpen,
  onLocate,
  onMapPendingChange,
  showLoadingOverlay = true,
  className,
}: NearbyMapStripProps) {
  const [mapReady, setMapReady] = useState(false);
  const tracking = status === 'tracking' && position !== null;
  const mapPending = tracking && !mapReady;

  useEffect(() => {
    onMapPendingChange?.(mapPending);
  }, [mapPending, onMapPendingChange]);

  return (
    <div className={cn('relative h-[132px] overflow-hidden bg-surface-overlay', className)}>
      <button
        type="button"
        onClick={onOpen}
        aria-label="Open the nearby transit map"
        className="absolute inset-0 z-10 cursor-pointer focus:outline-none focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent-primary"
      />
      {tracking && position ? (
        // Absolute so the map resolves its size against the slice.
        <div className="pointer-events-none absolute inset-0">
          <Suspense fallback={null}>
            <LazyPeekMap
              center={position}
              stops={stops}
              pins={pins}
              highlightStopId={highlightStopId}
              onReady={() => setMapReady(true)}
            />
          </Suspense>
        </div>
      ) : (
        <div className="pointer-events-none flex h-full flex-col items-center justify-center gap-2 pb-4 text-center">
          {status === 'locating' ? (
            <span className="h-4 w-4 animate-spin rounded-full border-2 border-tint-blue/30 border-t-tint-blue" />
          ) : (
            <RiCrosshair2Line className="h-5 w-5 text-tint-blue" aria-hidden />
          )}
          <p className="text-xs font-semibold text-secondary">{STATUS_TEXT[status === 'tracking' ? 'locating' : status]}</p>
        </div>
      )}
      {tracking && showLoadingOverlay && <LoadingOverlay show={!mapReady} label="Loading map…" />}
      <div aria-hidden className="pointer-events-none absolute inset-x-0 bottom-0 h-16 bg-gradient-to-t from-surface-secondary to-transparent" />

      <div className="absolute right-3 top-3 z-20 flex items-center gap-1.5">
        {status === 'failed' && (
          <Button variant="outline" size="sm" onClick={onLocate} className="h-8 rounded-full text-xs">
            Locate
          </Button>
        )}
        <button
          type="button"
          onClick={onOpen}
          aria-label="Open the nearby transit map"
          title="Open the nearby transit map"
          className="flex h-8 w-8 items-center justify-center rounded-full border border-subtle bg-surface-secondary/80 text-secondary shadow-sm backdrop-blur-sm transition-colors hover:text-primary cursor-pointer active:scale-95 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary"
        >
          <RiFullscreenLine className="h-3.5 w-3.5" aria-hidden />
        </button>
      </div>
    </div>
  );
}
