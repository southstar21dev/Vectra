import { useEffect, useRef, useCallback, useState } from 'react';
import * as maplibregl from '@/lib/maplibre';
import { cn } from '@/lib/utils';
import {
  applyOpenFreeMapParkColors,
  fetchOpenFreeMapStyle,
  mapTransformRequest,
  suppressMissingStyleImages,
} from '@/lib/map-tiles';
import { getRadarTileUrl, type RadarFrame } from './radar-api';

export const RAINVIEWER_ATTRIBUTION = '&copy; <a href="https://www.rainviewer.com/" target="_blank" rel="noreferrer">RainViewer</a>';


/** HD tile dimension — RainViewer serves 512px tiles for 2× quality */
const HD_TILE_SIZE = 512;

export interface WeatherRadarMapProps {
  center: { lat: number; lon: number };
  activeFrame: RadarFrame | null;
  host: string;
  colorScheme?: number;
  opacity?: number;
  className?: string;
  /** Fired once when the map is instantiated and a frame has painted. */
  onReady?: () => void;
}


/**
 * Register (or re-register) `addProtocol` so MapLibre can load
 * `doppler://` tile URLs, fetch the real RainViewer PNG, colour-remap
 * it through an offscreen canvas, and hand the result back as a Blob.
 */
function installDopplerProtocol() {
  // Unregister first in case of HMR re-mount
  try {
    maplibregl.removeProtocol('doppler');
  } catch {
    /* first mount — nothing to remove */
  }

  maplibregl.addProtocol('doppler', (params, abortController) => {
    // Strip the `doppler://` prefix to recover the real HTTPS URL
    const realUrl = params.url.replace('doppler://', 'https://');

    return fetch(realUrl, { signal: abortController.signal })
      .then((res) => {
        if (!res.ok) throw new Error(`Tile HTTP ${res.status}`);
        return res.blob();
      })
      .then((blob) => createImageBitmap(blob))
      .then(
        (bitmap) =>
          new Promise<{ data: ArrayBuffer }>((resolve) => {
            // Draw bitmap to canvas, remap, then export as PNG blob
            const canvas = document.createElement('canvas');
            canvas.width = HD_TILE_SIZE;
            canvas.height = HD_TILE_SIZE;
            const ctx = canvas.getContext('2d', { willReadFrequently: true })!;
            ctx.drawImage(bitmap, 0, 0, HD_TILE_SIZE, HD_TILE_SIZE);

            try {
              const imgData = ctx.getImageData(0, 0, HD_TILE_SIZE, HD_TILE_SIZE);
              const data = imgData.data;

              for (let i = 0; i < data.length; i += 4) {
                const a = data[i + 3];
                if (a === 0) continue;
                const r = data[i];
                const g = data[i + 1];
                const b = data[i + 2];

                const isYellow = r > 150 && g > 135 && b < 110;
                const isRedOrange = r > 150 && g < 130 && b < 80;
                const isMagenta = r > 110 && b > 110 && g < 80;
                const isWhite = r > 240 && g > 240 && b > 240;

                if (!isYellow && !isRedOrange && !isMagenta && !isWhite) {
                  const intensity = Math.max(r, g, b);
                  const factor = intensity / 255;
                  data[i] = (20 + 45 * factor) | 0;
                  data[i + 1] = (160 + 85 * factor) | 0;
                  data[i + 2] = (15 + 35 * factor) | 0;
                }
              }
              ctx.putImageData(imgData, 0, 0);
            } catch {
              /* security fallback */
            }

            canvas.toBlob((pngBlob) => {
              if (!pngBlob) {
                resolve({ data: new ArrayBuffer(0) });
                return;
              }
              pngBlob.arrayBuffer().then((buf) => resolve({ data: buf }));
            }, 'image/png');
          })
      );
  });
}

