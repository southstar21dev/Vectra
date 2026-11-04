import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { RiFocus3Line, RiExpandDiagonalLine } from '@remixicon/react';
import * as maplibregl from '@/lib/maplibre';
import type { LayerSpecification } from 'maplibre-gl';
import { OPENFREEMAP_ATTRIBUTION } from '@/lib/map-tiles';
import { TransitMap } from './map/TransitMap';
import { useMapLoaded } from './map/use-map-loaded';
import { useGeoLayer, type GeoLayerSpec } from './map/use-geo-layers';
import { useProgrammaticGuard } from './map/use-camera';
import { MapControls, type MapControlButton } from './map/MapControls';
import { boundsOf } from './map/geo';
import { createColoredVehicleElement } from './map/markers';
import { journeyFitKey, type LatLon } from './route-geometry';

export interface RoutePreviewStop {
  id: string;
  name: string;
  lat: number;
  lon: number;
  /** Stops the user has already selected/tracked — highlighted. */
  selected?: boolean;
}

export interface RoutePreviewVehicle {
  id: string;
  lat: number;
  lon: number;
  bearing?: number;
}

interface RoutePreviewMapProps {
  /** One polyline per direction variant — the whole route. */
  polylines: LatLon[][];
  stops: RoutePreviewStop[];
  vehicles: RoutePreviewVehicle[];
  /** Route color as hex WITHOUT the leading #. */
  routeColor: string;
  /** Optional per-polyline colors (same length as polylines) — for multi-route trips. */
  polylineColors?: string[];
  /** Walking segments between stops/addresses — drawn dashed so they read as
   *  on-foot paths, not transit. */
  walkPolylines?: LatLon[][];
  /** Live approach of the next vehicle: the remaining path from its current
   *  position to the tracked stop, drawn as a lighter shade of the route color
   *  with its own stop dots. */
  approach?: { polyline: LatLon[]; stops: RoutePreviewStop[]; color?: string } | null;
  height?: number;
  onExpand?: () => void;
  /** Called with a stop id when its dot is clicked (undefined = dots inert). */
  onSelectStop?: (stopId: string) => void;
  /** Fired once when the map style is loaded and a frame has painted. */
  onReady?: () => void;
}

/** GeoJSON of every direction's polyline (one Feature per variant). When
 *  per-polyline colors are supplied they ride along as feature properties. */
function polylinesGeoJSON(
  polylines: LatLon[][],
  polylineColors?: string[]
): GeoJSON.FeatureCollection<GeoJSON.LineString> {
  return {
    type: 'FeatureCollection',
    features: polylines
      .map((pts, i) => ({ pts, i }))
      .filter((x) => x.pts.length >= 2)
      .map(({ pts, i }) => ({
        type: 'Feature' as const,
        properties: polylineColors ? { color: `#${polylineColors[i]}` } : {},
        geometry: {
          type: 'LineString' as const,
          coordinates: pts.map((p) => [p.lon, p.lat] as [number, number]),
        },
      })),
  };
}

/** GeoJSON of walking segments (straight dashed paths between stops). */
function walkGeoJSON(walks: LatLon[][]): GeoJSON.FeatureCollection<GeoJSON.LineString> {
  return {
    type: 'FeatureCollection',
    features: walks
      .filter((pts) => pts.length >= 2)
      .map((pts) => ({
        type: 'Feature' as const,
        properties: {},
        geometry: {
          type: 'LineString' as const,
          coordinates: pts.map((p) => [p.lon, p.lat] as [number, number]),
        },
      })),
  };
}

