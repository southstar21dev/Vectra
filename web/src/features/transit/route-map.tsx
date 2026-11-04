import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { RiFocus3Line, RiFullscreenLine, RiRouteLine } from '@remixicon/react';
import * as maplibregl from '@/lib/maplibre';
import type { LayerSpecification } from 'maplibre-gl';
import { TransitMap } from './map/TransitMap';
import { useMapLoaded } from './map/use-map-loaded';
import { useMapTheme } from './map/use-map-theme';
import { useGeoLayer, type GeoLayerSpec } from './map/use-geo-layers';
import { useProgrammaticGuard } from './map/use-camera';
import { useVehicleAnimations, type AnimatedVehicleInput } from './map/use-vehicle-anim';
import { MapControls, type MapControlButton } from './map/MapControls';
import { boundsOf, lineFeatureCollection, samePoints, stopFeatureCollection } from './map/geo';
import { cssVar, liveLineColors, withHash } from './map/colors';
import { MAP_FONT_STACK } from './map/glyphs';
import type { LatLon, MapStop, RouteMapSecondaryLine, RouteMapStop, RouteMapVehicle } from './map/types';

const CLOSE_ZOOM = 16;

interface RouteMapProps {
  stops: RouteMapStop[];
  polyline: LatLon[];
  vehicles: RouteMapVehicle[];
  routeColor: string;
  height?: number;
  secondaryLines?: RouteMapSecondaryLine[];
  onFullscreen?: () => void;
  fillHeight?: boolean;
  /** Fired once when the map style is loaded and a frame has painted. */
  onReady?: () => void;
  /** Basemap style failed to load; the map shows its own unavailable notice. */
  onLoadError?: () => void;
}

function stopKind(stop: RouteMapStop, isTerminus: boolean): MapStop['kind'] {
  if (stop.isSelected) return 'selected';
  if (stop.isTracked) return 'tracked';
  return isTerminus ? 'terminus' : 'regular';
}

function toLngLatBounds(b: { minLon: number; minLat: number; maxLon: number; maxLat: number }) {
  return new maplibregl.LngLatBounds([b.minLon, b.minLat], [b.maxLon, b.maxLat]);
}

