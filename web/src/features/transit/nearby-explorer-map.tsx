import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import * as maplibregl from '@/lib/maplibre';
import type { LayerSpecification } from 'maplibre-gl';
import { TransitMap } from './map/TransitMap';
import { applyOpenFreeMapParkColors } from '@/lib/map-tiles';
import { useMapLoaded } from './map/use-map-loaded';
import { useMapTheme } from './map/use-map-theme';
import { useGeoLayer, type GeoLayerSpec } from './map/use-geo-layers';
import { useProgrammaticGuard } from './map/use-camera';
import { useVehicleAnimations } from './map/use-vehicle-anim';
import { accuracyCircleFeature } from './map/geo';
import { explorerRouteColors, liveLineColors, withHash } from './map/colors';
import type { LatLon } from './route-geometry';
import {
  clampBottomInset,
  viewportFromUncoveredRect,
  type ExplorerMapStop,
} from './nearby-stops';
import type {
  ExplorerRouteStop,
  ExplorerSelectedStop,
  ExplorerViewport,
} from './use-nearby-explorer';

export interface ExplorerVehicleMarker {
  id: string;
  lat: number;
  lon: number;
  bearing: number;
}

export interface ExplorerCameraRequest {
  lat: number;
  lon: number;
  zoom?: number;
  nonce: number;
}

export interface NearbyExplorerMapProps {
  initialCenter: LatLon;
  stops: ExplorerMapStop[];
  focusedStopId: string | null;
  selectedStop: ExplorerSelectedStop | null;
  polylines: LatLon[][];
  routeStops: ExplorerRouteStop[];
  routeColor: string;
  vehicles: ExplorerVehicleMarker[];
  cameraRequest: ExplorerCameraRequest | null;
  user: { lat: number; lon: number; accuracy: number } | null;
  /** Viewport height covered by the departures sheet, from the bottom. */
  bottomInsetPx: number;
  onReady?: () => void;
  onViewportChange: (viewport: ExplorerViewport) => void;
  onSelectStop: (stopId: string, stationId?: string) => void;
}

/** Extra invisible tap radius around a stop dot. The visible dots are 4–5px
 *  across; a fingertip needs roughly a 40px target, so an opacity-0 circle
 *  layer owns the touch interaction instead (MapLibre hit-tests fully
 *  transparent features normally). */
const STOP_HIT_LAYER_ID = 'explorer-stops-hit';
const STOP_HIT_RADIUS_PX = 14;
/** Keep edge stops discoverable when their label is visible at the map edge. */
const STOP_QUERY_MARGIN_PX = 160;

/** A dot represents the selected departure's exact physical stop. */
function isSelectedDot(stop: ExplorerMapStop, selectedStopId: string | null): boolean {
  return selectedStopId !== null && stop.id === selectedStopId;
}

/**
 * Stop dots for the viewport. A latched route selection dims them rather than
 * hiding them: the old code emptied the layer entirely, so the only way to pick
 * another stop was to first tap an empty patch of map to clear the selection,
 * which reads as "the map stopped working" on a phone.
 */
function stopsGeoJSON(
  stops: ExplorerMapStop[],
  selectedStopId: string | null,
  focusedStopId: string | null,
  palette: { stopFill: string; stopStroke: string }
): GeoJSON.FeatureCollection<GeoJSON.Point> {
  const dimmed = selectedStopId !== null;
  return {
    type: 'FeatureCollection',
    features: stops
      .filter((s) => !isSelectedDot(s, selectedStopId))
      .map((s) => {
        // Keep the visual focus on the exact dot pressed. A platform can
        // share its display name with other platforms or catalog stops.
        const focused = s.id === focusedStopId;
        // Match RouteMap's tracked/regular dot scale: platform stops are
        // secondary dots, while a focused stop gets the tracked blue treatment.
        const baseRadius = focused ? 5.5 : s.isPlatform ? 3 : 4;
        return {
          type: 'Feature' as const,
          properties: {
            id: s.id,
            stationId: s.stationId,
            name: s.name,
            radius: dimmed ? Math.max(2.5, baseRadius - 1.5) : baseRadius,
            opacity: dimmed ? 0.45 : 1,
            fillColor: focused
              ? '#2563eb'
              : s.isPlatform
                ? '#3f3f46'
                : palette.stopFill,
            strokeColor: focused ? '#60a5fa' : palette.stopStroke,
            strokeWidth: focused ? 2 : 1,
          },
          geometry: { type: 'Point' as const, coordinates: [s.lon, s.lat] },
        };
      }),
  };
}

