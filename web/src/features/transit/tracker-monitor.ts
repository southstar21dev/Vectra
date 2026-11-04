import { formatBudapestHm } from '@/lib/date-utils';
import { groupArrivalsByHeadsign } from './transit-utils';
import type { Arrival } from './types';

/** Directions a tracker monitor renders as columns; extra ones are counted. */
export const MONITOR_MAX_COLUMNS = 3;

export interface MonitorStopInput {
  stopId: string;
  /** The tracked direction at this stop; it leads the column order. */
  headsign: string | null;
  arrivals: Arrival[];
}

export interface MonitorGrid {
  /** Direction (terminus) per column, in display order. */
  columns: string[];
  /** Per stop, in tracker order: the next arrival in each column, or null. */
  rows: Array<{ stopId: string; cells: Array<Arrival | null> }>;
  /** Directions beyond MONITOR_MAX_COLUMNS, counted rather than dropped. */
  extraColumns: number;
}

/**
 * A tracker as a stop display: directions are the columns, stops are the rows,
 * each cell the next departure. Columns are the union of every stop's
 * directions, tracked direction first, then first-seen order.
 */
export function buildMonitorGrid(stops: MonitorStopInput[], maxColumns = MONITOR_MAX_COLUMNS): MonitorGrid {
  const perStop = stops.map((stop) => {
    const groups = groupArrivalsByHeadsign(stop.arrivals);
    if (stop.headsign) {
      groups.sort((a, b) => (a.name === stop.headsign ? -1 : b.name === stop.headsign ? 1 : 0));
    }
    return { stopId: stop.stopId, groups };
  });

  const allColumns: string[] = [];
  for (const { groups } of perStop) {
    for (const group of groups) {
      if (!allColumns.includes(group.name)) allColumns.push(group.name);
    }
  }
  const columns = allColumns.slice(0, maxColumns);

  return {
    columns,
    rows: perStop.map(({ stopId, groups }) => ({
      stopId,
      cells: columns.map((name) => groups.find((g) => g.name === name)?.list[0] ?? null),
    })),
    extraColumns: allColumns.length - columns.length,
  };
}

/** Minutes until `arrivalMs`, never negative. */
export function minutesUntil(arrivalMs: number, now: number): number {
  return Math.max(0, Math.ceil((arrivalMs - now) / 60_000));
}

/**
 * The monitor's time readout: "NOW" when due, `7'` within the hour, and the
 * Budapest clock time (`23:41`) beyond it, where a countdown stops being useful.
 */
export function formatMonitorTime(arrivalMs: number, now: number): string {
  const minutes = minutesUntil(arrivalMs, now);
  if (minutes <= 0) return 'NOW';
  if (minutes < 60) return `${minutes}'`;
  return formatBudapestHm(arrivalMs);
}
