import { useEffect, useRef } from 'react';
import type * as maplibregl from '@/lib/maplibre';
import type { LayerSpecification } from 'maplibre-gl';

/**
 * Source data, paint, layout, and `filter` reconcile in place on every sync.
 * Changing `type`, `source`, `minzoom`, or `maxzoom` on an existing layer id is
 * NOT reconciled — use a new layer id instead. Removing a paint or layout key
 * from the spec does not unset it on the map.
 */
export interface GeoLayerSpec {
  sourceId: string;
  data: GeoJSON.GeoJSON;
  layers: LayerSpecification[];
}

/** Minimal map surface used by the sync helpers, so they stay unit-testable. */
export interface MapLike {
  getSource(id: string): unknown;
  addSource(id: string, source: { type: 'geojson'; data: GeoJSON.GeoJSON }): void;
  getLayer(id: string): unknown;
  addLayer(layer: LayerSpecification): void;
  removeLayer(id: string): void;
  removeSource(id: string): void;
  setPaintProperty(id: string, name: string, value: unknown): void;
  setLayoutProperty(id: string, name: string, value: unknown): void;
  setFilter(id: string, filter: unknown): void;
}

interface GeoJSONSourceLike {
  setData(data: GeoJSON.GeoJSON): void;
}

/**
 * Creates or updates a GeoJSON source and its layers in place. Source data,
 * paint, layout, and `filter` reconcile; see {@link GeoLayerSpec} for the
 * properties that are not reconciled on an existing layer id.
 */
export function syncGeoLayer(map: MapLike, spec: GeoLayerSpec): void {
  const existing = map.getSource(spec.sourceId) as GeoJSONSourceLike | undefined;
  if (!existing) map.addSource(spec.sourceId, { type: 'geojson', data: spec.data });
  else existing.setData(spec.data);

  for (const layer of spec.layers) {
    if (!map.getLayer(layer.id)) {
      map.addLayer(layer);
      continue;
    }
    for (const [key, value] of Object.entries(layer.paint ?? {})) {
      map.setPaintProperty(layer.id, key, value);
    }
    for (const [key, value] of Object.entries(layer.layout ?? {})) {
      map.setLayoutProperty(layer.id, key, value);
    }
    if ('filter' in layer && layer.filter !== undefined) {
      map.setFilter(layer.id, layer.filter);
    }
  }
}

/** Removes layers first, then the source underneath them. */
export function removeGeoLayer(map: MapLike, sourceId: string, layerIds: string[]): void {
  for (const id of layerIds) if (map.getLayer(id)) map.removeLayer(id);
  if (map.getSource(sourceId)) map.removeSource(sourceId);
}

/**
 * Declarative GeoJSON layer lifecycle: syncs source data and layer properties
 * whenever they change and removes them on unmount. `loaded` must be true
 * (style ready) before the first sync.
 */
export function useGeoLayer(
  map: maplibregl.Map | null,
  loaded: boolean,
  spec: GeoLayerSpec
): void {
  const specRef = useRef(spec);
  specRef.current = spec;
  const layerIds = spec.layers.map((l) => l.id).join('|');

  useEffect(() => {
    if (!map || !loaded) return;
    syncGeoLayer(map as unknown as MapLike, specRef.current);
  }, [map, loaded, spec.sourceId, spec.data, spec.layers]);

  useEffect(() => {
    if (!map) return;
    return () => {
      removeGeoLayer(map as unknown as MapLike, spec.sourceId, layerIds.split('|').filter(Boolean));
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map, spec.sourceId, layerIds]);
}