function routeStopsGeoJSON(
  routeStops: ExplorerRouteStop[],
  selectedStopId: string | null,
  selectedPlatformStopId: string | null,
  color: string
): GeoJSON.FeatureCollection<GeoJSON.Point> {
  return {
    type: 'FeatureCollection',
    features: routeStops
      // The pulse marker already marks the selected departure's own stop; drop
      // its route-stop dot whether the route data keys it by station or by
      // physical platform.
      .filter((s) => s.id !== selectedStopId && s.id !== selectedPlatformStopId)
      .map((s) => ({
        type: 'Feature' as const,
        properties: { id: s.id, name: s.name, color },
        geometry: { type: 'Point' as const, coordinates: [s.lon, s.lat] },
      })),
  };
}

function routeGeoJSON(
  polylines: LatLon[][],
  color: string
): GeoJSON.FeatureCollection<GeoJSON.LineString> {
  return {
    type: 'FeatureCollection',
    features: polylines
      .filter((line) => line.length >= 2)
      .map((line) => ({
        type: 'Feature' as const,
        properties: { color },
        geometry: {
          type: 'LineString' as const,
          coordinates: line.map((p) => [p.lon, p.lat]),
        },
      })),
  };
}

export function NearbyExplorerMap({
  initialCenter,
  stops,
  focusedStopId,
  selectedStop,
  polylines,
  routeStops,
  routeColor,
  vehicles,
  cameraRequest,
  user,
  bottomInsetPx,
  onReady,
  onViewportChange,
  onSelectStop,
}: NearbyExplorerMapProps) {
  const [map, setMap] = useState<maplibregl.Map | null>(null);
  const loaded = useMapLoaded(map);

  // One-shot: report once the style is loaded and a frame has painted.
  // Deliberately NOT map.loaded(): it can stay false indefinitely while tiles
  // are pending or animation is running, which left the dialog's loading
  // overlay stuck.
  useEffect(() => {
    if (!loaded || !onReady) return;
    const frame = requestAnimationFrame(() => onReady());
    return () => cancelAnimationFrame(frame);
  }, [loaded, onReady]);

  const { isLight } = useMapTheme();
  const colors = useMemo(() => explorerRouteColors(isLight), [isLight]);
  const lineColors = useMemo(() => liveLineColors(isLight), [isLight]);
  const { programmaticMove, userMovedRef } = useProgrammaticGuard(map);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const userMarkerRef = useRef<maplibregl.Marker | null>(null);
  const selectedMarkerRef = useRef<maplibregl.Marker | null>(null);
  const bottomInsetRef = useRef(bottomInsetPx);
  const emitViewportRef = useRef<() => void>(() => {});
  const onViewportChangeRef = useRef(onViewportChange);
  const onSelectStopRef = useRef(onSelectStop);

  const selectedStopId = selectedStop?.id ?? null;
  const selectedPlatformStopId = selectedStop?.platformStopId ?? null;

  useEffect(() => {
    onViewportChangeRef.current = onViewportChange;
    onSelectStopRef.current = onSelectStop;
  }, [onViewportChange, onSelectStop]);

  // Keep the latest inset visible to the map's stable emit callbacks.
  useEffect(() => {
    bottomInsetRef.current = bottomInsetPx;
  }, [bottomInsetPx]);

  const handleReady = useCallback((m: maplibregl.Map) => {
    mapRef.current = m;
    setMap(m);
  }, []);

  const routeSpec = useMemo<GeoLayerSpec>(() => ({
    sourceId: 'explorer-route',
    data: routeGeoJSON(polylines, withHash(routeColor)),
    layers: [
      { id: 'explorer-route-casing', type: 'line', source: 'explorer-route',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': lineColors.casing, 'line-width': 7, 'line-opacity': 0.9 } },
      { id: 'explorer-route-line', type: 'line', source: 'explorer-route',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': ['get', 'color'], 'line-width': 4, 'line-opacity': 1 } },
    ] as LayerSpecification[],
  }), [polylines, routeColor, lineColors]);

  const animatedVehicles = useMemo(
    () => vehicles.map((v) => ({
      id: v.id,
      polyline: polylines[0] ?? [],
      location: { lat: v.lat, lon: v.lon },
      variant: 'primary' as const,
      bearing: v.bearing,
    })),
    [vehicles, polylines]
  );

  const accuracySpec = useMemo<GeoLayerSpec>(() => ({
    sourceId: 'explorer-accuracy',
    data: {
      type: 'FeatureCollection',
      features: user && user.accuracy > 0
        ? [accuracyCircleFeature(user, user.accuracy)].filter((f): f is NonNullable<typeof f> => f !== null)
        : [],
    },
    layers: [
      { id: 'explorer-accuracy-fill', type: 'fill', source: 'explorer-accuracy',
        paint: { 'fill-color': '#3b82f6', 'fill-opacity': 0.12 } },
      { id: 'explorer-accuracy-stroke', type: 'line', source: 'explorer-accuracy',
        paint: { 'line-color': '#3b82f6', 'line-width': 1.5, 'line-opacity': 0.35 } },
    ] as LayerSpecification[],
  }), [user]);

  const stopsSpec = useMemo<GeoLayerSpec>(() => ({
    sourceId: 'explorer-stops',
    data: stopsGeoJSON(stops, selectedStopId, focusedStopId, {
      stopFill: colors.stopFill,
      stopStroke: colors.stopStroke,
    }),
    layers: [
      { id: 'explorer-stops-layer', type: 'circle', source: 'explorer-stops',
        paint: {
          'circle-radius': ['get', 'radius'],
          'circle-color': ['get', 'fillColor'],
          'circle-opacity': ['get', 'opacity'],
          'circle-stroke-color': ['get', 'strokeColor'],
          'circle-stroke-width': ['get', 'strokeWidth'],
        } },
      // Invisible tap target sitting on top of the dots. Paint never affects
      // hit testing, so a fully transparent circle still answers queries while
      // keeping the dots visually small.
      { id: STOP_HIT_LAYER_ID, type: 'circle', source: 'explorer-stops',
        paint: {
          'circle-radius': ['+', ['get', 'radius'], STOP_HIT_RADIUS_PX],
          'circle-color': '#000000',
          'circle-opacity': 0,
        } },
    ] as LayerSpecification[],
  }), [stops, selectedStopId, focusedStopId, colors]);

  const routeStopsSpec = useMemo<GeoLayerSpec>(() => ({
    sourceId: 'explorer-route-stops',
    data: routeStopsGeoJSON(routeStops, selectedStopId, selectedPlatformStopId, withHash(routeColor)),
    layers: [
      { id: 'explorer-route-stops-layer', type: 'circle', source: 'explorer-route-stops',
        paint: {
          'circle-radius': 4.5,
          'circle-color': '#ffffff',
          'circle-stroke-color': ['get', 'color'],
          'circle-stroke-width': 3,
        } },
    ] as LayerSpecification[],
  }), [routeStops, routeColor, selectedStopId, selectedPlatformStopId]);

  useEffect(() => {
    if (map && loaded) applyOpenFreeMapParkColors(map, isLight);
  }, [map, loaded, isLight]);

  useGeoLayer(map, loaded, routeSpec);
  useGeoLayer(map, loaded, accuracySpec);
  useVehicleAnimations(map, loaded, animatedVehicles);
  useGeoLayer(map, loaded, stopsSpec);
  useGeoLayer(map, loaded, routeStopsSpec);

  // Stop interaction is handled from one map-level listener. Relying only on
  // a transparent layer's delegated click event is flaky across MapLibre/WebGL
  // versions (and can also let the second map click handler clear a selection
  // immediately), so query the generous hit target and the visible dot together.
  useEffect(() => {
    if (!map || !loaded) return;
    const stopLayers = [STOP_HIT_LAYER_ID, 'explorer-stops-layer'];
    const findStopAt = (point: maplibregl.PointLike) =>
      map.queryRenderedFeatures(point, { layers: stopLayers })[0]?.properties;
    const onMapClick = (e: maplibregl.MapMouseEvent) => {
      const props = findStopAt(e.point);
      const id = props?.id;
      const stationId = props?.stationId;
      // Empty taps, including the first/second click of a double-tap zoom,
      // intentionally do nothing. Route tracking is dismissed only by an
      // explicit route selection or a stop-dot selection.
      if (typeof id === 'string' && id) {
        onSelectStopRef.current(id, typeof stationId === 'string' ? stationId : undefined);
      }
    };
    const onMouseMove = (e: maplibregl.MapMouseEvent) => {
      map.getCanvas().style.cursor = findStopAt(e.point) ? 'pointer' : '';
    };
    map.on('click', onMapClick);
    map.on('mousemove', onMouseMove);
    return () => {
      map.off('click', onMapClick);
      map.off('mousemove', onMouseMove);
      map.getCanvas().style.cursor = '';
    };
  }, [map, loaded]);

  // Keep the stop query aligned with the whole map canvas. The sheet overlays
  // the lower edge, but that map area is still visible while the sheet moves
  // and users expect stops such as Teleki László tér to remain discoverable.
  // The center stays anchored to the uncovered area so the feed remains useful.
  useEffect(() => {
    if (!map || !loaded) return;
    const emitViewport = () => {
      const canvas = map.getCanvas();
      const width = canvas.clientWidth;
      const height = canvas.clientHeight;
      const bottomInset = clampBottomInset(bottomInsetRef.current, height);
      const uncoveredHeight = height - bottomInset;
      if (width <= 0 || uncoveredHeight <= 0) return;
      onViewportChangeRef.current(
        viewportFromUncoveredRect(
          map.unproject([0, -STOP_QUERY_MARGIN_PX]),
          map.unproject([width, height + STOP_QUERY_MARGIN_PX]),
          map.unproject([width / 2, uncoveredHeight / 2]),
          map.getZoom()
        )
      );
    };
    emitViewportRef.current = emitViewport;
    let timer: number | undefined;
    const onMoveEnd = () => {
      window.clearTimeout(timer);
      timer = window.setTimeout(emitViewport, 400);
    };
    map.on('moveend', onMoveEnd);
    emitViewport();
    return () => {
      window.clearTimeout(timer);
      map.off('moveend', onMoveEnd);
      emitViewportRef.current = () => {};
    };
  }, [map, loaded]);

  // The sheet reports its coverage per drag frame; re-emit the uncovered
  // viewport only once the drag/snap settles (and once on mount).
  useEffect(() => {
    if (!mapRef.current) return;
    const timer = window.setTimeout(() => emitViewportRef.current(), 150);
    return () => window.clearTimeout(timer);
  }, [bottomInsetPx]);

  // React to a location fix that arrives after mount, until the user moves the
  // camera. Keyed on the coordinates: the parent recreates the prop object on
  // every render, so identity would re-issue the ease after unrelated updates
  // (for example right after a camera-request flyTo).
  useEffect(() => {
    if (!map || !loaded || userMovedRef.current) return;
    programmaticMove(() =>
      map.easeTo({ center: [initialCenter.lon, initialCenter.lat], duration: 300 })
    );
  }, [map, loaded, initialCenter.lon, initialCenter.lat, programmaticMove, userMovedRef]);

  // Camera requests (recenter / fly to a selected stop).
  useEffect(() => {
    if (!map || !loaded || !cameraRequest) return;
    userMovedRef.current = true;
    programmaticMove(() =>
      map.flyTo({
        center: [cameraRequest.lon, cameraRequest.lat],
        zoom: cameraRequest.zoom ?? Math.max(map.getZoom(), 15.5),
        speed: 1.4,
        curve: 1.3,
        essential: true,
      })
    );
  }, [map, loaded, cameraRequest, programmaticMove, userMovedRef]);

  // User dot + accuracy circle. Removes the marker when the fix is lost.
  useEffect(() => {
    if (!map || !loaded) return;
    if (!user) {
      userMarkerRef.current?.remove();
      userMarkerRef.current = null;
      return;
    }
    if (userMarkerRef.current) {
      userMarkerRef.current.setLngLat([user.lon, user.lat]);
      return;
    }
    const el = document.createElement('div');
    el.innerHTML =
      '<div class="relative flex h-5 w-5 items-center justify-center">' +
      '<span class="absolute inline-flex h-full w-full animate-ping rounded-full bg-blue-400/70"></span>' +
      '<span class="relative h-3.5 w-3.5 rounded-full bg-blue-500 ring-2 ring-white shadow-lg shadow-blue-500/50"></span>' +
      '</div>';
    userMarkerRef.current = new maplibregl.Marker({ element: el })
      .setLngLat([user.lon, user.lat])
      .addTo(map);
  }, [map, loaded, user]);

  // Selected stop pulse + label. Marker-only: add it whenever the selection
  // changes, regardless of style/source update state. The latched coordinates
  // keep it rendered even when its stop is outside the viewport.
  useEffect(() => {
    if (!map || !loaded) return;

    selectedMarkerRef.current?.remove();
    selectedMarkerRef.current = null;
    if (!selectedStop) return;

    // RouteMap uses the same blue selected-stop beacon in the tracker dialog.
    const color = '#3b82f6';

    const root = document.createElement('div');
    root.className = 'flex flex-col items-center gap-1';
    root.style.pointerEvents = 'none';

    const label = document.createElement('span');
    label.className =
      'route-stop-label whitespace-nowrap rounded-full border border-subtle bg-surface-overlay/95 px-2 py-0.5 text-[10px] font-mono font-bold shadow-md';
    label.style.borderColor = color;
    label.textContent = selectedStop.name;

    const dotWrap = document.createElement('span');
    dotWrap.className = 'relative flex h-6 w-6 items-center justify-center';
    const ping = document.createElement('span');
    ping.className = 'absolute inline-flex h-full w-full animate-ping rounded-full';
    ping.style.background = `${color}b3`;
    const dot = document.createElement('span');
    dot.className = 'relative h-4 w-4 rounded-full';
    dot.style.background = color;
    dot.style.boxShadow = colors.isLight
      ? `0 0 0 2px #ffffff, 0 0 0 3.5px ${color}, 0 2px 8px rgba(0,0,0,0.4)`
      : `0 0 0 2.5px #ffffff, 0 0 0 4.5px ${color}, 0 2px 10px rgba(0,0,0,0.55)`;
    dotWrap.appendChild(ping);
    dotWrap.appendChild(dot);

    root.appendChild(label);
    root.appendChild(dotWrap);

    selectedMarkerRef.current = new maplibregl.Marker({
      element: root,
      anchor: 'bottom',
      subpixelPositioning: true,
    })
      .setLngLat([selectedStop.lon, selectedStop.lat])
      .addTo(map);
  }, [map, loaded, selectedStop, routeColor, colors]);


  // TransitMap owns `map.remove()`; the HTML markers are ours to drop.
  useEffect(() => {
    return () => {
      userMarkerRef.current?.remove();
      userMarkerRef.current = null;
      selectedMarkerRef.current?.remove();
      selectedMarkerRef.current = null;
    };
  }, []);

  return (
    <TransitMap
      initialCenter={initialCenter}
      initialZoom={15.5}
      minZoom={10}
      maxZoom={19}
      dragRotate={false}
      attributionControl={{ compact: false }}
      ariaLabel="Nearby transit map"
      ariaRole="application"
      className="absolute inset-0 z-0 h-full w-full"
      onReady={handleReady}
    />
  );
}
