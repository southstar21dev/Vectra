import { describe, expect, it } from 'vitest';
import { journeyFitKey, type LatLon } from './route-geometry';

const pt = (lat: number, lon: number): LatLon => ({ lat, lon });

describe('journeyFitKey', () => {
  it('returns empty for no geometry', () => {
    expect(journeyFitKey([])).toBe('');
    expect(journeyFitKey([[], [pt(1, 1)]])).toBe('');
  });

  it('captures every polyline, not just the first', () => {
    const one = [[pt(47.5, 19.05), pt(47.51, 19.06), pt(47.52, 19.07)]];
    const two = [
      [pt(47.5, 19.05), pt(47.51, 19.06), pt(47.52, 19.07)],
      [pt(47.49, 19.05), pt(47.48, 19.04), pt(47.47, 19.03)],
    ];
    // Same first polyline start/length — the OLD key would have been identical
    // and skipped the refit; the new key must differ.
    expect(journeyFitKey(one)).not.toBe(journeyFitKey(two));
  });

  it('changes when a walk is added or moved even if polylines are unchanged', () => {
    const polylines = [[pt(47.5, 19.05), pt(47.51, 19.06)]];
    const noWalks = journeyFitKey(polylines);
    const withWalk = journeyFitKey(polylines, [[pt(47.49, 19.04), pt(47.5, 19.05)]]);
    const movedWalk = journeyFitKey(polylines, [[pt(47.48, 19.03), pt(47.5, 19.05)]]);
    expect(withWalk).not.toBe(noWalks);
    expect(movedWalk).not.toBe(withWalk);
  });

  it('is stable for the same geometry (reference-independent)', () => {
    const a = journeyFitKey(
      [[pt(47.5, 19.05), pt(47.51, 19.06), pt(47.52, 19.07)]],
      [[pt(47.49, 19.04), pt(47.5, 19.05)]]
    );
    const b = journeyFitKey(
      [
        [
          { lat: 47.5, lon: 19.05 },
          { lat: 47.51, lon: 19.06 },
          { lat: 47.52, lon: 19.07 },
        ],
      ],
      [
        [
          { lat: 47.49, lon: 19.04 },
          { lat: 47.5, lon: 19.05 },
        ],
      ]
    );
    expect(a).toBe(b);
    expect(a.length).toBeGreaterThan(0);
  });
});