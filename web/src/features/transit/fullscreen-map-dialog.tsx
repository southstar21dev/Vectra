import { useId, useState, Suspense, lazy } from 'react';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { LoadingOverlay } from '@/components/ui/loading-overlay';
import { SheetBar } from '@/features/trips/sheet-chrome';
import { TrackedStopTabs } from './tracker-list-row';
import { RouteBadge } from './route-badge';
import { MapFallback } from './map/map-fallback';

// Lazy: RouteMap pulls in maplibre, kept out of the eager bundle.
const LazyRouteMap = lazy(() => import('./route-map').then((m) => ({ default: m.RouteMap })));
import type { RouteMapSecondaryLine, RouteMapStop, RouteMapVehicle } from './map/types';
import type { LatLon } from './route-geometry';
import type { TrackerStop } from './types';

export interface FullscreenMapDialogProps {
  open: boolean;
  onClose: () => void;
  routeShortName: string;
  routeColor: string;
  routeTextColor?: string | null;
  stopName: string;
  stops: RouteMapStop[];
  polyline: LatLon[];
  vehicles: RouteMapVehicle[];
  secondaryLines?: RouteMapSecondaryLine[];
  trackerStops?: TrackerStop[];
  activeStopId?: string;
  onSelectStop?: (stopId: string) => void;
}

/**
 * Full-viewport version of the live-position sheet's route map. A FRESH
 * RouteMap instance mounts here (auto-fits on mount like the mini map) and
 * receives the same live props the parent passes down, so the 10s vehicle poll
 * keeps moving the markers while it's open.
 *
 * Same sheet chrome as the live-position sheet: the bar carries the route and
 * stop, the tracked stops as a chip strip, and the map fills everything below.
 */
export function FullscreenMapDialog({
  open,
  onClose,
  routeShortName,
  routeColor,
  routeTextColor,
  stopName,
  stops,
  polyline,
  vehicles,
  secondaryLines = [],
  trackerStops = [],
  activeStopId,
  onSelectStop,
}: FullscreenMapDialogProps) {
  const titleId = useId();
  const [mapReady, setMapReady] = useState(false);
  const [prevOpen, setPrevOpen] = useState(open);

  // The parent stays mounted while DialogContent unmounts, so reset readiness
  // on every closed→open transition — otherwise the remounted map skips the
  // cover and flashes. Adjusted during render so the stale value cannot paint.
  if (open !== prevOpen) {
    setPrevOpen(open);
    if (open) setMapReady(false);
  }

  const currentStopId = activeStopId ?? trackerStops[0]?.stop_id;

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      {open && (
        <DialogContent
          edgeToEdge
          aria-labelledby={titleId}
          className="sm:max-w-4xl h-[92vh] max-h-[92vh] rounded-[28px] p-0 overflow-hidden flex flex-col gap-0"
        >
          <SheetBar
            leading="close"
            onLeading={onClose}
            titleId={titleId}
            title={
              <span className="inline-flex max-w-full items-center gap-2">
                <RouteBadge size="sm" name={routeShortName} color={routeColor} textColor={routeTextColor} />
                <span className="min-w-0 truncate">{stopName}</span>
              </span>
            }
            subtitle="Live map"
            accessory={
              trackerStops.length > 1 ? (
                <TrackedStopTabs stops={trackerStops} activeStopId={currentStopId} onSelect={(id) => onSelectStop?.(id)} />
              ) : undefined
            }
          />

          {/* The map takes everything under the bar as one rounded, bordered
              box like the live sheet's map, ending clear of the home indicator. */}
          <div
            className="relative isolate mx-5 min-h-0 flex-1 overflow-hidden rounded-2xl border border-subtle bg-surface-overlay sm:mx-6"
            style={{ marginBottom: 'calc(env(safe-area-inset-bottom, 0px) + 1rem)' }}
          >
            <Suspense fallback={<MapFallback />}>
              <LazyRouteMap
                stops={stops}
                polyline={polyline}
                vehicles={vehicles}
                secondaryLines={secondaryLines}
                routeColor={routeColor}
                fillHeight
                onReady={() => setMapReady(true)}
              />
            </Suspense>
            <LoadingOverlay show={!mapReady} label="Loading map…" />
          </div>
        </DialogContent>
      )}
    </Dialog>
  );
}
