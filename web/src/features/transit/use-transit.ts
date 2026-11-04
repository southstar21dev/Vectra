import { useMemo } from 'react';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query-keys';
import { pb } from '@/lib/pocketbase';
import { requireSession, getSessionUserId } from '@/lib/auth-helpers';
import { isOnline } from '@/lib/online';
import { createOfflineCache } from '@/lib/offline-cache';
import { computeKeyedDigest, encryptField, decryptRow, isCiphertext } from '@/lib/encryption';
import { withUserId, type Json } from '@/lib/pocketbase-mappers';
import type { TransitTrackersRecord } from '@/types/pocketbase-types';
import { fetchCombinedRouteDetails, fetchStopArrivalsBatch, fetchTripDetails, fetchVehiclesForRoute } from './futar-api';
import { ARRIVALS_MINUTES_AFTER, loadStopArrivals, loadStopArrivalsWithLimit } from './arrivals-loader';
import type {
  FutarArrivalsResponse,
  FutarRouteDetailsResponse,
  FutarTripDetailsResponse,
  FutarVehiclesForRouteResponse,
  NewTrackerInput,
  UpdateTrackerStopsInput,
} from './types';
import { getCombinedRouteIds, MAX_FAVORITE_TRACKERS } from './transit-utils';

/**
 * Fields that identify the user's stops & routes — encrypted at rest with the
 * same AES-256-GCM (Web Crypto) scheme the schedule feature uses. route_type,
 * route_color and route_text_color are public route-catalog metadata and stay
 * plaintext (matching how schedule keeps colors/semesters plaintext).
 */
const ENCRYPTED_TRACKER_FIELDS = [
  'stop_id',
  'stop_name',
  'route_id',
  'route_short_name',
  'route_long_name',
  'headsign',
  'stops',
] as const;

/** Raw PocketBase `transit_trackers` row (fields optional in the generated types). */
type TransitTrackerRecord = TransitTrackersRecord<Json>;

/**
 * Domain shape the UI reads. PocketBase stores the owner as the `user`
 * relation; {@link withUserId} mirrors it onto `user_id` so existing components
 * and tests keep working. Encrypted fields (`stop_id`, `stop_name`, `headsign`,
 * `stops`, …) are decrypted at the data-layer boundary on read.
 */
export interface TransitTracker {
  id: string;
  user_id: string;
  stop_id: string;
  stop_name: string;
  route_id: string;
  route_short_name: string;
  route_long_name: string;
  route_type: string;
  route_color: string;
  route_text_color: string;
  headsign: string;
  is_favorite: boolean;
  dedupe_key: string;
  created_at: string;
  stops?: Json | null;
  sort_order?: number;
}

/** Create payload; the owner relation + timestamps are assigned by the backend. */
type TransitTrackerInsert = {
  stop_id: string;
  stop_name: string;
  route_id: string;
  route_short_name: string;
  route_long_name: string;
  route_type: string;
  route_color: string;
  route_text_color: string;
  headsign?: string;
  is_favorite?: boolean;
  dedupe_key: string;
  stops?: Json;
};

export type { NewTrackerInput, TrackerStop, UpdateTrackerStopsInput } from './types';

export { useNow } from '@/hooks/use-now';

const transitCache = createOfflineCache<TransitTracker[]>('vectra_transit_trackers_cache');

export const saveTransitCache = (trackers: TransitTracker[], userId?: string | null) => transitCache.write(trackers, userId);
export const readTransitCache = (userId?: string | null): TransitTracker[] | undefined => transitCache.read(userId) ?? undefined;

