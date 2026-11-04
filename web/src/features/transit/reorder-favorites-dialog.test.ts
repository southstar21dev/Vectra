import { describe, it, expect } from 'vitest';
import {
  reorderFavoriteSlots,
  reorderAllSlots,
  extractRouteNumber,
  sortTrackersByNumber,
  applyFavoriteOrder,
} from './reorder-favorites-utils';
import type { TransitTracker } from './use-transit';

function mockTracker(id: string, isFavorite: boolean): TransitTracker {
  return {
    id,
    user_id: 'u1',
    stop_id: 's1',
    stop_name: 'Stop 1',
    route_id: 'r1',
    route_short_name: id,
    route_type: '3',
    dedupe_key: id,
    created_at: new Date().toISOString(),
    is_favorite: isFavorite,
    sort_order: 0,
  };
}

describe('reorderAllSlots', () => {
  it('moves an item down within the full list', () => {
    const items = [mockTracker('A', false), mockTracker('B', false), mockTracker('C', false)];
    const result = reorderAllSlots(items, 0, 1);
    expect(result.map((t) => t.id)).toEqual(['B', 'A', 'C']);
  });

  it('moves an item up within the full list', () => {
    const items = [mockTracker('A', false), mockTracker('B', false), mockTracker('C', false)];
    const result = reorderAllSlots(items, 2, -1);
    expect(result.map((t) => t.id)).toEqual(['A', 'C', 'B']);
  });

  it('does nothing when moving out of bounds', () => {
    const items = [mockTracker('A', false), mockTracker('B', false)];
    expect(reorderAllSlots(items, 0, -1)).toBe(items);
    expect(reorderAllSlots(items, 1, 1)).toBe(items);
  });
});

describe('reorderFavoriteSlots', () => {
  it('swaps favorite items within the full tracker array while preserving non-favorite positions', () => {
    const items = [
      mockTracker('NF1', false),
      mockTracker('F1', true),
      mockTracker('NF2', false),
      mockTracker('F2', true),
      mockTracker('NF3', false),
    ];

    // F1 is at favIndex 0, F2 is at favIndex 1
    // Moving F1 down (direction: 1)
    const result = reorderFavoriteSlots(items, 0, 1);

    expect(result.map((t) => t.id)).toEqual(['NF1', 'F2', 'NF2', 'F1', 'NF3']);
  });

  it('swaps multiple favorites without disturbing intermediate non-favorites', () => {
    const items = [
      mockTracker('F1', true),
      mockTracker('NF1', false),
      mockTracker('F2', true),
      mockTracker('NF2', false),
      mockTracker('F3', true),
    ];

    // F3 is at favIndex 2, move up
    const result = reorderFavoriteSlots(items, 2, -1);

    expect(result.map((t) => t.id)).toEqual(['F1', 'NF1', 'F3', 'NF2', 'F2']);
  });

  it('handles list where all items are favorites', () => {
    const items = [mockTracker('F1', true), mockTracker('F2', true), mockTracker('F3', true)];
    const result = reorderFavoriteSlots(items, 1, -1);
    expect(result.map((t) => t.id)).toEqual(['F2', 'F1', 'F3']);
  });

  it('returns original array when favorite target index is out of bounds', () => {
    const items = [mockTracker('F1', true), mockTracker('F2', true)];
    expect(reorderFavoriteSlots(items, 0, -1)).toBe(items);
    expect(reorderFavoriteSlots(items, 1, 1)).toBe(items);
  });
});

describe('extractRouteNumber', () => {
  it('extracts numbers correctly from Budapest transit lines', () => {
    expect(extractRouteNumber('1').num).toBe(1);
    expect(extractRouteNumber('M1').num).toBe(1);
    expect(extractRouteNumber('M2').num).toBe(2);
    expect(extractRouteNumber('M4').num).toBe(4);
    expect(extractRouteNumber('H5').num).toBe(5);
    expect(extractRouteNumber('28 / 28A').num).toBe(28);
    expect(extractRouteNumber('37 / 37A').num).toBe(37);
    expect(extractRouteNumber('988').num).toBe(988);
  });
});

describe('sortTrackersByNumber', () => {
  it('sorts trackers from lowest to highest based on their numbers', () => {
    const trackers = [
      mockTracker('M2', false),
      mockTracker('H5', false),
      mockTracker('M4', false),
      mockTracker('M1', false),
      mockTracker('37 / 37A', false),
      mockTracker('28 / 28A', false),
      mockTracker('988', false),
    ];

    const sorted = sortTrackersByNumber(trackers);
    expect(sorted.map((t) => t.route_short_name)).toEqual([
      'M1',
      'M2',
      'M4',
      'H5',
      '28 / 28A',
      '37 / 37A',
      '988',
    ]);
  });
});


describe('applyFavoriteOrder', () => {
  it('places the new favorites order into the favorite slots only', () => {
    const a = mockTracker('A', true);
    const b = mockTracker('B', false);
    const c = mockTracker('C', true);
    const d = mockTracker('D', true);
    const result = applyFavoriteOrder([a, b, c, d], [d, a, c]);
    expect(result.map((t) => t.id)).toEqual(['D', 'B', 'A', 'C']);
  });
});
