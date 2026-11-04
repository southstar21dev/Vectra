import { pb } from '@/lib/pocketbase';
import { getErrorMessage } from '@/lib/error-utils';
import { timeoutSignal } from '@/lib/timeout-signal';
import { getCombinedRouteIds, normalizeStopIdToStation } from './transit-utils';
import {
  expandStopArrivalsBatch,
  type FutarStopArrivalsBatchResponse,
  type StopArrivalsBatch,
} from './arrivals-batch';
import type {
  FutarArrivalsResponse,
  FutarRouteDetailsResponse,
  FutarRouteVariant,
  FutarTripDetailsResponse,
  FutarVehiclesForRouteResponse,
} from './types';

type FutarJson = FutarArrivalsResponse & { error?: string };

function assertFutarOk(json: FutarJson): FutarArrivalsResponse {
  if (!json || (json as { status?: string }).status !== 'OK') {
    throw new Error(json?.error || (json as { text?: string })?.text || 'Futár API returned an error');
  }
  return json as FutarArrivalsResponse;
}

/**
 * Call a whitelisted Futár endpoint through the server-side proxy (the Futár
 * API key never touches the client). `POST /api/vectra/futar` injects the key
 * from the server environment; the request is aborted client-side after 40s so
 * a slow/hung proxy can never leave the query pending forever.
 */
async function proxyFutar(
  endpoint: string,
  params: Record<string, string | number | boolean>
): Promise<unknown> {
  const data = await pb.send('/api/vectra/futar', {
    method: 'POST',
    body: { endpoint, params },
    signal: timeoutSignal(40_000),
  });
  return assertFutarOk(data as FutarJson);
}

/**
 * Stop-times cap for chained trip planning. Futár's implicit default (60)
 * truncates busy interchanges to ~25 minutes of entries, so a chained
 * departure's expected vehicle is missing from the destination stop — the
 * timeline then drops the option. 200 is the ceiling Futár accepts
 * (400 → LIMIT_EXCEEDED) and covers 60–90+ min even at Deák tér.
 */
export const DEEP_ARRIVALS_LIMIT = 200;

export async function fetchStopArrivals(
  stopId: string,
  minutesAfter = 60,
  limit?: number
): Promise<FutarArrivalsResponse> {
  const stationId = normalizeStopIdToStation(stopId);
  return (await proxyFutar('arrivals-and-departures-for-stop', {
    stopId: stationId,
    minutesBefore: 0,
    minutesAfter,
    ...(limit ? { limit } : {}),
  })) as FutarArrivalsResponse;
}

/** Stops per batch request — mirrors the server-side cap (`pb/internal/futar/`). */
export const MAX_BATCH_ARRIVAL_STOPS = 20;

async function fetchStopArrivalsIndividually(
  stationIds: string[],
  minutesAfter: number
): Promise<StopArrivalsBatch> {
  const settled = await Promise.allSettled(
    stationIds.map((id) => fetchStopArrivals(id, minutesAfter))
  );
  const responses: StopArrivalsBatch['responses'] = {};
  const errors: StopArrivalsBatch['errors'] = {};
  settled.forEach((result, index) => {
    const id = stationIds[index];
    if (result.status === 'fulfilled') responses[id] = result.value;
    else errors[id] = getErrorMessage(result.reason, 'Arrivals unavailable');
  });
  return { responses, errors };
}

/**
 * Arrivals for many stops in one request. The proxy fans out to Futár, caches
 * each stop's response for a few seconds and deduplicates the route/trip/alert
 * references, so the nearby map pays one round trip and a much smaller payload
 * instead of one invocation per stop.
 *
 * Any batch failure (including a proxy deployed before this endpoint existed)
 * falls back to the previous one-request-per-stop behaviour, so the feed always
 * loads.
 */
export async function fetchStopArrivalsBatch(
  stopIds: string[],
  minutesAfter = 90
): Promise<StopArrivalsBatch> {
  const stationIds = Array.from(
    new Set(stopIds.map(normalizeStopIdToStation).filter((id) => id.length > 0))
  );
  if (stationIds.length === 0) return { responses: {}, errors: {} };

  const chunks: string[][] = [];
  for (let i = 0; i < stationIds.length; i += MAX_BATCH_ARRIVAL_STOPS) {
    chunks.push(stationIds.slice(i, i + MAX_BATCH_ARRIVAL_STOPS));
  }

  try {
    const payloads = (await Promise.all(
      chunks.map((chunk) =>
        proxyFutar('stop-arrivals', {
          stopIds: chunk.join(','),
          minutesBefore: 0,
          minutesAfter,
        })
      )
    )) as FutarStopArrivalsBatchResponse[];

    const merged: StopArrivalsBatch = { responses: {}, errors: {} };
    for (const payload of payloads) {
      const expanded = expandStopArrivalsBatch(payload);
      Object.assign(merged.responses, expanded.responses);
      Object.assign(merged.errors, expanded.errors);
    }
    return merged;
  } catch (err) {
    console.warn(
      'Batched arrivals request failed, falling back to per-stop requests:',
      getErrorMessage(err)
    );
    return fetchStopArrivalsIndividually(stationIds, minutesAfter);
  }
}

