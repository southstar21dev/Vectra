import type { StyleSpecification } from 'maplibre-gl';

/**
 * `transformStyle` for a basemap theme swap: keep every source the app added
 * at runtime (routes, stops, vehicles) and the layers drawing them, on top of
 * the incoming basemap. `basemapSourceIds` are the sources the previous
 * basemap style itself declared — anything else in the live style is ours.
 */
export function carryOverCustomLayers(
  previous: StyleSpecification | undefined,
  next: StyleSpecification,
  basemapSourceIds: ReadonlySet<string>
): StyleSpecification {
  if (!previous) return next;
  const customSources = Object.fromEntries(
    Object.entries(previous.sources ?? {}).filter(([id]) => !basemapSourceIds.has(id) && !(id in (next.sources ?? {})))
  );
  const nextLayerIds = new Set(next.layers.map((layer) => layer.id));
  const customLayers = previous.layers.filter(
    (layer) =>
      !nextLayerIds.has(layer.id) &&
      'source' in layer &&
      typeof layer.source === 'string' &&
      layer.source in customSources
  );
  return {
    ...next,
    sources: { ...next.sources, ...customSources },
    layers: [...next.layers, ...customLayers],
  };
}
