import { useMemo } from 'react';
import type { TransitTracker } from './use-transit';
import type { Arrival } from './types';
import { useMultiStopArrivals } from './use-transit';
import { useCatalogStops } from './use-nearby';
import { haversineMeters, type LatLon } from './route-geometry';
import { getTrackerStops, parseStopArrivals } from './transit-utils';

export type TrackerStopRef = { stop_id: string; stop_name: string; headsign?: string | null };

export interface StopView {
  stop: TrackerStopRef;
  arrivals: Arrival[];
  loading: boolean;
  error: boolean;
  updatedAt: number | undefined;
}

export interface PinnedRow {
  tracker: TransitTracker;
  /** Every stop of the tracker, in its saved order. */
  views: StopView[];
  /** The stop shown in the row: the one nearest you, else the first. */
  primary: number;
  /** Catalog position of the primary stop, for the map. */
  primaryAt: LatLon | null;
  /** Next departure at the primary stop, for sorting. */
  soonestMs: number | null;
}

/**
 * The dashboard's pinned trackers as rows: one arrivals query per stop shared
 * across every tracker, each tracker narrowed to the stop nearest you, and the
 * rows ordered by whichever departs first.
 */
export function usePinnedRows(trackers: TransitTracker[], position: LatLon | null): PinnedRow[] {
  const catalogStops = useCatalogStops();
  const trackerStops = useMemo(
    () =>
      trackers.map((tracker) => {
        const stops = getTrackerStops(tracker);
        return stops.length > 0
          ? stops
          : [{ stop_id: tracker.stop_id, stop_name: tracker.stop_name, headsign: tracker.headsign }];
      }),
    [trackers]
  );
  const stopIds = useMemo(() => trackerStops.flatMap((stops) => stops.map((s) => s.stop_id)), [trackerStops]);
  const arrivals = useMultiStopArrivals(stopIds);
  const coords = useMemo(
    () => new Map((catalogStops.data ?? []).map((stop) => [stop.id, stop] as const)),
    [catalogStops.data]
  );

  return useMemo(() => {
    const rows = trackers.map((tracker, index): PinnedRow => {
      const stops = trackerStops[index];
      const views: StopView[] = stops.map((stop) => {
        const query = arrivals[stop.stop_id];
        return {
          stop,
          arrivals: parseStopArrivals(
            query?.data,
            tracker.route_id,
            stop.headsign || (stops.length === 1 ? tracker.headsign : null) || null
          ),
          loading: (query?.isLoading ?? false) && !query?.data,
          error: query?.isError ?? false,
          updatedAt: query?.dataUpdatedAt,
        };
      });

      let primary = 0;
      let primaryDistance: number | null = null;
      if (position) {
        stops.forEach((stop, i) => {
          const at = coords.get(stop.stop_id);
          if (!at) return;
          const distance = haversineMeters(position, at);
          if (primaryDistance === null || distance < primaryDistance) {
            primary = i;
            primaryDistance = distance;
          }
        });
      }

      const first = views[primary]?.arrivals[0];
      const at = coords.get(stops[primary]?.stop_id ?? '');
      return {
        tracker,
        views,
        primary,
        primaryAt: at ? { lat: at.lat, lon: at.lon } : null,
        soonestMs: first ? first.arrivalMs : null,
      };
    });
    // Stable: trackers without departures keep their saved order at the end.
    return rows
      .map((row, index) => ({ row, index }))
      .sort((a, b) => {
        if (a.row.soonestMs === null || b.row.soonestMs === null) {
          return a.row.soonestMs === null ? (b.row.soonestMs === null ? a.index - b.index : 1) : -1;
        }
        return a.row.soonestMs - b.row.soonestMs || a.index - b.index;
      })
      .map(({ row }) => row);
  }, [trackers, trackerStops, arrivals, coords, position]);
}

