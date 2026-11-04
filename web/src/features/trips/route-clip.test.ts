import { describe, it, expect } from 'vitest';
import { clipRouteToJourney, type ClipStop } from './route-clip';
import type { LatLon } from '@/features/transit/route-geometry';

/** A straight north-south polyline, 1e-4° per vertex, so nearest-vertex snapping is predictable. */
function line(n: number, startLat = 47.47): LatLon[] {
  return Array.from({ length: n }, (_, i) => ({ lat: startLat + i * 0.0001, lon: 19.05 }));
}

function stopsAt(line: LatLon[], idxs: number[]): ClipStop[] {
  return idxs.map((i, j) => ({
    id: `S${j}`,
    name: `Stop ${j}`,
    lat: line[i].lat,
    lon: line[i].lon,
  }));
}

describe('clipRouteToJourney', () => {
  it('slices approach (line start → boarding) and taken (boarding → destination)', () => {
    const pts = line(10); // vertices 0..9
    const stops = stopsAt(pts, [2, 7]);
    const clip = clipRouteToJourney(pts, stops, 'Stop 0', 'Stop 1');
    expect(clip).not.toBeNull();
    // Approach: vertices 0..2 (up to and including the boarding stop).
    expect(clip!.approach.length).toBe(3);
    expect(clip!.approach[0]).toEqual(pts[0]);
    expect(clip!.approach[clip!.approach.length - 1]).toEqual(pts[2]);
    // Taken: vertices 2..7.
    expect(clip!.taken.length).toBe(6);
    expect(clip!.taken[0]).toEqual(pts[2]);
    expect(clip!.taken[clip!.taken.length - 1]).toEqual(pts[7]);
    expect(clip!.fromIndex).toBe(0);
    expect(clip!.toIndex).toBe(1);
  });

  it('ignores a variant that serves the destination BEFORE the departure (wrong direction)', () => {
    const pts = line(10);
    const stops = stopsAt(pts, [7, 2]); // dest at index 0 in stop order, boarding at 1
    const clip = clipRouteToJourney(pts, stops, 'Stop 0', 'Stop 1');
    expect(clip).toBeNull();
  });

  it('returns null when a stop name is not on the variant', () => {
    const pts = line(10);
    const stops = stopsAt(pts, [2]);
    expect(clipRouteToJourney(pts, stops, 'Stop 0', 'Not here')).toBeNull();
    expect(clipRouteToJourney(pts, stops, 'Not here', 'Stop 0')).toBeNull();
  });

  it('matches names with accents/diacritics folded', () => {
    const pts = line(10);
    const stops = stopsAt(pts, [2, 7]);
    stops[0] = { ...stops[0], name: 'Teleki László tér' };
    stops[1] = { ...stops[1], name: 'Fővám tér' };
    const clip = clipRouteToJourney(pts, stops, 'teleki laszlo ter', 'fovam ter');
    expect(clip).not.toBeNull();
    expect(clip!.taken[0]).toEqual(pts[2]);
  });

  it('returns null for empty/short polylines or missing coordinates', () => {
    const pts = line(10);
    const stops = stopsAt(pts, [2, 7]);
    expect(clipRouteToJourney([], stops, 'Stop 0', 'Stop 1')).toBeNull();
    expect(clipRouteToJourney([pts[0]], stops, 'Stop 0', 'Stop 1')).toBeNull();
    const noCoords = stops.map((s) => ({ ...s, lat: 0, lon: 0 }));
    expect(clipRouteToJourney(pts, noCoords, 'Stop 0', 'Stop 1')).toBeNull();
  });

  it('uses the nearest polyline vertex when a stop sits between vertices', () => {
    const pts = line(10);
    const stops: ClipStop[] = [
      { id: 'A', name: 'Dep', lat: pts[3].lat + 0.00002, lon: pts[3].lon }, // between 3 and 4
      { id: 'B', name: 'Dest', lat: pts[7].lat, lon: pts[7].lon },
    ];
    const clip = clipRouteToJourney(pts, stops, 'Dep', 'Dest');
    expect(clip).not.toBeNull();
    expect(clip!.taken[0]).toEqual(pts[3]);
  });
});