export function WeatherRadarMap({
  center,
  activeFrame,
  host,
  colorScheme = 1,
  opacity = 0.85,
  className,
  onReady,
}: WeatherRadarMapProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const markerRef = useRef<maplibregl.Marker | null>(null);
  // The map is created asynchronously (after the basemap style resolves), so
  // the radar-source effect below can run before `mapRef.current` exists. This
  // flips once the map is instantiated to re-trigger that effect.
  const [isMapReady, setIsMapReady] = useState(false);
  // Set once on the map's first 'load'. `map.isStyleLoaded()` is not a usable
  // gate: it reads false again whenever tiles are still loading, and waiting on
  // a 'load' event at that point hangs forever — it only fires once.
  const [isStyleReady, setIsStyleReady] = useState(false);

  // One-shot: report once the map exists and a frame has painted.
  useEffect(() => {
    if (!isMapReady || !onReady) return;
    const frame = requestAnimationFrame(() => onReady());
    return () => cancelAnimationFrame(frame);
  }, [isMapReady, onReady]);

  // Build the doppler:// tile URL from the current frame
  const buildRadarTileUrl = useCallback(
    (frame: RadarFrame, hostUrl: string) => {
      const httpsUrl = getRadarTileUrl(hostUrl, frame.path, colorScheme, '1_1', HD_TILE_SIZE);
      // Replace {z}/{x}/{y} with MapLibre's expected template vars
      return 'doppler://' + httpsUrl.replace('https://', '');
    },
    [colorScheme]
  );

  // Initialize MapLibre map
  useEffect(() => {
    const el = containerRef.current;
    if (!el || mapRef.current) return;

    installDopplerProtocol();

    const isLight = document.documentElement.dataset.theme === 'light';

    let cancelled = false;
    let cleanup: (() => void) | undefined;

    void fetchOpenFreeMapStyle(isLight).then((mapStyle) => {
      if (cancelled) return;
      const map = new maplibregl.Map({
        container: el,
        style: mapStyle,
        center: [center.lon, center.lat],
        zoom: 6,
        minZoom: 3,
        maxZoom: 9,
        attributionControl: false,
        transformRequest: mapTransformRequest,
      });
      const removeMissingImageHandler = suppressMissingStyleImages(map);

      const applyStyleOverrides = () => applyOpenFreeMapParkColors(map, isLight);
      map.on('style.load', applyStyleOverrides);
      const markStyleReady = () => setIsStyleReady(true);
      map.once('load', markStyleReady);

      const markerEl = document.createElement('div');
      markerEl.innerHTML =
        '<div class="relative flex h-6 w-6 items-center justify-center">' +
        '<span class="absolute inline-flex h-full w-full animate-ping rounded-full bg-amber-400/40"></span>' +
        '<span class="relative flex h-4 w-4 items-center justify-center rounded-full bg-amber-500 shadow-md ring-2 ring-white/90 dark:ring-black/80">' +
        '<span class="h-1.5 w-1.5 rounded-full bg-white"></span>' +
        '</span>' +
        '</div>';
      const marker = new maplibregl.Marker({ element: markerEl })
        .setLngLat([center.lon, center.lat])
        .addTo(map);

      markerRef.current = marker;
      mapRef.current = map;
      setIsMapReady(true);
      const measure = () => map.resize();
      const raf = window.requestAnimationFrame(measure);
      const t1 = setTimeout(measure, 100);
      const t2 = setTimeout(measure, 350);
      let resizeObserver: ResizeObserver | undefined;
      if (typeof ResizeObserver !== 'undefined') {
        resizeObserver = new ResizeObserver(() => map.resize());
        resizeObserver.observe(el);
      }
      cleanup = () => {
        window.cancelAnimationFrame(raf);
        clearTimeout(t1);
        clearTimeout(t2);
        resizeObserver?.disconnect();
        marker.remove();
        map.off('style.load', applyStyleOverrides);
        map.off('load', markStyleReady);
        removeMissingImageHandler();
        map.remove();
        mapRef.current = null;
        markerRef.current = null;
        try {
          maplibregl.removeProtocol('doppler');
        } catch {
          /* already removed */
        }
      };
    }).catch((error) => {
      if (!cancelled) console.error('[WeatherRadar] Failed to load basemap style:', error);
    });

    return () => {
      cancelled = true;
      cleanup?.();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Update center & marker when location changes
  useEffect(() => {
    const map = mapRef.current;
    const marker = markerRef.current;
    if (!map || !marker) return;

    marker.setLngLat([center.lon, center.lat]);
  }, [center.lat, center.lon]);

  // Update radar tile URL on frame change
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !isStyleReady || !activeFrame || !host) return;
    addRadarSource(map, buildRadarTileUrl(activeFrame, host), opacity);
  }, [activeFrame, host, colorScheme, opacity, buildRadarTileUrl, isMapReady, isStyleReady]);

  return (
    <div className={cn('weather-radar-map relative h-full w-full overflow-hidden', className)}>
      <div
        ref={containerRef}
        className="h-full w-full bg-surface-secondary"
        role="img"
        aria-label="Interactive Weather Radar"
      />
    </div>
  );
}

/** Helper to add or replace the radar raster source + layer on the map safely */
function addRadarSource(map: maplibregl.Map, tileUrl: string, opacity: number) {
  try {
    if (map.getLayer('radar-layer')) {
      map.removeLayer('radar-layer');
    }
    if (map.getSource('radar')) {
      map.removeSource('radar');
    }

    map.addSource('radar', {
      type: 'raster',
      tiles: [tileUrl],
      tileSize: HD_TILE_SIZE,
      minzoom: 1,
      maxzoom: 7,
    });

    map.addLayer({
      id: 'radar-layer',
      type: 'raster',
      source: 'radar',
      paint: {
        'raster-opacity': opacity,
        'raster-fade-duration': 150,
      },
    });
  } catch (err) {
    console.warn('[WeatherRadar] Error updating radar source/layer:', err);
  }
}
