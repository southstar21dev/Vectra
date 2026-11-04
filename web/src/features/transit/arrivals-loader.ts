import { DEEP_ARRIVALS_LIMIT, fetchStopArrivals, fetchStopArrivalsBatch } from './futar-api';
import { normalizeStopIdToStation } from './transit-utils';
import type { FutarArrivalsResponse } from './types';

/**
 * Request coalescing for per-stop arrivals.
 *
 * Every tracker card and pinned row keeps its own per-stop query (so the cache
 * stays keyed per stop and components share entries), but the query functions
 * route through this loader: stops requested within a short window go out as
 * one `stop-arrivals` batch call instead of one proxy request each. Queries
 * mounted together start their poll timers together, so a page of trackers
 * refetches as a single request.
 *
 * Deep fetches (trip planning) are a superset of the shallow window — same
 * `minutesAfter`, more stop times — so a recent or in-flight deep response for
 * a station also satisfies a shallow request instead of fetching it twice.
 */

/** Minutes of arrivals every per-stop query asks for. */
export const ARRIVALS_MINUTES_AFTER = 90;
/** How long requests are collected before the batch goes out. */
export const BATCH_WINDOW_MS = 25;
/** A deep response this young also answers a shallow request. */
export const DEEP_REUSE_MS = 15_000;

interface Waiter {
  resolve: (response: FutarArrivalsResponse) => void;
  reject: (err: unknown) => void;
}

let queue = new Map<string, Waiter[]>();
let timer: ReturnType<typeof setTimeout> | null = null;
const recentDeep = new Map<string, { at: number; promise: Promise<FutarArrivalsResponse> }>();

function settle(waiters: Waiter[] | undefined, response: FutarArrivalsResponse | undefined, error?: unknown) {
  for (const w of waiters ?? []) {
    if (response) w.resolve(response);
    else w.reject(error ?? new Error('Arrivals unavailable'));
  }
}

async function flush(): Promise<void> {
  const batch = queue;
  queue = new Map();
  timer = null;
  const ids = Array.from(batch.keys());
  if (ids.length === 0) return;

  if (ids.length === 1) {
    const [id] = ids;
    try {
      settle(batch.get(id), await fetchStopArrivals(id, ARRIVALS_MINUTES_AFTER));
    } catch (err) {
      settle(batch.get(id), undefined, err);
    }
    return;
  }

  try {
    const { responses, errors } = await fetchStopArrivalsBatch(ids, ARRIVALS_MINUTES_AFTER);
    for (const id of ids) {
      settle(batch.get(id), responses[id], errors[id] ? new Error(errors[id]) : undefined);
    }
  } catch (err) {
    for (const id of ids) settle(batch.get(id), undefined, err);
  }
}

function freshDeep(stationId: string, now = Date.now()) {
  const hit = recentDeep.get(stationId);
  if (!hit) return null;
  if (now - hit.at > DEEP_REUSE_MS) {
    recentDeep.delete(stationId);
    return null;
  }
  return hit.promise;
}

/** Shallow arrivals for one stop, coalesced with every other stop requested in the same window. */
export function loadStopArrivals(stopId: string): Promise<FutarArrivalsResponse> {
  const stationId = normalizeStopIdToStation(stopId);
  const deep = freshDeep(stationId);
  if (deep) return deep;
  return new Promise<FutarArrivalsResponse>((resolve, reject) => {
    const waiters = queue.get(stationId);
    if (waiters) waiters.push({ resolve, reject });
    else queue.set(stationId, [{ resolve, reject }]);
    if (!timer) timer = setTimeout(() => void flush(), BATCH_WINDOW_MS);
  });
}

/**
 * Arrivals with a custom stop-times cap (the batch endpoint takes no limit, so
 * these stay per-stop). A deep-limit response is remembered briefly so the
 * shallow tracker query for the same station reuses it.
 */
export function loadStopArrivalsWithLimit(stopId: string, limit: number): Promise<FutarArrivalsResponse> {
  const promise = fetchStopArrivals(stopId, ARRIVALS_MINUTES_AFTER, limit);
  if (limit >= DEEP_ARRIVALS_LIMIT) {
    const stationId = normalizeStopIdToStation(stopId);
    const entry = { at: Date.now(), promise };
    recentDeep.set(stationId, entry);
    promise.then(
      () => {
        // Freshness counts from when the data landed, not when it was asked for.
        if (recentDeep.get(stationId) === entry) entry.at = Date.now();
      },
      () => {
        if (recentDeep.get(stationId) === entry) recentDeep.delete(stationId);
      }
    );
  }
  return promise;
}

/** Test hook: drop queued requests and remembered deep responses. */
export function resetArrivalsLoader(): void {
  if (timer) clearTimeout(timer);
  timer = null;
  queue = new Map();
  recentDeep.clear();
}