export function useTransitTrackers() {
  return useQuery({
    queryKey: queryKeys.transitTrackers,
    queryFn: async (): Promise<TransitTracker[]> => {
      const userId = getSessionUserId();

      // Fast-path immediate return if the browser is offline, or when there is
      // no session to scope the server query with (PocketBase's owner list rule
      // would reject an unscoped request).
      if (!isOnline() || !userId) {
        return readTransitCache(userId) || [];
      }

      const rows = await pb.collection<TransitTrackerRecord>('transit_trackers').getFullList({
        filter: pb.filter('user = {:u}', { u: userId }),
        sort: 'sort_order,created_at',
      });

      // Decrypt the encrypted-at-rest fields for display. Plaintext values
      // (legacy rows) pass through untouched.
      const trackers = await Promise.all(
        rows.map(async (row) => {
          const decrypted = (await decryptRow(withUserId(row), ENCRYPTED_TRACKER_FIELDS)) as unknown as TransitTracker;
          // A failed decrypt passes the ciphertext through. Throw so the
          // offline cache is never overwritten with it (handled below).
          if (ENCRYPTED_TRACKER_FIELDS.some((field) => isCiphertext((decrypted as unknown as Record<string, unknown>)[field]))) {
            throw new Error('Could not decrypt transit trackers.');
          }
          if (typeof decrypted.stops === 'string') {
            try {
              decrypted.stops = JSON.parse(decrypted.stops as string);
            } catch {
              // ignore
            }
          }
          return decrypted;
        })
      ).catch((err: unknown) => {
        // Show the last good copy rather than ciphertext; only fail outright
        // when there is nothing cached to fall back to.
        const cached = readTransitCache(userId);
        if (cached) return cached;
        throw err;
      }) as unknown as TransitTracker[];

      saveTransitCache(trackers, userId);
      return trackers;
    },
    // Seed from the offline cache so returning users never see a skeleton/empty
    // flash before their saved trackers render.
    placeholderData: () => readTransitCache(),
    refetchInterval: 30_000, // keep in sync across devices
    refetchOnWindowFocus: true,
  });
}

export function useAddTransitTracker() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: NewTrackerInput): Promise<TransitTracker> => {
      const session = requireSession();

      const primaryStop = (input.stops && input.stops[0]) || {
        stop_id: input.stop_id || '',
        stop_name: input.stop_name || '',
        headsign: input.headsign || '',
      };
      const stopsList = input.stops && input.stops.length > 0 ? input.stops : [primaryStop];
      const encryptedStops = await encryptField(JSON.stringify(stopsList));

      // Encrypt identifying fields at rest (AES-256-GCM via Web Crypto).
      // Deterministic keyed fingerprint so the server can enforce uniqueness
      // per route per user even though encrypted identifiers use randomized IVs.
      const encrypted: TransitTrackerInsert = {
        stop_id: (await encryptField(primaryStop.stop_id)) as string,
        stop_name: (await encryptField(primaryStop.stop_name)) as string,
        route_id: (await encryptField(input.route_id)) as string,
        route_short_name: (await encryptField(input.route_short_name)) as string,
        route_long_name: (await encryptField(input.route_long_name)) as string,
        headsign: primaryStop.headsign ? ((await encryptField(primaryStop.headsign)) as string) : undefined,
        route_type: input.route_type,
        route_color: input.route_color,
        route_text_color: input.route_text_color,
        is_favorite: false,
        dedupe_key: await computeKeyedDigest(input.route_id, primaryStop.stop_id, primaryStop.headsign || ''),
        stops: encryptedStops as any,
      };

      const record = await pb.collection<TransitTrackerRecord>('transit_trackers').create({
        ...encrypted,
        user: session.record!.id,
      });
      return withUserId(record) as unknown as TransitTracker;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.transitTrackers });
    },
  });
}

export function useUpdateTrackerStops() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, stops }: UpdateTrackerStopsInput): Promise<void> => {
      // Owner scoping is enforced server-side by the collection's owner rules.
      requireSession();
      const primaryStop = stops[0] || { stop_id: '', stop_name: '', headsign: '' };
      const encryptedStops = await encryptField(JSON.stringify(stops));
      const encryptedPrimaryStopId = await encryptField(primaryStop.stop_id);
      const encryptedPrimaryStopName = await encryptField(primaryStop.stop_name);
      const encryptedPrimaryHeadsign = primaryStop.headsign ? await encryptField(primaryStop.headsign) : '';

      await pb.collection('transit_trackers').update(id, {
        stops: encryptedStops as any,
        stop_id: encryptedPrimaryStopId as string,
        stop_name: encryptedPrimaryStopName as string,
        headsign: encryptedPrimaryHeadsign as string,
      });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.transitTrackers });
    },
  });
}

/** Mutations that read and rewrite tracker sort order share one serial queue. */
const TRACKER_ORDER_SCOPE = 'transit-tracker-order';

