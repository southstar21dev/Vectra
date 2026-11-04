import { describe, expect, it, vi } from 'vitest';
import type { FilterSpecification } from 'maplibre-gl';
import { removeGeoLayer, syncGeoLayer, type MapLike } from './use-geo-layers';

function fakeMap() {
  const sources = new Map<string, { setData: ReturnType<typeof vi.fn> }>();
  const layers = new Set<string>();
  const calls: string[] = [];
  const map: MapLike = {
    getSource: (id) => {
      const source = sources.get(id);
      return source ?? undefined;
    },
    addSource: (id) => {
      calls.push(`addSource:${id}`);
      sources.set(id, { setData: vi.fn() });
    },
    getLayer: (id) => (layers.has(id) ? { id } : undefined),
    addLayer: (layer) => {
      calls.push(`addLayer:${layer.id}`);
      layers.add(layer.id);
    },
    removeLayer: (id) => {
      calls.push(`removeLayer:${id}`);
      layers.delete(id);
    },
    removeSource: (id) => {
      calls.push(`removeSource:${id}`);
      sources.delete(id);
    },
    setPaintProperty: (id, name, value) => calls.push(`paint:${id}:${name}:${String(value)}`),
    setLayoutProperty: (id, name, value) => calls.push(`layout:${id}:${name}:${String(value)}`),
    setFilter: (id, filter) => calls.push(`filter:${id}:${JSON.stringify(filter)}`),
  };
  return { map, sources, layers, calls };
}

const empty = { type: 'FeatureCollection' as const, features: [] };

describe('syncGeoLayer', () => {
  it('creates the source and layers on first sync, then updates data and paint', () => {
    const { map, sources, calls } = fakeMap();
    const spec = {
      sourceId: 'line',
      data: empty,
      layers: [
        {
          id: 'line-casing' as const,
          type: 'line' as const,
          source: 'line',
          paint: { 'line-width': 7 as const },
        },
      ],
    };
    syncGeoLayer(map, spec);
    expect(calls).toContain('addSource:line');
    expect(calls).toContain('addLayer:line-casing');

    const updated = { type: 'FeatureCollection' as const, features: [] };
    syncGeoLayer(map, { ...spec, data: updated });
    expect(sources.get('line')!.setData).toHaveBeenCalledWith(updated);

    syncGeoLayer(map, {
      ...spec,
      layers: [{ ...spec.layers[0], paint: { 'line-width': 4 as const } }],
    });
    expect(calls).toContain('paint:line-casing:line-width:4');
    expect(calls.filter((c) => c === 'addLayer:line-casing')).toHaveLength(1);
  });

  it('reconciles an updated filter on an existing layer without re-adding it', () => {
    const { map, calls } = fakeMap();
    const first: FilterSpecification = ['==', 'kind', 'selected'];
    const second: FilterSpecification = ['==', 'kind', 'terminus'];
    const layer = { id: 'line-casing' as const, type: 'line' as const, source: 'line' };
    syncGeoLayer(map, {
      sourceId: 'line',
      data: empty,
      layers: [{ ...layer, filter: first }],
    });
    syncGeoLayer(map, {
      sourceId: 'line',
      data: empty,
      layers: [{ ...layer, filter: second }],
    });
    expect(calls.filter((c) => c === 'addLayer:line-casing')).toHaveLength(1);
    expect(calls).toContain(`filter:line-casing:${JSON.stringify(second)}`);
  });
});

describe('removeGeoLayer', () => {
  it('removes layers before the source', () => {
    const { map, calls } = fakeMap();
    syncGeoLayer(map, {
      sourceId: 'line',
      data: empty,
      layers: [{ id: 'line-casing' as const, type: 'line' as const, source: 'line' }],
    });
    calls.length = 0;
    removeGeoLayer(map, 'line', ['line-casing']);
    expect(calls).toEqual(['removeLayer:line-casing', 'removeSource:line']);
  });
});