export function RouteMap({
  stops,
  polyline,
  vehicles,
  routeColor,
  height = 240,
  secondaryLines = [],
  onFullscreen,
  fillHeight = false,
  onReady,
  onLoadError,
}: RouteMapProps) {
  const [map, setMap] = useState<maplibregl.Map | null>(null);
  const loaded = useMapLoaded(map);

  // One-shot: report once the style is loaded and a frame has painted.
  // Deliberately NOT map.loaded(): it can stay false indefinitely while tiles
  // are pending or animation is running, which left loading overlays stuck.
  useEffect(() => {
    if (!loaded || !onReady) return;
    const frame = requestAnimationFrame(() => onReady());
    return () => cancelAnimationFrame(frame);
  }, [loaded, onReady]);

  const { isLight } = useMapTheme();
  const colors = useMemo(() => liveLineColors(isLight), [isLight]);
  const [followActive, setFollowActive] = useState(true);
  const [fitNonce, setFitNonce] = useState(0);
  const handleUserMove = useCallback(() => setFollowActive(false), []);
  const { programmaticMove, userMovedRef, resetUserMoved } = useProgrammaticGuard(map, handleUserMove);
  const tripKeyRef = useRef<LatLon[] | null>(null);
  const selectedStopKeyRef = useRef<string | null>(null);
  const fittedRef = useRef(false);

  const labelColor = cssVar('--text-secondary', isLight ? '#3f3f46' : '#d4d4d8');
  const labelHalo = isLight ? 'rgba(255,255,255,0.95)' : 'rgba(0,0,0,0.95)';

  const primaryLine = useMemo<GeoLayerSpec>(
    () => ({
      sourceId: 'live-line',
      data: lineFeatureCollection([polyline]),
      layers: [
        {
          id: 'live-line-casing',
          type: 'line',
          source: 'live-line',
          layout: { 'line-cap': 'round', 'line-join': 'round' },
          paint: { 'line-color': colors.casing, 'line-width': 7, 'line-opacity': 0.9 },
        },
        {
          id: 'live-line-base',
          type: 'line',
          source: 'live-line',
          layout: { 'line-cap': 'round', 'line-join': 'round' },
          paint: { 'line-color': withHash(routeColor), 'line-width': 4, 'line-opacity': 1 },
        },
      ] as LayerSpecification[],
    }),
    [polyline, colors, routeColor]
  );

  const secondaryData = useMemo(
    () =>
      lineFeatureCollection(
        secondaryLines
          .map((ln) => ln.polyline)
          .filter((p) => p.length >= 2 && !samePoints(polyline, p))
      ),
    [secondaryLines, polyline]
  );
  const secondarySpec = useMemo<GeoLayerSpec>(
    () => ({
      sourceId: 'live-secondary-lines',
      data: secondaryData,
      layers: [
        {
          id: 'live-secondary-casing',
          type: 'line',
          source: 'live-secondary-lines',
          layout: { 'line-cap': 'round', 'line-join': 'round' },
          paint: { 'line-color': colors.casingSecondary, 'line-width': 5, 'line-opacity': 0.85 },
        },
        {
          id: 'live-secondary-base',
          type: 'line',
          source: 'live-secondary-lines',
          layout: { 'line-cap': 'round', 'line-join': 'round' },
          paint: { 'line-color': withHash(routeColor), 'line-width': 3, 'line-opacity': 0.7 },
        },
      ] as LayerSpecification[],
    }),
    [secondaryData, colors, routeColor]
  );

  const stopFeatures = useMemo(() => {
    const primary: MapStop[] = stops.map((s, i) => ({
      name: s.name,
      lat: s.lat,
      lon: s.lon,
      kind: stopKind(s, i === 0 || i === stops.length - 1),
    }));
    const secondary: MapStop[] = secondaryLines
      .filter((ln) => ln.polyline.length >= 2 && !samePoints(polyline, ln.polyline))
      .flatMap((ln) =>
        ln.stops.map((s, j) => ({
          name: s.name,
          lat: s.lat,
          lon: s.lon,
          kind: (j === 0 || j === ln.stops.length - 1 ? 'secondary-terminus' : 'secondary') as MapStop['kind'],
        }))
      );
    return stopFeatureCollection([...primary, ...secondary]);
  }, [stops, secondaryLines, polyline]);

  const stopsSpec = useMemo<GeoLayerSpec>(
    () => ({
      sourceId: 'live-stops',
      data: stopFeatures,
      layers: [
        {
          id: 'live-stops-halo',
          type: 'circle',
          source: 'live-stops',
          filter: ['in', ['get', 'kind'], ['literal', ['tracked', 'selected']]],
          paint: {
            'circle-radius': ['match', ['get', 'kind'], 'selected', 10, 8],
            'circle-color': '#3b82f6',
            'circle-opacity': ['match', ['get', 'kind'], 'selected', 0.25, 0.12],
            'circle-stroke-width': 1,
            'circle-stroke-color': '#3b82f6',
            'circle-stroke-opacity': ['match', ['get', 'kind'], 'selected', 0.35, 0.2],
          },
        },
        {
          id: 'live-stops-dots',
          type: 'circle',
          source: 'live-stops',
          paint: {
            'circle-radius': [
              'match', ['get', 'kind'],
              'selected', 6.5, 'tracked', 5.5, 'terminus', 5,
              'secondary', 3, 'secondary-terminus', 4, 4,
            ],
            'circle-color': [
              'match', ['get', 'kind'],
              'selected', '#3b82f6', 'tracked', '#2563eb', 'terminus', '#52525b',
              'secondary', '#3f3f46', 'secondary-terminus', '#3f3f46', '#52525b',
            ],
            'circle-stroke-color': [
              'match', ['get', 'kind'],
              'selected', '#93c5fd', 'tracked', '#60a5fa', 'terminus', '#a1a1aa',
              'secondary', '#71717a', 'secondary-terminus', '#71717a', '#71717a',
            ],
            'circle-stroke-width': ['match', ['get', 'kind'], 'selected', 2.5, 'tracked', 2, 1],
          },
        },
        {
          id: 'live-stop-labels',
          type: 'symbol',
          source: 'live-stops',
          filter: ['!', ['in', ['get', 'kind'], ['literal', ['tracked', 'selected']]]],
          layout: {
            'text-field': ['get', 'name'],
            'text-font': MAP_FONT_STACK,
            'text-size': 10,
            'text-anchor': 'bottom',
            'text-offset': [0, -1.1],
            'text-optional': true,
            'symbol-sort-key': ['get', 'labelPriority'],
          },
          paint: {
            'text-color': labelColor,
            'text-halo-color': labelHalo,
            'text-halo-width': 1.2,
          },
        },
        {
          id: 'live-stop-labels-priority',
          type: 'symbol',
          source: 'live-stops',
          filter: ['in', ['get', 'kind'], ['literal', ['tracked', 'selected']]],
          layout: {
            'text-field': ['get', 'name'],
            'text-font': MAP_FONT_STACK,
            'text-size': 10,
            'text-anchor': 'bottom',
            'text-offset': [0, -1.1],
            'text-allow-overlap': true,
            'text-ignore-placement': true,
          },
          paint: {
            'text-color': labelColor,
            'text-halo-color': labelHalo,
            'text-halo-width': 1.2,
          },
        },
      ] as LayerSpecification[],
    }),
    [stopFeatures, labelColor, labelHalo]
  );

  useGeoLayer(map, loaded, primaryLine);
  useGeoLayer(map, loaded, secondarySpec);
  useGeoLayer(map, loaded, stopsSpec);

  const animVehicles = useMemo<AnimatedVehicleInput[]>(() => {
    const list: AnimatedVehicleInput[] = [];
    for (const v of vehicles) {
      list.push({
        id: v.vehicleId,
        polyline,
        location: v.location,
        variant: v.isApproximate ? 'approximate' : 'primary',
        bearing: v.bearing,
      });
    }
    for (const ln of secondaryLines) {
      const linePoly = ln.polyline.length >= 2 ? ln.polyline : polyline;
      for (const v of ln.vehicles) {
        list.push({
          id: v.vehicleId,
          polyline: linePoly,
          location: v.location,
          variant: v.isApproximate ? 'approximate' : 'secondary',
          bearing: v.bearing,
        });
      }
    }
    return list;
  }, [vehicles, polyline, secondaryLines]);

  useVehicleAnimations(map, loaded, animVehicles);

  // Auto-fit: center on the tracked/selected stop (plus vehicles when present)
  // until the user moves the camera. Preserved from the Leaflet implementation.
  useEffect(() => {
    if (tripKeyRef.current !== polyline) {
      fittedRef.current = false;
      tripKeyRef.current = polyline;
    }
  }, [polyline]);

  useEffect(() => {
    if (!map || !loaded || stops.length === 0 || userMovedRef.current) return;
    const tracked = stops.find((s) => s.isSelected) ?? stops.find((s) => s.isTracked);
    if (!tracked || (tracked.lat === 0 && tracked.lon === 0)) return;

    const stopKey = `${tracked.lat},${tracked.lon},${tracked.name}`;
    if (selectedStopKeyRef.current !== stopKey) {
      selectedStopKeyRef.current = stopKey;
      fittedRef.current = false;
    }

    const anchors: LatLon[] = [{ lat: tracked.lat, lon: tracked.lon }];
    for (const v of vehicles) {
      if (v.location.lat !== 0 || v.location.lon !== 0) anchors.push(v.location);
    }
    for (const ln of secondaryLines) {
      for (const v of ln.vehicles) {
        if (v.location.lat !== 0 || v.location.lon !== 0) anchors.push(v.location);
      }
    }

    if (anchors.length === 1) {
      if (!fittedRef.current) {
        programmaticMove(() => map.jumpTo({ center: [tracked.lon, tracked.lat], zoom: CLOSE_ZOOM }));
      }
      fittedRef.current = true;
      return;
    }

    const bounds = boundsOf(anchors);
    if (!bounds) return;
    if (
      fittedRef.current &&
      anchors.every((p) => map.getBounds().contains([p.lon, p.lat]))
    ) {
      return;
    }
    programmaticMove(() =>
      map.fitBounds(toLngLatBounds(bounds), { padding: 50, maxZoom: CLOSE_ZOOM, animate: false })
    );
    fittedRef.current = true;
  }, [map, loaded, stops, polyline, vehicles, secondaryLines, fitNonce, programmaticMove, userMovedRef]);

  const showWholeRoute = useCallback(() => {
    if (!map) return;
    const points = [...polyline, ...secondaryLines.flatMap((ln) => ln.polyline)];
    const bounds = boundsOf(points);
    if (!bounds) return;
    userMovedRef.current = true;
    setFollowActive(false);
    programmaticMove(() => map.fitBounds(toLngLatBounds(bounds), { padding: 40, animate: true }));
  }, [map, polyline, secondaryLines, programmaticMove, userMovedRef]);

  const resetView = useCallback(() => {
    resetUserMoved();
    setFollowActive(true);
    fittedRef.current = false;
    setFitNonce((n) => n + 1);
  }, [resetUserMoved]);

  const controls = useMemo<MapControlButton[]>(() => {
    const buttons: MapControlButton[] = [
      {
        id: 'recenter',
        title: followActive
          ? 'Centered on your stop and the vehicle'
          : 'Re-center on your stop and the vehicle',
        icon: RiFocus3Line,
        onClick: resetView,
        active: !followActive,
      },
      { id: 'whole-route', title: 'Show the whole route', icon: RiRouteLine, onClick: showWholeRoute },
      ...(onFullscreen
        ? [{ id: 'fullscreen', title: 'Fullscreen map', icon: RiFullscreenLine, onClick: onFullscreen }]
        : []),
    ];
    return buttons;
  }, [followActive, resetView, showWholeRoute, onFullscreen]);

  return (
    <TransitMap
      initialCenter={{ lat: 47.4979, lon: 19.0402 }}
      initialZoom={13}
      minZoom={6}
      dragRotate={false}
      attributionControl={{ compact: false }}
      ariaLabel="Live route map"
      className={fillHeight ? 'h-full w-full' : 'w-full'}
      style={fillHeight ? undefined : { height }}
      onReady={setMap}
      onLoadError={onLoadError}
    >
      <MapControls buttons={controls} />
    </TransitMap>
  );
}
