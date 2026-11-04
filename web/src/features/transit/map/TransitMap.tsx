import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import * as maplibregl from '@/lib/maplibre';
import {
  applyOpenFreeMapParkColors,
  fetchOpenFreeMapStyle,
  mapTransformRequest,
  OPENFREEMAP_ATTRIBUTION,
  suppressMissingStyleImages,
} from '@/lib/map-tiles';
import { cn } from '@/lib/utils';
import { useMapTheme } from './use-map-theme';
import { carryOverCustomLayers } from './theme-style';
import type { LatLon } from './types';

export interface TransitMapProps {
  initialCenter: LatLon;
  initialZoom: number;
  minZoom?: number;
  maxZoom?: number;
  /** When false, rotation/pitch are disabled (explorer requirement). */
  dragRotate?: boolean;
  attributionControl?: boolean | maplibregl.AttributionControlOptions;
  ariaLabel: string;
  ariaRole?: 'img' | 'application';
  className?: string;
  style?: CSSProperties;
  onReady?: (map: maplibregl.Map) => void;
  /** Called when the basemap style cannot be fetched (offline, provider down). */
  onLoadError?: () => void;
  children?: ReactNode;
  /** Defaults to {@link mapTransformRequest}, which serves the style's
   *  OpenSansRegular glyphs from the bundled PBFs (OpenFreeMap 404s them). */
  transformRequest?: maplibregl.RequestTransformFunction;
}

/** Shared OpenFreeMap vector basemap with theme-aware styles and resize handling. */
export function TransitMap({
  initialCenter,
  initialZoom,
  minZoom = 5,
  maxZoom = 19,
  dragRotate = true,
  attributionControl = true,
  ariaLabel,
  ariaRole = 'img',
  className,
  style,
  onReady,
  onLoadError,
  children,
  transformRequest = mapTransformRequest,
}: TransitMapProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const { isLight } = useMapTheme();
  // Live theme for listeners registered once at map creation.
  const isLightRef = useRef(isLight);
  isLightRef.current = isLight;
  // Theme the current basemap style was built for, and the sources it declared
  // (anything else on the map was added by the app and survives a theme swap).
  const styleThemeRef = useRef<boolean | null>(null);
  const basemapSourcesRef = useRef<Set<string>>(new Set());
  const [mapCreated, setMapCreated] = useState(false);
  const onReadyRef = useRef(onReady);
  onReadyRef.current = onReady;
  const onLoadErrorRef = useRef(onLoadError);
  onLoadErrorRef.current = onLoadError;
  const [styleFailed, setStyleFailed] = useState(false);

  useEffect(() => {
    const el = containerRef.current;
    if (!el || mapRef.current) return;

    let cancelled = false;
    let cleanup: (() => void) | undefined;

    const styleIsLight = isLight;
    const load = () => fetchOpenFreeMapStyle(styleIsLight).then((mapStyle) => {
      if (cancelled || mapRef.current) return;
      styleThemeRef.current = styleIsLight;
      basemapSourcesRef.current = new Set(Object.keys(mapStyle.sources ?? {}));
      window.removeEventListener('online', retry);
      setStyleFailed(false);
      const map = new maplibregl.Map({
        container: el,
        style: mapStyle,
        center: [initialCenter.lon, initialCenter.lat],
        zoom: initialZoom,
        minZoom,
        maxZoom,
        dragRotate,
        pitchWithRotate: dragRotate,
        touchPitch: dragRotate,
        attributionControl: false,
        transformRequest,
      });
      // Register before the style is fully parsed so the handler catches
      // missing sprite images during the initial load.
      const removeMissingImageHandler = suppressMissingStyleImages(map);
      if (!dragRotate) {
        map.touchZoomRotate.disableRotation();
        map.keyboard.disableRotation();
      }
      const measure = () => map.resize();
      const raf = window.requestAnimationFrame(measure);
      const resizeTimer100 = window.setTimeout(measure, 100);
      const resizeTimer350 = window.setTimeout(measure, 350);
      const resizeTimer700 = window.setTimeout(measure, 700);
      let observer: ResizeObserver | undefined;
      if (typeof ResizeObserver !== 'undefined') {
        observer = new ResizeObserver(measure);
        observer.observe(el);
      }
      const applyStyleOverrides = () => applyOpenFreeMapParkColors(map, isLightRef.current);
      map.on('load', applyStyleOverrides);
      map.on('style.load', applyStyleOverrides);
      if (map.isStyleLoaded()) applyStyleOverrides();

      mapRef.current = map;
      setMapCreated(true);
      onReadyRef.current?.(map);
      cleanup = () => {
        window.cancelAnimationFrame(raf);
        window.clearTimeout(resizeTimer100);
        window.clearTimeout(resizeTimer350);
        window.clearTimeout(resizeTimer700);
        observer?.disconnect();
        map.off('load', applyStyleOverrides);
        map.off('style.load', applyStyleOverrides);
        removeMissingImageHandler();
        map.remove();
        mapRef.current = null;
        setMapCreated(false);
      };
    }).catch((error) => {
      if (cancelled) return;
      console.error('[TransitMap] Failed to load basemap style:', error);
      setStyleFailed(true);
      onLoadErrorRef.current?.();
    });
    // Offline or a provider hiccup: try again once the connection returns.
    function retry() {
      void load();
    }
    window.addEventListener('online', retry);
    void load();

    return () => {
      cancelled = true;
      window.removeEventListener('online', retry);
      cleanup?.();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Theme flips swap the basemap in place: the app's own sources and layers
  // are carried over, so routes and markers stay put.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapCreated || styleThemeRef.current === null || styleThemeRef.current === isLight) return;
    let cancelled = false;
    void fetchOpenFreeMapStyle(isLight)
      .then((nextStyle) => {
        if (cancelled || mapRef.current !== map) return;
        const basemapSources = basemapSourcesRef.current;
        styleThemeRef.current = isLight;
        basemapSourcesRef.current = new Set(Object.keys(nextStyle.sources ?? {}));
        map.once('styledata', () => applyOpenFreeMapParkColors(map, isLightRef.current));
        map.setStyle(nextStyle, {
          diff: true,
          transformStyle: (previous, next) => carryOverCustomLayers(previous, next, basemapSources),
        });
      })
      .catch((error) => {
        if (!cancelled) console.warn('[TransitMap] Could not switch basemap theme:', error);
      });
    return () => {
      cancelled = true;
    };
  }, [isLight, mapCreated]);


  return (
    <div className={cn('relative isolate overflow-hidden', className)} style={style}>
      <div
        ref={containerRef}
        className="h-full w-full"
        role={ariaRole}
        aria-label={ariaLabel}
      />
      {attributionControl !== false && (
        <div
          className="map-attribution pointer-events-auto absolute bottom-1 left-2 z-10 font-mono text-[9px] leading-tight"
          dangerouslySetInnerHTML={{ __html: OPENFREEMAP_ATTRIBUTION }}
        />
      )}
      {styleFailed && (
        <div className="absolute inset-0 z-10 flex items-center justify-center p-4 text-center text-sm text-muted">
          Map unavailable. It will load when you're back online.
        </div>
      )}
      {children}
    </div>
  );
}