/** Toggle the favorite/pin flag that surfaces the tracker on the dashboard. */
export function useToggleTransitFavorite() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (trackerId: string) => {
      const session = requireSession();
      const userId = session.record!.id;

      // Read current flag and favorites to enforce the max limit and set sort order.
      const row = await pb.collection<TransitTrackerRecord>('transit_trackers').getOne(trackerId);
      const next = !(row.is_favorite ?? false);

      let nextSortOrder = row.sort_order ?? 0;

      if (next) {
        const favorites = await pb.collection<TransitTrackerRecord>('transit_trackers').getFullList({
          filter: pb.filter('user = {:u} && is_favorite = true', { u: userId }),
        });
        if (favorites.length >= MAX_FAVORITE_TRACKERS) {
          throw new Error(`Maximum of ${MAX_FAVORITE_TRACKERS} favorite trackers allowed.`);
        }

        const maxOrder = favorites.length > 0
          ? Math.max(...favorites.map((f) => f.sort_order ?? 0))
          : -1;
        nextSortOrder = maxOrder + 1;
      }

      // PocketBase returns the updated record and throws if the owner/update
      // rule blocks the write, so the returned record is the landed-write
      // signal (replacing the old silent 0-rows RLS check).
      const updated = await pb.collection<TransitTrackerRecord>('transit_trackers').update(trackerId, {
        is_favorite: next,
        sort_order: nextSortOrder,
      });
      if (!updated || updated.is_favorite !== next) {
        console.warn('Favorite toggle update did not land as expected.');
        throw new Error('Could not update favorite');
      }
      return next;
    },
    // Read-then-write: two quick taps must run one after the other, or both
    // pass the favourites limit check and take the same sort_order.
    scope: { id: TRACKER_ORDER_SCOPE },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.transitTrackers });
    },
  });
}

/** Reorder favorited trackers by updating their sort_order sequentially. */
export function useReorderTransitTrackers() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (orderedTrackerIds: string[]) => {
      // Owner scoping is enforced server-side by the collection's owner rules.
      requireSession();
      await Promise.all(
        orderedTrackerIds.map((id, index) =>
          pb.collection('transit_trackers').update(id, { sort_order: index })
        )
      );
    },
    scope: { id: TRACKER_ORDER_SCOPE },
    // Refetch on failure too: some rows may already have their new order.
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.transitTrackers });
    },
  });
}

export function useDeleteTransitTracker() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (trackerId: string) => {
      // Owner scoping is enforced server-side by the collection's owner rules.
      requireSession();
      await pb.collection('transit_trackers').delete(trackerId);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.transitTrackers });
    },
  });
}

/**
 * Query function for one stop's arrivals. Shallow requests are coalesced into
 * batch calls by {@link loadStopArrivals}; a deeper `limit` stays per-stop.
 */
function stopArrivalsQueryFn(stopId: string, limit?: number): () => Promise<FutarArrivalsResponse> {
  return () => (limit ? loadStopArrivalsWithLimit(stopId, limit) : loadStopArrivals(stopId));
}

/** Raw arrivals responses, one query per stop (trackers sharing a stop share the query). */
export function useStopArrivals(stopId: string | null) {
  return useQuery({
    queryKey: queryKeys.transitArrivals(stopId),
    queryFn: stopArrivalsQueryFn(stopId as string),
    enabled: !!stopId,
    refetchInterval: 20_000,
    refetchOnWindowFocus: true, // a resumed app must not show stale countdowns until the next poll
    staleTime: 10_000,
    retry: 2,
  });
}

export interface StopArrivalsEntry {
  data?: FutarArrivalsResponse;
  isLoading: boolean;
  isError: boolean;
  /** When this stop's snapshot arrived — the card's staleness signal. */
  dataUpdatedAt: number;
}

/** Per-stop arrivals state, keyed by the stop id that was requested. */
export type StopArrivalsMap = Record<string, StopArrivalsEntry>;