/** GeoJSON of stop dots — selected (tracked) stops get a blue emphasis. */
function stopsGeoJSON(stops: RoutePreviewStop[]): GeoJSON.FeatureCollection<GeoJSON.Point> {
  return {
    type: 'FeatureCollection',
    features: stops
      .filter((s) => s.lat !== 0 || s.lon !== 0)
      .map((s) => ({
        type: 'Feature' as const,
        properties: {
          id: s.id,
          name: s.name,
          selected: s.selected ? 1 : 0,
          radius: s.selected ? 7 : 4.5,
          strokeColor: s.selected ? '#93c5fd' : '#a1a1aa',
          strokeWidth: s.selected ? 2 : 1,
          fillColor: s.selected ? '#2563eb' : '#52525b',
        },
        geometry: { type: 'Point' as const, coordinates: [s.lon, s.lat] },
      })),
  };
}

/** GeoJSON of the approach's stop dots — tinted to the approach color. */
function approachStopsGeoJSON(
  stops: RoutePreviewStop[],
  color: string
): GeoJSON.FeatureCollection<GeoJSON.Point> {
  return {
    type: 'FeatureCollection',
    features: stops
      .filter((s) => s.lat !== 0 || s.lon !== 0)
      .map((s) => ({
        type: 'Feature' as const,
        properties: { id: s.id, name: s.name, fillColor: `#${color}` },
        geometry: { type: 'Point' as const, coordinates: [s.lon, s.lat] },
      })),
  };
}

/**
 * MapLibre GL preview of a whole route: every direction's polyline, the stop
 * dots, and the live positions of the vehicles currently running on it.
 * Self-contained so the tracker dialogs stay lightweight.
 */
