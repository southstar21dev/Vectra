import { describe, expect, it } from 'vitest';
import {
  accuracyCircleFeature,
  boundsOf,
  cutLineFeature,
  lineFeatureCollection,
  samePoints,
  stopFeatureCollection,
} from './geo';
import { cumulativeHaversineDistances } from '../route-geometry';
import { labelPriority } from './types';

describe('lineFeatureCollection', () => {
  it('drops polylines shorter than 2 points and emits [lon, lat] coordinates', () => {
    const fc = lineFeatureCollection([
      [{ lat: 47.5, lon: 19.05 }, { lat: 47.6, lon: 19.1 }],
      [{ lat: 1, lon: 1 }],
    ]);
    expect(fc.features).toHaveLength(1);
    expect(fc.features[0].geometry.coordinates).toEqual([
      [19.05, 47.5],
      [19.1, 47.6],
    ]);
  });

  it('attaches per-index properties', () => {
    const fc = lineFeatureCollection(
      [
        [{ lat: 1, lon: 1 }, { lat: 2, lon: 2 }],
        [{ lat: 3, lon: 3 }, { lat: 4, lon: 4 }],
      ],
      (i) => ({ color: i === 0 ? '#111' : '#222' })
    );
    expect(fc.features.map((f) => f.properties?.color)).toEqual(['#111', '#222']);
  });
});

describe('stopFeatureCollection', () => {
  it('maps kind and label priority, skipping invalid coordinates', () => {
    const fc = stopFeatureCollection([
      { id: 'a', name: 'A', lat: 47.5, lon: 19.05, kind: 'selected' },
      { name: 'B', lat: 47.5, lon: 19.05 },
      { name: 'Zero', lat: 0, lon: 0 },
    ]);
    expect(fc.features).toHaveLength(2);
    expect(fc.features[0].properties).toMatchObject({ id: 'a', kind: 'selected', labelPriority: 4 });
    expect(fc.features[1].properties).toMatchObject({ id: '', kind: 'regular', labelPriority: 1 });
  });
});

describe('accuracyCircleFeature', () => {
  it('returns null for a non-positive radius or invalid center', () => {
    expect(accuracyCircleFeature({ lat: 47.5, lon: 19.05 }, 0)).toBeNull();
    expect(accuracyCircleFeature({ lat: 0, lon: 0 }, 50)).toBeNull();
  });

  it('builds a closed polygon ring', () => {
    const f = accuracyCircleFeature({ lat: 47.5, lon: 19.05 }, 100);
    expect(f).not.toBeNull();
    const ring = f!.geometry.coordinates[0];
    expect(ring).toHaveLength(65);
    expect(ring[0]).toEqual(ring[64]);
  });
});

describe('boundsOf', () => {
  it('returns null for no points and min/max otherwise', () => {
    expect(boundsOf([])).toBeNull();
    expect(boundsOf([{ lat: 47, lon: 19 }, { lat: 48, lon: 20 }])).toEqual({
      minLat: 47,
      minLon: 19,
      maxLat: 48,
      maxLon: 20,
    });
  });
});

describe('samePoints', () => {
  const line = [
    { lat: 47.5, lon: 19.05 },
    { lat: 47.6, lon: 19.1 },
  ];

  it('returns true for the same reference', () => {
    expect(samePoints(line, line)).toBe(true);
  });

  it('returns true for equal content in different arrays', () => {
    expect(samePoints(line, [{ lat: 47.5, lon: 19.05 }, { lat: 47.6, lon: 19.1 }])).toBe(true);
  });

  it('returns false for different lengths', () => {
    expect(samePoints(line, [line[0]])).toBe(false);
  });

  it('returns false when a point differs at the same length', () => {
    expect(samePoints(line, [line[0], { lat: 47.6, lon: 19.11 }])).toBe(false);
  });
});

describe('cutLineFeature', () => {
  const line = [
    { lat: 47.5, lon: 19.05 },
    { lat: 47.6, lon: 19.1 },
  ];

  it('returns null when the cut is shorter than two points', () => {
    const dists = cumulativeHaversineDistances(line);
    expect(cutLineFeature(line, dists, -5)).toBeNull();
  });

  it('returns a LineString in [lon, lat] order', () => {
    const dists = cumulativeHaversineDistances(line);
    const f = cutLineFeature(line, dists, dists[dists.length - 1] / 2, { color: '#fff' });
    expect(f).not.toBeNull();
    expect(f!.geometry.coordinates[0][0]).toBeCloseTo(19.05, 3);
    expect(f!.properties?.color).toBe('#fff');
  });
});

describe('labelPriority', () => {
  it('orders selected > tracked > terminus > regular > secondary', () => {
    expect(labelPriority('selected')).toBe(4);
    expect(labelPriority('tracked')).toBe(3);
    expect(labelPriority('terminus')).toBe(2);
    expect(labelPriority('regular')).toBe(1);
    expect(labelPriority('secondary')).toBe(0);
    expect(labelPriority('secondary-terminus')).toBe(0);
    expect(labelPriority(undefined)).toBe(1);
  });
});