/**
 * Multi-stop arrivals query hook: one cache entry per stop (deduplicated
 * across components via TanStack Query), while the network side coalesces
 * every shallow stop polled in the same tick into one batch request (see
 * `arrivals-loader.ts`).
 *
 * `options.limit` asks for a deeper stop-times window (chained trip planning
 * needs the same trip visible at the destination stop minutes later). It is
 * part of the query key, so a deep query never races the shallow one for the
 * same stop; a fresh deep response also answers the shallow request for that
 * stop instead of fetching it again. Omitting it keeps the compact tracker
 * payload.
 */
export function useMultiStopArrivals(
  stopIds: (string | null | undefined)[],
  options?: { limit?: number; enabled?: boolean }
) {
  const limit = options?.limit;
  const enabled = options?.enabled ?? true;
  const validIds = useMemo(() => Array.from(new Set(stopIds.filter((id): id is string => Boolean(id)))), [stopIds]);
  const results = useQueries({
    queries: validIds.map((stopId) => ({
      queryKey: queryKeys.transitArrivals(stopId, limit),
      queryFn: stopArrivalsQueryFn(stopId, limit),
      enabled,
      refetchInterval: 20_000,
      refetchOnWindowFocus: true,
      staleTime: 10_000,
      retry: 2,
    })),
  });

  return useMemo<StopArrivalsMap>(() => {
    const map: StopArrivalsMap = {};
    validIds.forEach((id, index) => {
      const res = results[index];
      map[id] = {
        data: res?.data,
        isLoading: res ? res.isLoading || res.isFetching : false,
        isError: !!res?.isError,
        dataUpdatedAt: res?.dataUpdatedAt ?? 0,
      };
    });
    return map;
  }, [validIds, results]);
}

/**
 * Same contract as {@link useMultiStopArrivals}, but every stop comes from one
 * proxy request ({@link fetchStopArrivalsBatch}). Used by the nearby features,
 * which poll a dozen or more stops at once — one round trip and a deduplicated
 * payload instead of one invocation per stop.
 *
 * The batch response also seeds each stop's own single-stop cache entry, so a
 * tracker card polling the same stop shares this fetch instead of duplicating
 * it.
 */
export function useBatchStopArrivals(stopIds: (string | null | undefined)[]): StopArrivalsMap {
  const validIds = useMemo(
    () => Array.from(new Set(stopIds.filter((id): id is string => Boolean(id)))),
    [stopIds]
  );
  // Order-insensitive key: two components polling the same stops in a
  // different order share one request.
  const batchKey = useMemo(() => [...validIds].sort().join(','), [validIds]);
  const queryClient = useQueryClient();

  const query = useQuery({
    queryKey: queryKeys.transitArrivalsBatch(batchKey),
    queryFn: async () => {
      const batch = await fetchStopArrivalsBatch(validIds, ARRIVALS_MINUTES_AFTER);
      for (const [id, response] of Object.entries(batch.responses)) {
        queryClient.setQueryData(queryKeys.transitArrivals(id), response);
      }
      return batch;
    },
    enabled: validIds.length > 0,
    refetchInterval: 20_000,
    refetchOnWindowFocus: true,
    staleTime: 10_000,
    retry: 2,
  });

  const { data, isLoading, isFetching, isError, dataUpdatedAt } = query;
  return useMemo<StopArrivalsMap>(() => {
    const map: StopArrivalsMap = {};
    const loading = isLoading || isFetching;
    for (const id of validIds) {
      map[id] = {
        data: data?.responses[id],
        isLoading: loading,
        isError: !!data?.errors[id] || (isError && !data?.responses[id]),
        dataUpdatedAt: dataUpdatedAt ?? 0,
      };
    }
    return map;
  }, [validIds, data, isLoading, isFetching, isError, dataUpdatedAt]);
}

/**
 * Cache key for a combined line's MERGED vehicle list (28 + 28A). It must not
 * share {@link queryKeys.transitVehicles}: {@link useVehiclesForRoutes} stores
 * the single member route's response under that key, and the group id doubles
 * as a member id, so the two shapes would overwrite each other.
 */
export const combinedVehiclesKey = (routeId: string | null) => ['transit-vehicles-combined', routeId] as const;

/** Key for {@link useVehiclesForRoute}: merged groups get their own key. */
export function vehiclesForRouteKey(routeId: string | null) {
  const combinedIds = routeId ? getCombinedRouteIds(routeId) : null;
  return combinedIds && combinedIds.length > 1 ? combinedVehiclesKey(routeId) : queryKeys.transitVehicles(routeId);
}