export function RoutePreviewMap({
  polylines,
  stops,
  vehicles,
  routeColor,
  polylineColors,
  walkPolylines = [],
  approach = null,
  height = 220,
  onExpand,
  onSelectStop,
  onReady,
}: RoutePreviewMapProps) {
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

  const { programmaticMove, userMovedRef, resetUserMoved } = useProgrammaticGuard(map);
  const fittedKeyRef = useRef('');
  const vehicleMarkersRef = useRef<Map<string, maplibregl.Marker>>(new Map());

  const fitKey = useMemo(
    () => journeyFitKey(polylines, walkPolylines) + (approach?.polyline?.length ? '|approach' : ''),
    [polylines, walkPolylines, approach]
  );

  const walksSpec = useMemo<GeoLayerSpec>(
    () => ({
      sourceId: 'route-walks',
      data: walkGeoJSON(walkPolylines),
      layers: [
        {
          id: 'route-walks-casing',
          type: 'line',
          source: 'route-walks',
          layout: { 'line-cap': 'round', 'line-join': 'round' },
          paint: {
            'line-color': '#3f3f46',
            'line-width': 5,
            'line-opacity': 0.55,
            'line-dasharray': [1.2, 1.2],
          },
        },
        {
          id: 'route-walks-layer',
          type: 'line',
          source: 'route-walks',
          layout: { 'line-cap': 'round', 'line-join': 'round' },
          paint: {
            'line-color': '#d4d4d8',
            'line-width': 3,
            'line-opacity': 0.9,
            'line-dasharray': [1.2, 1.2],
          },
        },
      ] as LayerSpecification[],
    }),
    [walkPolylines]
  );

  const linesSpec = useMemo<GeoLayerSpec>(
    () => ({
      sourceId: 'route-lines',
      data: polylinesGeoJSON(polylines, polylineColors),
      layers: [
        {
          id: 'route-lines-casing',
          type: 'line',
          source: 'route-lines',
          layout: { 'line-cap': 'round', 'line-join': 'round' },
          paint: { 'line-color': '#1f2937', 'line-width': 7, 'line-opacity': 0.9 },
        },
        {
          id: 'route-lines-color',
          type: 'line',
          source: 'route-lines',
          layout: { 'line-cap': 'round', 'line-join': 'round' },
          paint: {
            'line-color': ['coalesce', ['get', 'color'], `#${routeColor}`],
            'line-width': 4,
            'line-opacity': 0.95,
          },
        },
      ] as LayerSpecification[],
    }),
    [polylines, polylineColors, routeColor]
  );

  const approachSpec = useMemo<GeoLayerSpec>(
    () => ({
      sourceId: 'route-approach',
      data: polylinesGeoJSON(
        approach?.polyline ? [approach.polyline] : [],
        approach?.color ? [approach.color] : undefined
      ),
      layers: [
        {
          id: 'route-approach-line',
          type: 'line',
          source: 'route-approach',
          layout: { 'line-cap': 'round', 'line-join': 'round' },
          paint: {
            'line-color': ['coalesce', ['get', 'color'], `#${routeColor}`],
            'line-width': 4,
            'line-opacity': 0.9,
          },
        },
      ] as LayerSpecification[],
    }),
    [approach, routeColor]
  );

  const stopsSpec = useMemo<GeoLayerSpec>(
    () => ({
      sourceId: 'route-stops',
      data: stopsGeoJSON(stops),
      layers: [
        {
          id: 'route-stops-layer',
          type: 'circle',
          source: 'route-stops',
          paint: {
            'circle-radius': ['get', 'radius'],
            'circle-color': ['get', 'fillColor'],
            'circle-stroke-color': ['get', 'strokeColor'],
            'circle-stroke-width': ['get', 'strokeWidth'],
          },
        },
      ] as LayerSpecification[],
    }),
    [stops]
  );

  const approachStopsSpec = useMemo<GeoLayerSpec>(
    () => ({
      sourceId: 'route-approach-stops',
      data: approachStopsGeoJSON(approach?.stops ?? [], approach?.color ?? routeColor),
      layers: [
        {
          id: 'route-approach-stops-layer',
          type: 'circle',
          source: 'route-approach-stops',
          paint: {
            'circle-radius': 4,
            'circle-color': ['coalesce', ['get', 'fillColor'], '#94a3b8'],
            'circle-stroke-color': '#ffffff',
            'circle-stroke-width': 1.5,
          },
        },
      ] as LayerSpecification[],
    }),
    [approach, routeColor]
  );

  useGeoLayer(map, loaded, walksSpec);
  useGeoLayer(map, loaded, linesSpec);
  useGeoLayer(map, loaded, approachSpec);
  useGeoLayer(map, loaded, stopsSpec);
  useGeoLayer(map, loaded, approachStopsSpec);

  // Stop tap + hover cursor (registered once the style is loaded).
  useEffect(() => {
    if (!map || !loaded) return;
    const onEnter = () => {
      map.getCanvas().style.cursor = onSelectStop ? 'pointer' : '';
    };
    const onLeave = () => {
      map.getCanvas().style.cursor = '';
    };
    const onClick = (e: maplibregl.MapLayerMouseEvent) => {
      const id = e.features?.[0]?.properties?.id;
      if (typeof id === 'string' && onSelectStop) onSelectStop(id);
    };
    map.on('mouseenter', 'route-stops-layer', onEnter);
    map.on('mouseleave', 'route-stops-layer', onLeave);
    map.on('click', 'route-stops-layer', onClick);
    return () => {
      map.off('mouseenter', 'route-stops-layer', onEnter);
      map.off('mouseleave', 'route-stops-layer', onLeave);
      map.off('click', 'route-stops-layer', onClick);
    };
  }, [map, loaded, onSelectStop]);

  // Fit once per geometry key unless the user has moved the camera. The guard
  // no longer latches on programmatic fits, so a new leg/walk refits.
  useEffect(() => {
    if (!map || !loaded || !fitKey || userMovedRef.current) return;
    if (fittedKeyRef.current === fitKey) return;
    const points: LatLon[] = [
      ...polylines.flat(),
      ...walkPolylines.flat(),
      ...(approach?.polyline ?? []),
    ];
    const bounds = boundsOf(points);
    if (!bounds) return;
    fittedKeyRef.current = fitKey;
    programmaticMove(() =>
      map.fitBounds(
        new maplibregl.LngLatBounds([bounds.minLon, bounds.minLat], [bounds.maxLon, bounds.maxLat]),
        { padding: 40, maxZoom: 15.5, animate: false }
      )
    );
  }, [map, loaded, fitKey, polylines, walkPolylines, approach, programmaticMove, userMovedRef]);

  // Vehicle markers.
  useEffect(() => {
    if (!map) return;
    const seen = new Set<string>();
    for (const v of vehicles) {
      if (v.lat === 0 && v.lon === 0) continue;
      seen.add(v.id);
      const existing = vehicleMarkersRef.current.get(v.id);
      if (existing) {
        existing.setLngLat([v.lon, v.lat]);
        if (v.bearing != null && Number.isFinite(v.bearing)) {
          const rotor = existing.getElement().querySelector<HTMLElement>('[data-vehicle-rotor]');
          if (rotor) rotor.style.transform = `rotate(${Math.round(v.bearing)}deg)`;
        }
      } else {
        const element = createColoredVehicleElement(`#${routeColor}`);
        if (v.bearing != null && Number.isFinite(v.bearing)) {
          const rotor = element.querySelector<HTMLElement>('[data-vehicle-rotor]');
          if (rotor) rotor.style.transform = `rotate(${Math.round(v.bearing)}deg)`;
        }
        const marker = new maplibregl.Marker({
          element,
          subpixelPositioning: true,
        })
          .setLngLat([v.lon, v.lat])
          .addTo(map);
        vehicleMarkersRef.current.set(v.id, marker);
      }
    }
    for (const [id, marker] of vehicleMarkersRef.current) {
      if (!seen.has(id)) {
        marker.remove();
        vehicleMarkersRef.current.delete(id);
      }
    }
  }, [map, vehicles, routeColor]);

  useEffect(() => {
    const markers = vehicleMarkersRef.current;
    return () => {
      for (const marker of markers.values()) marker.remove();
      markers.clear();
    };
  }, []);

  const showWholeRoute = useCallback(() => {
    if (!map) return;
    const points: LatLon[] = [
      ...polylines.flat(),
      ...walkPolylines.flat(),
      ...(approach?.polyline ?? []),
    ];
    const bounds = boundsOf(points);
    if (!bounds) return;
    resetUserMoved();
    fittedKeyRef.current = fitKey;
    programmaticMove(() =>
      map.fitBounds(
        new maplibregl.LngLatBounds([bounds.minLon, bounds.minLat], [bounds.maxLon, bounds.maxLat]),
        { padding: 40, maxZoom: 15.5, animate: true }
      )
    );
  }, [map, polylines, walkPolylines, approach, fitKey, programmaticMove, resetUserMoved]);

  const controls = useMemo<MapControlButton[]>(
    () => [
      { id: 'whole-route', title: 'Show whole route', icon: RiFocus3Line, onClick: showWholeRoute },
      ...(onExpand
        ? [{ id: 'expand', title: 'Expand', icon: RiExpandDiagonalLine, onClick: onExpand }]
        : []),
    ],
    [showWholeRoute, onExpand]
  );

  return (
    <div className="relative isolate w-full overflow-hidden rounded-2xl border border-subtle bg-surface-overlay">
      <TransitMap
        initialCenter={{ lat: 47.4979, lon: 19.0402 }}
        initialZoom={12}
        minZoom={5}
        maxZoom={19}
        attributionControl={false}
        ariaLabel="Route preview map"
        style={{ height }}
        className="w-full"
        onReady={setMap}
      >
        <MapControls buttons={controls} />
      </TransitMap>
      <div
        className="pointer-events-none absolute bottom-1.5 left-2 z-[1000] font-mono text-[9px] leading-tight text-[rgba(120,120,130,0.85)] [&_a]:text-[rgba(140,150,170,0.9)]"
        dangerouslySetInnerHTML={{ __html: OPENFREEMAP_ATTRIBUTION }}
      />
    </div>
  );
}
