import { useEffect, useMemo, useState } from 'react';
import { RiCloudyLine, RiPauseFill, RiPlayFill, RiResetLeftLine, RiSkipBackLine, RiSkipForwardLine } from '@remixicon/react';
import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query-keys';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { LoadingOverlay } from '@/components/ui/loading-overlay';
import { Button } from '@/components/ui/button';
import {
  fetchRainViewerData,
  formatRadarFrameLabel,
  type RadarFrame,
} from './radar-api';
import { RAINVIEWER_ATTRIBUTION, WeatherRadarMap } from './weather-radar-map';
import { OPENFREEMAP_ATTRIBUTION } from '@/lib/map-tiles';
import type { WeatherCoords, WeatherData } from './weather-api';

export interface WeatherRadarDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  coords: WeatherCoords;
  weatherData?: WeatherData;
}

export function WeatherRadarDialog({
  open,
  onOpenChange,
  coords,
}: WeatherRadarDialogProps) {
  const [frameIndex, setFrameIndex] = useState<number>(0);
  const [isPlaying, setIsPlaying] = useState<boolean>(false);
  const [mapReady, setMapReady] = useState(false);
  const [prevOpen, setPrevOpen] = useState(open);
  // Snap to the live frame once per open — not on every background refetch,
  // which would yank the slider away from the frame the user is looking at.
  const [snapToLive, setSnapToLive] = useState(true);

  // The dialog component stays mounted across open/close while DialogContent
  // unmounts, so reset readiness on every closed→open transition — otherwise
  // the remounted map skips the cover and flashes. Adjusted during render so
  // the stale value cannot paint.
  if (open !== prevOpen) {
    setPrevOpen(open);
    if (open) {
      setMapReady(false);
      setSnapToLive(true);
    }
  }

  // Fetch RainViewer radar metadata with react-query
  const {
    data: radarData,
    isLoading,
    isError,
    error,
    refetch,
  } = useQuery({
    queryKey: queryKeys.radar,
    queryFn: fetchRainViewerData,
    enabled: open,
    staleTime: 2 * 60 * 1000, // 2 minutes
    refetchInterval: open ? 5 * 60 * 1000 : false,
  });

  const activeFrames = useMemo(() => {
    return radarData?.radarFrames ?? [];
  }, [radarData]);

  // When the dialog opens, default to the latest past frame (current live
  // radar). A refetch while open keeps the frame on screen (same timestamp
  // when it is still in the list). Adjusted during render so the radar never
  // paints the wrong frame first.
  const [prevRadarData, setPrevRadarData] = useState<typeof radarData>(undefined);
  if (radarData !== prevRadarData || (snapToLive && radarData && activeFrames.length > 0)) {
    const previousFrame = prevRadarData?.radarFrames?.[frameIndex];
    setPrevRadarData(radarData);
    if (activeFrames.length > 0 && radarData) {
      if (snapToLive) {
        const latestPastIdx = activeFrames.findIndex((f) => f.time === radarData.latestPastTime);
        setFrameIndex(latestPastIdx >= 0 ? latestPastIdx : activeFrames.length - 1);
        setSnapToLive(false);
      } else {
        const sameIdx = previousFrame ? activeFrames.findIndex((f) => f.time === previousFrame.time) : -1;
        setFrameIndex(sameIdx >= 0 ? sameIdx : Math.min(frameIndex, activeFrames.length - 1));
      }
    }
  }

  // Playback animation loop
  useEffect(() => {
    if (!isPlaying || activeFrames.length === 0) return;

    const isLastFrame = frameIndex === activeFrames.length - 1;
    const intervalMs = isLastFrame ? 2200 : 900;

    const timer = setTimeout(() => {
      setFrameIndex((prev) => (prev + 1) % activeFrames.length);
    }, intervalMs);

    return () => clearTimeout(timer);
  }, [isPlaying, frameIndex, activeFrames.length]);

  const currentFrame: RadarFrame | null = activeFrames[frameIndex] ?? null;

  const currentLabel = useMemo(() => {
    if (!currentFrame || !radarData) return null;
    return formatRadarFrameLabel(currentFrame, radarData.latestPastTime);
  }, [currentFrame, radarData]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {open && (
        <DialogContent
          onClose={() => onOpenChange(false)}
          edgeToEdge
          className="max-w-4xl sm:max-w-5xl h-[86dvh] max-h-[820px] flex flex-col p-0 sm:p-0 overflow-hidden"
        >
          {/* Full-bleed Map */}
          <div className="relative flex-1 min-h-0 w-full overflow-hidden bg-surface-secondary">
            {/* Initial load renders nothing: the overlay covers this area, and
                rendering the error UI underneath would leave its Retry button
                tappable and focusable while invisible. */}
            {isLoading && !radarData ? null : isError || !radarData ? (
              <div className="flex flex-col items-center justify-center h-full gap-4 p-6 text-center">
                <RiCloudyLine className="w-10 h-10 text-muted" />
                <div>
                  <p className="text-sm font-semibold text-primary">Unable to load radar data</p>
                  <p className="text-xs text-secondary mt-1 max-w-xs">
                    {error instanceof Error ? error.message : 'Please check your internet connection or try again.'}
                  </p>
                </div>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => refetch()}
                  className="rounded-full gap-1.5 mt-2"
                >
                  <RiResetLeftLine className="w-3.5 h-3.5" /> Retry
                </Button>
              </div>
            ) : activeFrames.length === 0 ? (
              <div className="flex flex-col items-center justify-center h-full gap-4 p-6 text-center">
                <RiCloudyLine className="w-10 h-10 text-muted" />
                <div>
                  <p className="text-sm font-semibold text-primary">No radar frames available</p>
                  <p className="text-xs text-secondary mt-1 max-w-xs">
                    Radar frames are currently updating. Please check back in a moment.
                  </p>
                </div>
              </div>
            ) : (
              <>
                <WeatherRadarMap
                  center={{ lat: coords.latitude, lon: coords.longitude }}
                  activeFrame={currentFrame}
                  host={radarData.host}
                  colorScheme={1}
                  opacity={0.85}
                  className="h-full w-full"
                  onReady={() => setMapReady(true)}
                />

                {/* Floating Top Frame Badge */}
                {currentLabel && (
                  <div className="absolute top-[max(0.75rem,var(--safe-area-top,env(safe-area-inset-top,0px)))] left-3 z-[1000] pointer-events-none">
                    <div className="flex items-center gap-2 px-3 py-1.5 rounded-full card-glass shadow-lg border border-subtle text-primary bg-surface-primary/85">
                      <span
                        className={`w-2 h-2 rounded-full ${
                          currentLabel.relative === 'Now'
                            ? 'bg-emerald-500 animate-ping'
                            : 'bg-tint-blue'
                        }`}
                      />
                      <span className="text-xs font-mono font-bold">{currentLabel.timeStr}</span>
                      <span className="text-xs font-medium text-secondary">
                        ({currentLabel.relative})
                      </span>
                    </div>
                  </div>
                )}

                {/* Floating Bottom Controls — attribution sits centered just
                    above the glass panel so it is never hidden behind it. */}
                <div className="absolute bottom-[max(0.75rem,env(safe-area-inset-bottom,0px))] left-3 right-3 sm:left-6 sm:right-6 z-[1000] max-w-xl mx-auto pointer-events-none flex flex-col items-center gap-1.5">
                  <div
                    className="map-attribution pointer-events-auto text-center font-mono text-[9px] leading-tight"
                    dangerouslySetInnerHTML={{
                      __html: `${OPENFREEMAP_ATTRIBUTION} | ${RAINVIEWER_ATTRIBUTION}`,
                    }}
                  />
                  <div className="radar-glass-solid w-full pointer-events-auto px-3.5 py-2.5 sm:px-4 sm:py-3 rounded-2xl card-glass border border-subtle shadow-2xl flex flex-col gap-2">
                    {/* Scrubbing & Playback Bar */}
                    <div className="flex items-center gap-2 sm:gap-3">
                      <Button
                        variant="outline"
                        size="icon"
                        onClick={() => setIsPlaying((p) => !p)}
                        className="h-8 w-8 sm:h-9 sm:w-9 rounded-full shrink-0 border-subtle bg-surface-secondary hover:bg-surface-overlay text-primary cursor-pointer shadow-xs"
                        aria-label={isPlaying ? 'Pause radar animation' : 'Play radar animation'}
                      >
                        {isPlaying ? (
                          <RiPauseFill className="w-3.5 h-3.5 sm:w-4 sm:h-4 text-tint-amber" />
                        ) : (
                          <RiPlayFill className="w-3.5 h-3.5 sm:w-4 sm:h-4 ml-0.5 text-tint-amber" />
                        )}
                      </Button>

                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={() => {
                          setIsPlaying(false);
                          setFrameIndex((prev) => Math.max(0, prev - 1));
                        }}
                        disabled={frameIndex === 0}
                        className="h-7 w-7 sm:h-8 sm:w-8 rounded-full shrink-0 text-secondary hover:text-primary hidden sm:flex cursor-pointer"
                        aria-label="Step back one frame"
                      >
                        <RiSkipBackLine className="w-3.5 h-3.5" />
                      </Button>

                      {/* Timeline Scrubber */}
                      <div className="flex-1 relative flex items-center px-1">
                        <input
                          type="range"
                          min={0}
                          max={activeFrames.length - 1}
                          value={frameIndex}
                          onChange={(e) => {
                            setIsPlaying(false);
                            setFrameIndex(Number(e.target.value));
                          }}
                          className="w-full h-2 rounded-lg bg-surface-overlay appearance-none cursor-pointer accent-tint-amber focus:outline-hidden"
                          aria-label="Radar timeline frame selector"
                        />
                      </div>

                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={() => {
                          setIsPlaying(false);
                          setFrameIndex((prev) => Math.min(activeFrames.length - 1, prev + 1));
                        }}
                        disabled={frameIndex === activeFrames.length - 1}
                        className="h-7 w-7 sm:h-8 sm:w-8 rounded-full shrink-0 text-secondary hover:text-primary hidden sm:flex cursor-pointer"
                        aria-label="Step forward one frame"
                      >
                        <RiSkipForwardLine className="w-3.5 h-3.5" />
                      </Button>
                    </div>

                    {/* Compact One-Line Timeline Range & Precipitation Legend */}
                    <div className="flex items-center justify-between gap-2 pt-1 border-t border-subtle/40 text-[10px] text-muted">
                      <span className="font-mono font-medium shrink-0">
                        -2h<span className="hidden sm:inline text-muted/70 ml-1">Past</span>
                      </span>

                      {/* Unified Color Legend in One Line */}
                      <div className="flex items-center gap-2.5 sm:gap-3 flex-nowrap overflow-hidden">
                        {/* Rain */}
                        <div
                          className="flex items-center gap-1.5 shrink-0"
                          title="Rain intensity: Light to Heavy"
                        >
                          <span className="font-semibold text-emerald-400 text-[10px]">Rain</span>
                          <div
                            className="h-1.5 w-9 sm:w-16 rounded-full overflow-hidden shadow-xs"
                            style={{
                              background:
                                'linear-gradient(to right, #15803d 0%, #22c55e 25%, #eab308 50%, #f97316 75%, #ef4444 100%)',
                            }}
                          />
                        </div>

                        {/* Mix */}
                        <div
                          className="flex items-center gap-1.5 shrink-0"
                          title="Mix intensity: Light to Heavy"
                        >
                          <span className="font-semibold text-pink-400 text-[10px]">Mix</span>
                          <div
                            className="h-1.5 w-7 sm:w-11 rounded-full overflow-hidden shadow-xs"
                            style={{
                              background: 'linear-gradient(to right, #f472b6 0%, #be185d 100%)',
                            }}
                          />
                        </div>

                        {/* Snow */}
                        <div
                          className="flex items-center gap-1.5 shrink-0"
                          title="Snow intensity: Light to Heavy"
                        >
                          <span className="font-semibold text-sky-400 text-[10px]">Snow</span>
                          <div
                            className="h-1.5 w-7 sm:w-11 rounded-full overflow-hidden shadow-xs"
                            style={{
                              background: 'linear-gradient(to right, #38bdf8 0%, #1d4ed8 100%)',
                            }}
                          />
                        </div>
                      </div>

                      <span className="font-mono font-medium shrink-0 text-right">Now</span>
                    </div>
                  </div>
                </div>
              </>
            )}
            {/* z-[5] keeps the dialog's close button (z-10) above the opaque
                cover; the z-[1000] badges and playback controls clear both. */}
            <LoadingOverlay
              show={isLoading || (!isError && activeFrames.length > 0 && !mapReady)}
              label="Loading live radar…"
              className="z-[5]"
            />
          </div>
        </DialogContent>
      )}
    </Dialog>
  );
}
