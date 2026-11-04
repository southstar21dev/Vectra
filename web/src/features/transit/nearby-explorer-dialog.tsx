import { Suspense, lazy, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { motion, useReducedMotion } from 'framer-motion';
import { RiArrowRightLine, RiTimeLine, RiFocus3Line, RiMapPinLine, RiCloseLine } from '@remixicon/react';
import { Dialog } from '@/components/ui/dialog';
import { resolveDialogOrigin } from '@/components/ui/dialog-origin';
import { NearbyDeparturesSheet } from './nearby-departures-sheet';
import { MapFallback } from './map/map-fallback';
import type { ExplorerCameraRequest } from './nearby-explorer-map';

// Lazy: the explorer map pulls in maplibre, kept out of the eager bundle.
const LazyNearbyExplorerMap = lazy(() => import('./nearby-explorer-map').then((m) => ({ default: m.NearbyExplorerMap })));
import { RouteBadge } from './route-badge';
import { LiveSignalIcon } from './transit-card';
import { formatNearbyCountdown } from './nearby-stops';
import type { RadarDepartureItem } from './nearby-stops';
import { useNearbyExplorer } from './use-nearby-explorer';
import { withHash } from './map/colors';

export default function NearbyExplorerDialog({
  open,
  onClose,
  onExited,
}: {
  open: boolean;
  onClose: () => void;
  onExited?: () => void;
}) {
  const explorer = useNearbyExplorer();
  const reduceMotion = useReducedMotion();
  const [cameraRequest, setCameraRequest] = useState<ExplorerCameraRequest | null>(null);
  const [sheetCoveredPx, setSheetCoveredPx] = useState(0);
  const [mapReady, setMapReady] = useState(false);
  const handleMapReady = useCallback(() => setMapReady(true), []);
  const closeButtonRef = useRef<HTMLButtonElement>(null);

  // Never leave the overlay stuck if readiness never arrives (broken WebGL,
  // offline tiles): cap it.
  useEffect(() => {
    if (mapReady) return;
    const timer = window.setTimeout(() => setMapReady(true), 2500);
    return () => window.clearTimeout(timer);
  }, [mapReady]);

  const shellRef = useRef<HTMLDivElement>(null);

  const handleSheetCoverageChange = useCallback((coveredPx: number, settled: boolean) => {
    // The coverage drives a CSS variable (map attribution offset), written
    // straight to the DOM: routing every drag frame through React state
    // re-rendered the whole map subtree and the 40-row feed on each pointer
    // move, which is what made the sheet drag stutter on phones. Only the
    // settled value goes through state, because only that one re-queries the
    // map viewport and re-renders the feed.
    shellRef.current?.style.setProperty('--explorer-sheet-height', `${coveredPx}px`);
    if (settled) setSheetCoveredPx(coveredPx);
  }, []);

  useEffect(() => {
    const previouslyFocused =
      document.activeElement instanceof HTMLElement ? document.activeElement : null;
    closeButtonRef.current?.focus();
    return () => {
      previouslyFocused?.focus();
    };
  }, []);

  // Expand from the tap point that opened the explorer. The panel fills the
  // viewport, so the tap is already inside it — the raw point is the origin.
  useLayoutEffect(() => {
    const el = shellRef.current;
    if (!el || reduceMotion) return;
    const origin = resolveDialogOrigin();
    if (!origin) return;
    el.style.transformOrigin = `${origin.x}px ${origin.y}px`;
  }, [reduceMotion]);

  const user = useMemo(
    () =>
      explorer.geoState.status === 'tracking'
        ? {
            lat: explorer.geoState.lat,
            lon: explorer.geoState.lon,
            accuracy: explorer.geoState.accuracy,
          }
        : null,
    [
      explorer.geoState.status,
      explorer.geoState.lat,
      explorer.geoState.lon,
      explorer.geoState.accuracy,
    ]
  );

  const handleRecenter = useCallback(() => {
    const target = explorer.anchor ?? explorer.initialCenter;
    setCameraRequest({ lat: target.lat, lon: target.lon, zoom: 15.5, nonce: Date.now() });
    if (explorer.geoState.status !== 'tracking') explorer.refreshLocation();
  }, [explorer]);

  const handleSelectItem = useCallback(
    (item: RadarDepartureItem) => {
      explorer.handleSelectItem(item);
      const stop = explorer.stopsInView.find((s) => s.id === item.stopId);
      // Match the selection and marker, which prefer the departure's own
      // physical stop over the catalog station centre.
      const lat = item.platformLat ?? stop?.lat;
      const lon = item.platformLon ?? stop?.lon;
      if (lat !== undefined && lon !== undefined) {
        setCameraRequest({ lat, lon, zoom: 15.5, nonce: Date.now() });
      }
    },
    [explorer]
  );

  const selectionKey = explorer.selection
    ? {
        stopId: explorer.selection.stopId,
        routeId: explorer.selection.routeId,
        headsign: explorer.selection.headsign,
        shortName: explorer.selection.shortName,
        color: explorer.selection.color,
        textColor: explorer.selection.textColor,
      }
    : null;

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()} fullscreen onExited={onExited}>
      <motion.div
        ref={shellRef}
        role="dialog"
        aria-modal="true"
        aria-label="Nearby transit map"
        className="explorer-map-shell relative h-full w-full overflow-hidden bg-surface-primary"
        // Same entry motion as the compact dialogs (COMPACT_MOTION in
        // dialog-presentation.ts): expand from the tap point (transform-origin
        // is set below) out to full screen. Plain fade when reduced motion is
        // requested. The exit mirrors the entry for X/back closes; the
        // edge-swipe animates its own drag-out.
        initial={reduceMotion ? { opacity: 0 } : { opacity: 0, scale: 0.96 }}
        animate={reduceMotion ? { opacity: 1 } : { opacity: 1, scale: 1 }}
        exit={reduceMotion ? { opacity: 0 } : { opacity: 0, scale: 0.98 }}
        transition={
          reduceMotion
            ? { duration: 0.15, ease: 'easeOut' }
            : { type: 'spring', stiffness: 420, damping: 38, mass: 0.9 }
        }
        // Match the compact dialogs: corners follow the physical display
        // radius on iOS home-screen apps (--screen-radius is 0 elsewhere).
        style={{ borderRadius: 'var(--screen-radius, 0px)' }}
      >
        <Suspense fallback={<MapFallback />}>
          <LazyNearbyExplorerMap
            initialCenter={explorer.initialCenter}
            stops={explorer.mapStops}
            focusedStopId={explorer.focusedStopId}
            selectedStop={explorer.selectedStop}
            polylines={explorer.polylines}
            routeStops={explorer.routeStops}
            routeColor={explorer.selection?.color ?? 'F7C600'}
            vehicles={explorer.vehicles}
            cameraRequest={cameraRequest}
            user={user}
            bottomInsetPx={sheetCoveredPx}
            onReady={handleMapReady}
            onViewportChange={explorer.handleViewportChange}
            onSelectStop={explorer.focusStop}
          />
        </Suspense>

        {/* Covers the map's blank init frame until the first idle paint. */}
        <div
          aria-hidden
          className={`pointer-events-none absolute inset-0 z-10 flex flex-col items-center justify-center gap-3 bg-surface-primary transition-opacity duration-300 ${
            mapReady ? 'opacity-0' : 'opacity-100'
          }`}
        >
          <div className="h-8 w-8 animate-spin rounded-full border-2 border-tint-blue border-t-transparent" />
          <p className="text-xs font-medium text-secondary">Loading map…</p>
        </div>

        <div className="pointer-events-none absolute inset-x-0 top-0 z-10 h-28 bg-gradient-to-b from-black/35 to-transparent" />

        <div className="pointer-events-none absolute inset-x-0 bottom-[calc(var(--explorer-sheet-height,0px)+0.75rem)] z-20 flex justify-end px-3 sm:px-4">
          <div className="pointer-events-auto flex flex-col items-center gap-2">
            <button
              type="button"
              onClick={handleRecenter}
              title="Recenter on your location"
              aria-label="Recenter on your location"
              className="flex h-11 w-11 items-center justify-center rounded-xl border border-subtle bg-surface-secondary/95 text-secondary shadow-lg transition-colors hover:text-primary active:scale-95"
            >
              <RiFocus3Line className="h-5 w-5" />
            </button>
            <button
              ref={closeButtonRef}
              type="button"
              onClick={onClose}
              title="Close map"
              aria-label="Close map"
              className="flex h-11 w-11 items-center justify-center rounded-xl border border-subtle bg-surface-secondary/95 text-secondary shadow-lg transition-colors hover:text-primary active:scale-95"
            >
              <RiCloseLine className="h-5 w-5" />
            </button>
          </div>
        </div>

        {explorer.selection ? (
          <div
            className="pointer-events-auto absolute inset-x-3 top-[calc(var(--safe-area-top,0px)+0.75rem)] z-20 box-border flex min-w-0 items-center gap-2 overflow-hidden rounded-2xl border bg-surface-secondary px-3 py-2.5 text-left shadow-lg sm:inset-x-4 sm:gap-3"
            style={{
              borderColor: `${withHash(explorer.selection.color)}80`,
              boxShadow: `0 5px 18px ${withHash(explorer.selection.color)}28`,
            }}
          >
            <span
              aria-hidden
              className="absolute inset-y-2 left-0 w-1 rounded-r-full"
              style={{ background: withHash(explorer.selection.color) }}
            />
            <RouteBadge
              name={explorer.selection.shortName}
              color={explorer.selection.color}
              textColor={explorer.selection.textColor}
              size="lg"
            />
            <span className="min-w-0 flex-1 self-stretch flex flex-col justify-center py-0.5">
              <span className="flex items-center gap-1.5">
                <span className="flex h-3 w-3 shrink-0 items-center justify-center">
                  <RiArrowRightLine className="h-3 w-3 text-secondary" />
                </span>
                <span className="truncate text-[11px] font-bold text-primary">
                  {explorer.selection.headsign || 'Any direction'}
                </span>
              </span>
              <span className="mt-1 flex min-w-0 items-center gap-1.5 truncate text-[10px] font-medium text-muted">
                <span className="flex h-3 w-3 shrink-0 items-center justify-center">
                  <span aria-hidden className="h-1.5 w-1.5 rounded-full bg-text-secondary/60" />
                </span>
                <span className="truncate">{explorer.selection.stopName}</span>
              </span>
            </span>            <span
              className={`flex flex-nowrap shrink-0 items-center gap-2 whitespace-nowrap font-mono text-sm font-black tabular-nums tracking-tight ${explorer.selection.isPredicted                ? 'text-tint-emerald'
                : 'text-primary/80'}`}
              aria-label={explorer.selection.isPredicted ? 'Live arrival' : 'Scheduled arrival'}
            >
              {explorer.selection.isPredicted ? (
                <LiveSignalIcon className="h-3.5 w-3.5" />
              ) : (
                <RiTimeLine className="h-3.5 w-3.5" />
              )}
              <span>{formatNearbyCountdown(explorer.trackedCountdownMinutes ?? 0)}</span>
            </span>
          </div>
        ) : explorer.focusedStop ? (
          <div className="pointer-events-auto absolute inset-x-3 top-[calc(var(--safe-area-top,0px)+0.75rem)] z-20 flex items-center gap-3 rounded-2xl border border-subtle bg-surface-secondary/95 px-3 py-2.5 shadow-lg sm:inset-x-4">
            <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-tint-blue/10 text-tint-blue">
              <RiMapPinLine className="h-4 w-4" />
            </span>
            <div className="min-w-0 flex-1">
              <p className="truncate text-[11px] font-bold text-primary">{explorer.focusedStop.name}</p>
              <p className="truncate text-[10px] font-medium text-muted">Selected stop</p>
            </div>
            <button
              type="button"
              onClick={explorer.clearFocus}
              title="Clear selected stop"
              aria-label="Clear selected stop"                className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-secondary transition-colors hover:bg-surface-tertiary hover:text-primary"
            >
              <RiCloseLine className="h-4 w-4" />
            </button>
          </div>
        ) : null}

        {explorer.anchor?.outsideHungary && (
          <div className={`absolute inset-x-3 z-20 rounded-xl border border-tint-amber/30 bg-amber-500/10 px-3 py-2 text-[11px] font-medium text-tint-amber ${explorer.selection || explorer.focusedStop ? 'top-[calc(var(--safe-area-top,0px)+8rem)]' : 'top-[calc(var(--safe-area-top,0px)+4rem)]'}`}>
            Outside Hungary — showing Budapest center.
          </div>
        )}

        {explorer.geoState.status === 'error' && (
          <div className={`absolute inset-x-3 z-20 flex items-center justify-between gap-3 rounded-xl border border-red-500/30 bg-red-500/10 px-3 py-2 text-[11px] font-medium text-red-400 ${explorer.selection || explorer.focusedStop ? 'top-[calc(var(--safe-area-top,0px)+8rem)]' : 'top-[calc(var(--safe-area-top,0px)+4rem)]'}`}>
            <span>{explorer.geoState.errorMessage}</span>
            <button
              type="button"
              onClick={explorer.refreshLocation}
              className="shrink-0 rounded-full border border-red-400/40 px-3 py-1 text-[10px] font-semibold transition-colors hover:bg-red-500/20"
            >
              Retry
            </button>
          </div>
        )}

        <NearbyDeparturesSheet
          feed={explorer.feed}
          selection={selectionKey}
          onSelectItem={handleSelectItem}
          modeFilter={explorer.modeFilter}
          onToggleMode={explorer.handleToggleMode}
          arrivalsEnabled={explorer.arrivalsEnabled}
          isLoading={explorer.isArrivalsLoading}
          visibleStopCount={explorer.visibleStopCount}
          polledStopCount={explorer.polledStopCount}
          onCoverageChange={handleSheetCoverageChange}
        />
      </motion.div>
    </Dialog>
  );
}