/** Live vehicles on a route — polled every 10s while the live position modal is open. */
export function useVehiclesForRoute(routeId: string | null, options?: { enabled?: boolean }) {
  return useQuery({
    queryKey: vehiclesForRouteKey(routeId),
    queryFn: async (): Promise<FutarVehiclesForRouteResponse> => {
      if (!routeId) throw new Error('No routeId');
      const combinedIds = getCombinedRouteIds(routeId);
      if (combinedIds && combinedIds.length > 1) {
        const responses = await Promise.all(combinedIds.map((id) => fetchVehiclesForRoute(id).catch(() => null)));
        const valid = responses.filter((r): r is FutarVehiclesForRouteResponse => !!r && !!r.data);
        if (valid.length === 0) throw new Error('Vehicle positions unavailable for every member route');
        const list = valid.flatMap((r) => r.data?.list || []);
        const trips = valid.reduce<Record<string, { tripHeadsign?: string; directionId?: string; routeId?: string }>>(
          (acc, r) => ({ ...acc, ...(r.data?.references?.trips || {}) }),
          {}
        );
        return {
          currentTime: valid[0]?.currentTime || Date.now(),
          status: 'OK',
          data: {
            list,
            references: { trips },
          },
        };
      }
      return fetchVehiclesForRoute(routeId);
    },
    enabled: !!routeId && (options?.enabled ?? true),
    refetchInterval: 10_000,
    refetchOnWindowFocus: true,
    staleTime: 5_000,
    retry: 2,
  });
}

/** Live vehicles across several routes (a multi-leg trip), merged into one list.
 *  Combined groups (28/28A) expand to their member ids like the single-route hook. */
export function useVehiclesForRoutes(routeIds: (string | null | undefined)[]) {
  const memberIds = useMemo(() => {
    const out: string[] = [];
    for (const id of routeIds) {
      if (!id) continue;
      const combined = getCombinedRouteIds(id);
      out.push(...(combined && combined.length > 1 ? combined : [id]));
    }
    return Array.from(new Set(out));
  }, [routeIds]);
  const results = useQueries({
    queries: memberIds.map((routeId) => ({
      queryKey: queryKeys.transitVehicles(routeId),
      queryFn: (): Promise<FutarVehiclesForRouteResponse> => fetchVehiclesForRoute(routeId),
      refetchInterval: 10_000,
      refetchOnWindowFocus: true,
      staleTime: 5_000,
      retry: 2,
    })),
  });

  return useMemo(() => {
    const vehicles: Array<{ vehicleId: string; tripId?: string; location?: { lat: number; lon: number } }> = [];
    for (const res of results) {
      for (const v of res?.data?.data?.list ?? []) {
        if (v.location && v.location.lat !== 0 && v.location.lon !== 0) {
          vehicles.push(v);
        }
      }
    }
    return { vehicles, isRefetching: results.some((r) => r.isRefetching) };
  }, [results]);
}

/**
 * Static-per-trip geometry (encoded polyline + stop list). Cached by tripId
 * with a long staleTime; the 60s refetch just lets a broadcast appear.
 */
export function useTripDetails(tripId: string | null) {
  return useQuery({
    queryKey: queryKeys.transitTripDetails(tripId),
    queryFn: (): Promise<FutarTripDetailsResponse> => fetchTripDetails(tripId as string),
    enabled: !!tripId,
    staleTime: 60_000,
    refetchInterval: 60_000,
    retry: 2,
  });
}

/** Route-level stop list + geometry — used when no upcoming departure can
 *  resolve a specific trip (the live-position dialog still draws the line).
 *  Combines variants and stops for paired lines (4/6, 28/28A, 37/37A). */
export function useRouteDetails(routeId: string | null) {
  return useQuery({
    queryKey: queryKeys.transitRouteDetails(routeId),
    queryFn: (): Promise<FutarRouteDetailsResponse> => fetchCombinedRouteDetails(routeId as string),
    enabled: !!routeId,
    staleTime: 60_000,
    refetchInterval: 60_000,
    retry: 2,
  });
}

