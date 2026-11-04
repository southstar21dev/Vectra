import { useEffect, useMemo, useRef, useState } from 'react';
import { RiArrowRightSLine, RiStarLine } from '@remixicon/react';
import { adoptDeviceLocationGrant } from '@/features/weather/weather-location';
import NearbyExplorerDialog from '@/features/transit/nearby-explorer-dialog';
import { NearbyDeparturesList } from '@/features/transit/nearby-departures-list';
import { NearbyMapStrip } from '@/features/transit/nearby-map-strip';
import { TransitCard } from '@/features/transit/transit-card';
import { usePinnedRows } from '@/features/transit/use-pinned-rows';
import { useNearbyData } from '@/features/transit/use-nearby-data';
import type { TransitTracker } from '@/features/transit/use-transit';
import { useAuth } from '@/features/auth/use-auth';
import { parseModeFilter } from '@/features/transit/transit-mode-filter';
import { useDialogPresence } from '@/hooks/use-dialog-presence';

/**
 * The dashboard's one transit card: a slice of the map around you (with your
 * pinned lines' nearest stops on it) over your starred trackers as the same
 * stop monitors the transit page uses, ordered by what leaves first. Without pins it lists the
 * lines at the closest stop instead. The map opens the nearby sheet.
 */
export function TransitWidget({
  favorites,
  onOpenTracker,
}: {
  /** Starred trackers, in their saved order. */
  favorites: TransitTracker[];
  onOpenTracker: (tracker: TransitTracker) => void;
}) {
  const { profile } = useAuth();
  const explorer = useDialogPresence<true>();

  const modeFilter = useMemo(() => parseModeFilter(profile?.preferences as Record<string, unknown> | null | undefined), [profile?.preferences]);
  const { radarItems, stops, position, geoState, status, refreshLocation } = useNearbyData(modeFilter);
  const pinnedRows = usePinnedRows(favorites, position);

  const hasPinned = favorites.length > 0;

  // A fix received during this session (not a persisted seed from a previous
  // one) means the user just granted location — mirror that into the weather
  // location setting, like the old one-shot prompt did.
  const [mountedAt] = useState(() => Date.now());
  const grantedRef = useRef(false);
  useEffect(() => {
    if (grantedRef.current || geoState.status !== 'tracking') return;
    if (geoState.lastUpdatedMs < mountedAt) return;
    grantedRef.current = true;
    adoptDeviceLocationGrant({ latitude: geoState.lat, longitude: geoState.lon });
  }, [geoState, mountedAt]);


  const peekStops = useMemo(() => stops.slice(0, 8).map((s) => ({ id: s.id, lat: s.lat, lon: s.lon })), [stops]);
  const pins = useMemo(
    () =>
      pinnedRows.flatMap((row) =>
        row.primaryAt
          ? [{
              id: row.tracker.id,
              ...row.primaryAt,
              label: row.tracker.route_short_name || row.tracker.route_id,
              color: row.tracker.route_color,
              textColor: row.tracker.route_text_color,
            }]
          : []
      ),
    [pinnedRows]
  );

  const openExplorer = () => explorer.openWith(true);

  const pinnedList = (
    <ul className="grid grid-cols-1 gap-2.5 p-3">
      {pinnedRows.map((row) => (
        <li key={row.tracker.id} className="min-w-0">
          <TransitCard tracker={row.tracker} onOpen={onOpenTracker} showFavoriteMark={false} />
        </li>
      ))}
    </ul>
  );

  const nearbyList = (
    <div className="px-3.5 pb-3.5">
      <NearbyDeparturesList items={radarItems} status={status} />
      <a
        href="/transit"
        className="mt-3 flex items-center gap-2 border-t border-subtle pt-3 text-xs font-semibold text-secondary transition-colors hover:text-primary"
      >
        <RiStarLine className="h-3.5 w-3.5 text-tint-amber" aria-hidden />
        <span className="flex-1">Star a tracker to pin it here</span>
        <RiArrowRightSLine className="h-3.5 w-3.5" aria-hidden />
      </a>
    </div>
  );

  return (
    <>
      <section className="min-w-0 overflow-hidden rounded-3xl card-glass">
        <h2 className="sr-only">Transit</h2>
        <NearbyMapStrip
          status={status}
          position={position}
          stops={peekStops}
          pins={pins}
          highlightStopId={hasPinned ? null : radarItems[0]?.stopId ?? null}
          onOpen={openExplorer}
          onLocate={refreshLocation}
        />

        {hasPinned ? pinnedList : nearbyList}
      </section>

      {explorer.value !== null && (
        <NearbyExplorerDialog
          open={explorer.open}
          onClose={() => explorer.onOpenChange(false)}
          onExited={explorer.onExited}
        />
      )}
    </>
  );
}