export async function fetchVehiclesForRoute(routeId: string): Promise<FutarVehiclesForRouteResponse> {
  return (await proxyFutar('vehicles-for-route', { routeId })) as FutarVehiclesForRouteResponse;
}

export async function fetchTripDetails(tripId: string): Promise<FutarTripDetailsResponse> {
  return (await proxyFutar('trip-details', {
    tripId,
    includeSchedule: true,
    includeStatus: true,
  })) as FutarTripDetailsResponse;
}

/** Route-level stop list + per-direction geometry (the Add Tracker route
 *  browser's stop picker and the live-position fallback when no trip resolves).
 *  NOTE: this fork's key scopes out routes-for-agency / stops-for-route, so the
 *  full catalog ships as static JSON (public/catalog/routes.json / stops.json,
 *  fetched at runtime). */
export async function fetchRouteDetails(routeId: string): Promise<FutarRouteDetailsResponse> {
  return (await proxyFutar('route-details', { routeId })) as FutarRouteDetailsResponse;
}

/**
 * Tag every variant of a route-details response with the id it was requested
 * under, so merged combined-line responses can still tell their members apart.
 */
function tagVariantRouteIds(
  response: FutarRouteDetailsResponse,
  routeId: string
): FutarRouteDetailsResponse {
  const variants = response.data?.entry?.variants;
  if (!variants || variants.length === 0) return response;
  return {
    ...response,
    data: {
      ...response.data,
      entry: {
        ...response.data?.entry,
        variants: variants.map((variant) => ({ ...variant, routeId })),
      },
    },
  };
}

async function fetchRouteDetailsTagged(routeId: string): Promise<FutarRouteDetailsResponse> {
  return tagVariantRouteIds(await fetchRouteDetails(routeId), routeId);
}

/**
 * Route details with automatic merging for combined lines (4/6, 28/28A, 37/37A).
 * Combines variants and stop lists so all branch stops (e.g. Újbuda-központ M
 * and Móricz Zsigmond körtér M) are available simultaneously. Every merged
 * variant keeps the member route id it came from.
 */
export async function fetchCombinedRouteDetails(routeId: string): Promise<FutarRouteDetailsResponse> {
  const combinedIds = getCombinedRouteIds(routeId);
  if (!combinedIds || combinedIds.length <= 1) {
    return fetchRouteDetailsTagged(routeId);
  }

  const responses = await Promise.all(
    combinedIds.map(async (id) => ({
      id,
      response: await fetchRouteDetails(id).catch(() => null),
    }))
  );
  const valid = responses.filter(
    (entry): entry is { id: string; response: FutarRouteDetailsResponse } =>
      !!entry.response && !!entry.response.data?.entry
  );
  if (valid.length === 0) {
    return fetchRouteDetailsTagged(routeId);
  }

  const primary = valid[0].response;
  const allVariants: FutarRouteVariant[] = [];

  for (const { id, response } of valid) {
    // Dedupe within each member response only — the same headsign/name from
    // another member is a distinct direction variant (4 vs 6), so it must be
    // kept and tagged with its own route id.
    const seenVariantKeys = new Set<string>();
    for (const v of response.data?.entry?.variants || []) {
      const key = `${v.headsign || ''}-${v.name || ''}`;
      if (seenVariantKeys.has(key)) continue;
      seenVariantKeys.add(key);
      allVariants.push({ ...v, routeId: id });
    }
  }

  const mergedStops = valid.reduce<Record<string, any>>(
    (acc, { response }) => ({ ...acc, ...(response.data?.references?.stops || {}) }),
    {}
  );
  const mergedRoutes = valid.reduce<Record<string, any>>(
    (acc, { response }) => ({ ...acc, ...(response.data?.references?.routes || {}) }),
    {}
  );

  return {
    currentTime: primary.currentTime || Date.now(),
    status: 'OK',
    data: {
      entry: {
        ...primary.data?.entry,
        variants: allVariants,
      },
      references: {
        stops: mergedStops,
        routes: mergedRoutes,
      },
    },
  };
}
