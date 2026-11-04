import type {
  FutarAlert,
  FutarArrivalsResponse,
  FutarRoute,
  FutarStop,
  FutarStopTime,
} from './types';

/**
 * Wire format of the `/api/vectra/futar` `stop-arrivals` endpoint (batched
 * arrivals), implemented in `pb/internal/futar/`.
 *
 * The nearby features poll up to a couple of dozen stops at once. Fetching each
 * stop through its own proxy invocation cost one round trip per stop and
 * repeated the same route/trip/alerts reference dictionaries in every payload.
 * The backend now fans out server-side, caches each stop's upstream response
 * for a few seconds, and returns this compact shape whose `shared` dictionaries
 * are deduplicated across the whole batch.
 *
 * `stops` stays per-stop (not shared): downstream code resolves a station's
 * physical platform dots from its own response's `references.stops`, and
 * merging those across stations would attribute another station's platforms to
 * the wrong dot.
 */
export interface FutarStopArrivalsBatchResponse {
  currentTime?: number;
  status?: string;
  data?: {
    shared?: {
      routes?: Record<string, FutarRoute>;
      trips?: Record<string, { routeId?: string; tripHeadsign?: string }>;
      alerts?: Record<string, FutarAlert>;
    };
    arrives?: Record<
      string,
      {
        stopId?: string;
        stopTimes?: FutarStopTime[];
        alertIds?: string[];
        stops?: Record<string, FutarStop>;
        /** Per-stop upstream failure, keeping the other stops usable. */
        error?: string;
      }
    >;
  };
}

export interface StopArrivalsBatch {
  /** Per-stop responses, each shaped exactly like a single-stop arrivals call. */
  responses: Record<string, FutarArrivalsResponse>;
  /** Per-stop failures (a whole-request failure never reaches this shape). */
  errors: Record<string, string>;
}

/**
 * Rebuild per-stop `FutarArrivalsResponse` objects from the compact batch
 * payload, so every existing consumer keeps working unchanged. Unknown stops
 * are omitted (the caller falls back to its own fetch for those).
 */
export function expandStopArrivalsBatch(
  payload: FutarStopArrivalsBatchResponse | null | undefined
): StopArrivalsBatch {
  const responses: Record<string, FutarArrivalsResponse> = {};
  const errors: Record<string, string> = {};
  const arrives = payload?.data?.arrives;
  if (!arrives) return { responses, errors };

  const shared = payload?.data?.shared;
  const currentTime = payload?.currentTime ?? Date.now();

  for (const [stopId, entry] of Object.entries(arrives)) {
    if (!entry) continue;
    if (entry.error) {
      errors[stopId] = entry.error;
      continue;
    }
    responses[stopId] = {
      currentTime,
      status: 'OK',
      data: {
        entry: {
          stopId: entry.stopId || stopId,
          stopTimes: entry.stopTimes ?? [],
          ...(entry.alertIds ? { alertIds: entry.alertIds } : {}),
        },
        references: {
          routes: shared?.routes ?? {},
          trips: shared?.trips ?? {},
          alerts: shared?.alerts ?? {},
          stops: entry.stops ?? {},
        },
      },
    };
  }

  return { responses, errors };
}
