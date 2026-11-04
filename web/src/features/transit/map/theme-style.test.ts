import { describe, expect, it } from 'vitest';
import type { StyleSpecification } from 'maplibre-gl';
import { carryOverCustomLayers } from './theme-style';

const style = (sources: string[], layers: Array<[id: string, source?: string]>): StyleSpecification => ({
  version: 8,
  sources: Object.fromEntries(sources.map((id) => [id, { type: 'geojson', data: { type: 'FeatureCollection', features: [] } }])),
  layers: layers.map(([id, source]) =>
    source ? { id, type: 'line', source } : { id, type: 'background' }
  ) as StyleSpecification['layers'],
});

describe('carryOverCustomLayers', () => {
  it('keeps app sources and layers on top of the new basemap', () => {
    const previous = style(['openmaptiles', 'route'], [['bg'], ['roads', 'openmaptiles'], ['route-line', 'route']]);
    const next = style(['openmaptiles'], [['bg'], ['roads', 'openmaptiles'], ['water', 'openmaptiles']]);
    const merged = carryOverCustomLayers(previous, next, new Set(['openmaptiles']));
    expect(Object.keys(merged.sources)).toEqual(['openmaptiles', 'route']);
    expect(merged.layers.map((l) => l.id)).toEqual(['bg', 'roads', 'water', 'route-line']);
  });

  it('drops basemap-only sources the new theme does not declare', () => {
    const previous = style(['openmaptiles', 'ne2'], [['ne-layer', 'ne2']]);
    const next = style(['openmaptiles'], [['roads', 'openmaptiles']]);
    const merged = carryOverCustomLayers(previous, next, new Set(['openmaptiles', 'ne2']));
    expect(Object.keys(merged.sources)).toEqual(['openmaptiles']);
    expect(merged.layers.map((l) => l.id)).toEqual(['roads']);
  });

  it('returns the next style when there is no previous one', () => {
    const next = style(['openmaptiles'], []);
    expect(carryOverCustomLayers(undefined, next, new Set())).toBe(next);
  });
});
