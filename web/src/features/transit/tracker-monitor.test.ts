import { describe, expect, it } from 'vitest';
import { buildMonitorGrid, formatMonitorTime, MONITOR_MAX_COLUMNS } from './tracker-monitor';
import type { Arrival } from './types';

const NOW = Date.UTC(2026, 9, 13, 10, 0, 0); // 12:00 in Budapest (CEST)

function arrival(headSign: string, minutes: number, isPredicted = true): Arrival {
  return { arrivalMs: NOW + minutes * 60_000, headSign, isPredicted, tripId: `${headSign}-${minutes}` };
}

describe('buildMonitorGrid', () => {
  it('puts each stop on a row and each direction in a column, next departure per cell', () => {
    const grid = buildMonitorGrid([
      { stopId: 'oktogon', headsign: null, arrivals: [arrival('Széll Kálmán tér', 2), arrival('Újbuda-központ', 11), arrival('Széll Kálmán tér', 7)] },
      { stopId: 'blaha', headsign: null, arrivals: [arrival('Újbuda-központ', 8), arrival('Széll Kálmán tér', 5)] },
    ]);

    expect(grid.columns).toEqual(['Széll Kálmán tér', 'Újbuda-központ']);
    expect(grid.rows.map((r) => r.stopId)).toEqual(['oktogon', 'blaha']);
    expect(grid.rows[0].cells.map((c) => c?.tripId)).toEqual(['Széll Kálmán tér-2', 'Újbuda-központ-11']);
    expect(grid.rows[1].cells.map((c) => c?.tripId)).toEqual(['Széll Kálmán tér-5', 'Újbuda-központ-8']);
    expect(grid.extraColumns).toBe(0);
  });

  it('leads with the tracked direction and leaves a gap where a stop has no departure', () => {
    const grid = buildMonitorGrid([
      { stopId: 'a', headsign: 'Újbuda-központ', arrivals: [arrival('Széll Kálmán tér', 1), arrival('Újbuda-központ', 4)] },
      { stopId: 'b', headsign: null, arrivals: [arrival('Újbuda-központ', 3)] },
    ]);

    expect(grid.columns).toEqual(['Újbuda-központ', 'Széll Kálmán tér']);
    expect(grid.rows[1].cells[1]).toBeNull();
  });

  it('counts directions beyond the column cap instead of dropping them silently', () => {
    const grid = buildMonitorGrid([
      { stopId: 'astoria', headsign: null, arrivals: ['A', 'B', 'C', 'D'].map((h, i) => arrival(h, i + 1)) },
    ]);

    expect(grid.columns).toHaveLength(MONITOR_MAX_COLUMNS);
    expect(grid.extraColumns).toBe(1);
  });
});

describe('formatMonitorTime', () => {
  it('reads "NOW" when due, minutes within the hour, the clock time beyond it', () => {
    expect(formatMonitorTime(NOW + 10_000, NOW + 30_000)).toBe('NOW');
    expect(formatMonitorTime(NOW + 7 * 60_000, NOW)).toBe("7'");
    expect(formatMonitorTime(NOW + 59 * 60_000, NOW)).toBe("59'");
    expect(formatMonitorTime(NOW + 74 * 60_000, NOW)).toBe('13:14');
  });
});
