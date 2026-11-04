import { RiTrainLine, RiSubwayLine, RiBusLine, RiBus2Line, RiShip2Line, type RemixiconComponentType } from '@remixicon/react';
import type { Arrival, FutarAlert, FutarArrivalsResponse, FutarRoute, TransitAlert, TrackerStop } from './types';
import { loadCatalogRoutes } from './catalog-data';
import type { TransitTracker } from './use-transit';

export interface RouteTypeMeta {
  label: string;
  emoji: string;
  icon: RemixiconComponentType;
}

export const ROUTE_TYPE_META: Record<string, RouteTypeMeta> = {
  SUBWAY: { label: 'Metro', emoji: '🚇', icon: RiSubwayLine },
  TRAM: { label: 'Tram', emoji: '🚋', icon: RiTrainLine },
  BUS: { label: 'Bus', emoji: '🚌', icon: RiBusLine },
  TROLLEYBUS: { label: 'Trolleybus', emoji: '🚎', icon: RiBus2Line },
  RAIL: { label: 'HÉV', emoji: '🚆', icon: RiTrainLine },
  SUBURBAN_RAILWAY: { label: 'HÉV', emoji: '🚆', icon: RiTrainLine },
  FERRY: { label: 'Ferry', emoji: '⛴️', icon: RiShip2Line },
  COACH: { label: 'Coach', emoji: '🚌', icon: RiBusLine },
};

export function routeTypeMeta(type: string | null | undefined): RouteTypeMeta {
  return ROUTE_TYPE_META[type || ''] || { label: type || 'Transit', emoji: '🚉', icon: RiTrainLine };
}

const TYPE_PRIORITY = ['SUBWAY', 'TRAM', 'TROLLEYBUS', 'BUS', 'RAIL', 'FERRY'];

/** Sort routes so metro/tram come first, then alphabetically-ish by short name. */
export function sortRoutes(routes: FutarRoute[]): FutarRoute[] {
  return [...routes].sort((a, b) => {
    const pa = TYPE_PRIORITY.indexOf(a.type);
    const pb = TYPE_PRIORITY.indexOf(b.type);
    if (pa !== pb) return (pa === -1 ? 99 : pa) - (pb === -1 ? 99 : pb);
    const na = parseFloat(a.shortName) || 0;
    const nb = parseFloat(b.shortName) || 0;
    if (na !== nb) return na - nb;
    return a.shortName.localeCompare(b.shortName);
  });
}

export interface CombinedRouteGroup {
  name: string;
  compactName: string;
  routeIds: string[];
  shortNames: string[];
}

export const COMBINED_ROUTE_GROUPS: CombinedRouteGroup[] = [
  {
    name: '1 / 1A',
    compactName: '1/1A',
    routeIds: ['BKK_3010', 'BKK_3011'],
    shortNames: ['1', '1A', '1/1A', '1 / 1A'],
  },
  {
    name: '4 / 6',
    compactName: '4/6',
    routeIds: ['BKK_3040', 'BKK_3060', 'BKK_3046'],
    shortNames: ['4', '6', '4-6', '4/6', '4 / 6'],
  },
  {
    name: '28 / 28A',
    compactName: '28/28A',
    routeIds: ['BKK_3280', 'BKK_3281'],
    shortNames: ['28', '28A', '28/28A', '28 / 28A'],
  },
  {
    name: '37 / 37A',
    compactName: '37/37A',
    routeIds: ['BKK_3370', 'BKK_3371'],
    shortNames: ['37', '37A', '37/37A', '37 / 37A'],
  },
  {
    name: 'H5',
    compactName: 'H5',
    routeIds: ['BKK_H5', 'volan_H5', 'volan_H5A', 'volan_H5E'],
    shortNames: ['H5', 'H5A', 'H5E'],
  },
];

export function findCombinedGroup(routeId?: string | null, shortName?: string | null): CombinedRouteGroup | undefined {
  if (routeId) {
    const byId = COMBINED_ROUTE_GROUPS.find((g) => g.routeIds.includes(routeId));
    if (byId) return byId;
  }
  if (shortName) {
    const norm = shortName.trim().toUpperCase();
    const byName = COMBINED_ROUTE_GROUPS.find((g) => g.shortNames.includes(norm));
    if (byName) return byName;
  }
  return undefined;
}

export function getCombinedRouteIds(routeId?: string | null): string[] | null {
  const g = findCombinedGroup(routeId);
  return g ? g.routeIds : null;
}

/**
 * Route matching logic. Combines paired lines:
 * - Tram 1 & 1A (BKK_3010 & BKK_3011)
 * - Tram 37 & 37A (BKK_3370 & BKK_3371)
 * - Tram 28 & 28A (BKK_3280 & BKK_3281)
 * - Tram 4 & 6 (BKK_3040 & BKK_3060)
 * while keeping all other lines strictly isolated.
 */
export function isRouteMatch(
  tripRouteId: string | undefined,
  targetRouteId: string,
  tripShortName?: string,
  targetShortName?: string
): boolean {
  if (tripShortName && targetShortName) {
    const gTrip = findCombinedGroup(undefined, tripShortName);
    const gTarget = findCombinedGroup(undefined, targetShortName);
    if (gTrip && gTarget && gTrip.name === gTarget.name) {
      return true;
    }
  }
  if (!tripRouteId) return false;
  const group = findCombinedGroup(targetRouteId);
  if (group) {
    return group.routeIds.includes(tripRouteId);
  }
  return tripRouteId === targetRouteId;
}

export function formatRouteDisplayName(shortName: string, routeId?: string, compact = false): string {
  const group = findCombinedGroup(routeId, shortName);
  if (group) {
    return compact ? group.compactName : group.name;
  }
  return shortName;
}

/**
 * Extract destination part from a headsign that may contain via arrows
 * e.g. "Salgótarjáni utca ► Sörgyár" -> "Sörgyár"
 */
export function extractHeadsignDestination(headsign: string | null | undefined): string {
  if (!headsign) return '';
  const trimmed = headsign.trim();
  const parts = trimmed.split(/\s*(?:►|>|->)\s*/);
  return parts[parts.length - 1] || trimmed;
}

function normalizeHeadsignForMatch(s: string): string {
  return normalizeSearchText(s)
    .replace(/\([^)]*\)/g, '')
    .replace(/\b(m|h|pu|vasutallomas|allomas)\b/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Robust headsign matching for live transit arrivals:
 * - Empty target matches all directions
 * - Checks exact match against stop-level headsign and trip canonical terminus
 * - Resolves via indicators ("Salgótarjáni utca ► Sörgyár" matches "Sörgyár")
 * - Tolerates station suffixes (M, H) and parenthesized station notes
 */
export function isHeadsignMatch(
  targetHeadsign: string | null | undefined,
  stopHeadsign: string | null | undefined,
  tripHeadsign?: string | null
): boolean {
  if (!targetHeadsign || !targetHeadsign.trim()) return true;

  const targetRaw = targetHeadsign.trim().toLowerCase();
  const stopRaw = (stopHeadsign || '').trim().toLowerCase();
  const tripRaw = (tripHeadsign || '').trim().toLowerCase();

  // 1. Direct raw string match
  if (stopRaw && stopRaw === targetRaw) return true;
  if (tripRaw && tripRaw === targetRaw) return true;

  // 2. Destination extraction match (after ► / > / ->)
  const targetDest = extractHeadsignDestination(targetHeadsign).toLowerCase();
  const stopDest = extractHeadsignDestination(stopHeadsign).toLowerCase();
  const tripDest = extractHeadsignDestination(tripHeadsign).toLowerCase();

  if (targetDest && stopDest && stopDest === targetDest) return true;
  if (targetDest && tripDest && tripDest === targetDest) return true;
  if (targetRaw && stopDest && stopDest === targetRaw) return true;
  if (targetRaw && tripDest && tripDest === targetRaw) return true;

  // 3. Normalized / clean match (ignoring diacritics, parentheses, and station suffixes)
  const normTarget = normalizeHeadsignForMatch(targetDest || targetHeadsign);
  const normStop = normalizeHeadsignForMatch(stopDest || stopHeadsign || '');
  const normTrip = normalizeHeadsignForMatch(tripDest || tripHeadsign || '');

  if (normTarget) {
    if (normStop && (normStop === normTarget || normStop.includes(normTarget) || normTarget.includes(normStop))) {
      return true;
    }
    if (normTrip && (normTrip === normTarget || normTrip.includes(normTarget) || normTarget.includes(normTrip))) {
      return true;
    }
  }

  return false;
}

/**
 * Extract the upcoming arrivals for one route from a stop's Futár response.
 * When `headsign` is set (a specific direction), only vehicles heading that
 * way are kept; empty/undefined means all directions.
 * For paired lines (37/37A, 28/28A, 4/6), trips from both lines are combined.
 */
export function parseStopArrivals(
  data: FutarArrivalsResponse | undefined,
  routeId: string,
  headsign?: string | null
): Arrival[] {
  const stopTimes = data?.data?.entry?.stopTimes || [];
  const trips = data?.data?.references?.trips || {};
  const routes = data?.data?.references?.routes || {};
  const now = Date.now();

  return stopTimes
    .filter((st) => !st.canceled && isRouteMatch(trips[st.tripId || '']?.routeId, routeId))
    .map((st) => {
      const trip = trips[st.tripId || ''];
      const tripRouteId = trip?.routeId;
      const route = tripRouteId ? routes[tripRouteId] : undefined;
      const predicted = st.predictedArrivalTime ?? st.predictedDepartureTime;
      const scheduled = st.arrivalTime ?? st.departureTime;
      const fallbackShortName =
        tripRouteId === 'BKK_3371'
          ? '37A'
          : tripRouteId === 'BKK_3370'
            ? '37'
            : tripRouteId === 'BKK_3281'
              ? '28A'
              : tripRouteId === 'BKK_3280'
                ? '28'
                : tripRouteId === 'BKK_3011'
                  ? '1A'
                  : tripRouteId === 'BKK_3010'
                    ? '1'
                    : tripRouteId === 'BKK_3040'
                      ? '4'
                      : tripRouteId === 'BKK_3060'
                        ? '6'
                        : undefined;
      const rawHeadsign = st.stopHeadsign || trip?.tripHeadsign || '';
      return {
        arrivalMs: ((predicted ?? scheduled ?? 0) > 0 ? (predicted ?? scheduled ?? 0) : 0) * 1000,
        headSign: rawHeadsign,
        tripHeadsign: trip?.tripHeadsign || '',
        isPredicted: !!predicted,
        tripId: st.tripId || '',
        routeId: tripRouteId,
        routeShortName: route?.shortName || fallbackShortName,
      };
    })
    // Keep vehicles that are due now (60s grace) or still upcoming.
    .filter((a) => a.arrivalMs > 0 && a.arrivalMs >= now - 60_000)
    // Narrow to the chosen direction when one is set.
    .filter((a) => isHeadsignMatch(headsign, a.headSign, a.tripHeadsign))
    .sort((a, b) => a.arrivalMs - b.arrivalMs);
}

/**
 * Group arrivals by terminus (headsign), preserving time order within each
 * group. Shared by the transit card and the dashboard tile so both render the
 * exact same per-direction panels.
 */
export function groupArrivalsByHeadsign(arrivals: Arrival[]): Array<{ name: string; list: Arrival[] }> {
  const map = new Map<string, Arrival[]>();
  for (const a of arrivals) {
    const key = a.tripHeadsign || a.headSign || 'Unknown';
    const list = map.get(key);
    if (list) list.push(a);
    else map.set(key, [a]);
  }
  return [...map.entries()].map(([name, list]) => ({ name, list }));
}

/** The `count` departures that follow `current` in the same direction (terminus),
 *  in time order. Empty when there is no current arrival. */
export function followingArrivals(arrivals: Arrival[], current: Arrival | null, count = 2): Arrival[] {
  if (!current) return [];
  const key = current.tripHeadsign || current.headSign || 'Unknown';
  return arrivals
    .filter(
      (a) =>
        a.tripId !== current.tripId &&
        a.arrivalMs > current.arrivalMs &&
        (a.tripHeadsign || a.headSign || 'Unknown') === key
    )
    .slice(0, count);
}

/** Lowercase + strip Hungarian diacritics so "szell kalman" matches "Széll
 *  Kálmán tér" and "deak" matches "Deák Ferenc tér". */
export function normalizeSearchText(s: string): string {
  return s
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '');
}

/** Crude HTML-to-text: drop tags and decode the common entities. The alert
 *  descriptions come back as `<ul><li>…</li></ul>` markup from the API. */
export function stripHtml(html: string): string {
  return html
    .replace(/<br\s*\/?>/gi, ' ')
    .replace(/<\/?[^>]+>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/g, "'")
    .replace(/&#(\d+);/g, (_, code) => String.fromCharCode(Number(code)))
    .replace(/\s+/g, ' ')
    // A closing tag before sentence punctuation leaves a stray space ("223E .").
    .replace(/ ([.,!?;:])/g, '$1')
    .trim();
}

/** Pick the app-language text from a translations block (en first, hu second,
 *  then whatever default exists). */
function pickText(t: { translations?: Record<string, string>; someTranslation?: string } | undefined): string {
  if (!t) return '';
  return t.translations?.en || t.translations?.hu || t.someTranslation || '';
}

/** Identity of the tracked route/stop, used to scope alerts. */
export interface AlertContext {
  routeId: string;
  routeShortName: string;
  routeType: string;
  stopId: string;
}

/** Resolves a route id to its short name (references.routes, then extra sources). */
export type RouteShortNameResolver = (routeId: string) => string | undefined;

let catalogRoutesPromise: Promise<Map<string, { shortName: string; type: string }>> | null = null;

/**
 * Lazily-loaded route catalog (id → short name + type), memoized per session.
 * Reads the same runtime JSON the add-tracker uses; only fetched on first use.
 */
export function getCatalogRoutes(): Promise<Map<string, { shortName: string; type: string }>> {
  if (!catalogRoutesPromise) {
    catalogRoutesPromise = loadCatalogRoutes()
      .then((routes) => {
        const map = new Map<string, { shortName: string; type: string }>();
        for (const r of routes) map.set(r.id, { shortName: r.shortName, type: r.type });
        return map;
      })
      .catch((err: unknown) => {
        catalogRoutesPromise = null;
        throw err;
      });
  }
  return catalogRoutesPromise;
}

/**
 * Active disruption alerts for a tracker, from `references.alerts` (the fork's
 * `entry.alertIds` is always empty). An alert is relevant when it is attached
 * to the tracked route (exact id, or short-name + route-type match via the
 * response's routes plus an optional extra resolver), attached to the tracked
 * stop (or its parent station), or agency-wide (no route/stop scope, sorted
 * last). The validity window (`start <= now <= end`, open-ended when `end` is
 * absent) drops expired alerts. Headers/descriptions are picked in app
 * language (en → hu) and stripped.
 */

export type RouteTypeResolver = (routeId: string) => string | undefined;

export function parseAlerts(
  data: FutarArrivalsResponse | undefined,
  ctx: AlertContext,
  now = Date.now(),
  extraResolver?: RouteShortNameResolver,
  extraTypeResolver?: RouteTypeResolver
): TransitAlert[] {
  const refs = data?.data?.references;
  const alerts = refs?.alerts ?? {};
  const routes = refs?.routes ?? {};
  const stops = refs?.stops ?? {};
  const entryStopId = data?.data?.entry?.stopId;
  const nowSec = now / 1000;

  const shortNameOf = (routeId: string): string | undefined =>
    routes[routeId]?.shortName ?? extraResolver?.(routeId);

  const routeMatch = (a: FutarAlert): boolean => {
    const ids = a.routeIds ?? a.routes?.map((r) => r.routeId).filter((x): x is string => Boolean(x)) ?? [];
    if (ids.some((id) => isRouteMatch(id, ctx.routeId))) return true;
    const types = a.vehicleRouteTypes;
    return ids.some((id) => {
      const name = shortNameOf(id);
      if (!name) return false;
      const matchesName =
        name === ctx.routeShortName ||
        isRouteMatch(undefined, ctx.routeId, name, ctx.routeShortName);
      if (!matchesName) return false;
      if (types && types.length > 0 && !types.includes(ctx.routeType)) return false;
      return true;
    });
  };

  const stopMatch = (a: FutarAlert): boolean => {
    const ids = a.stopIds ?? [];
    if (ids.includes(ctx.stopId) || (entryStopId != null && ids.includes(entryStopId))) return true;
    const parent = stops[ctx.stopId]?.parentStationId;
    return parent != null && ids.includes(parent);
  };

  const agencyWide = (a: FutarAlert): boolean =>
    (a.routeIds ?? []).length === 0 && (a.stopIds ?? []).length === 0;

  // A stop- or agency-scoped alert must affect the tracked route's vehicle
  // type — otherwise it's about other modes at the same location (e.g. a
  // RAIL signaling delay at a shared intermodal station must not appear on a
  // bus tracker). Declared vehicleRouteTypes are honored directly; otherwise
  // resolve the affected route ids (response refs, then the extra type
  // resolver) — if every resolved route is a different mode, the alert
  // cannot be relevant to this tracker. Unresolvable alerts stay (treated as
  // generic / all modes).
  const vehicleTypeMatch = (a: FutarAlert): boolean => {
    const declared = a.vehicleRouteTypes;
    if (declared && declared.length > 0) return declared.includes(ctx.routeType);
    const ids =
      a.routeIds ?? a.routes?.map((r) => r.routeId).filter((x): x is string => Boolean(x)) ?? [];
    if (ids.length === 0) return true;
    const affectedTypes = new Map<string, boolean>();
    for (const id of ids) {
      const type = routes[id]?.type ?? extraTypeResolver?.(id);
      if (type) affectedTypes.set(type, true);
    }
    if (affectedTypes.size === 0) return true;
    return affectedTypes.has(ctx.routeType);
  };

  const out: TransitAlert[] = [];
  for (const a of Object.values(alerts)) {
    if (a.start != null && nowSec < a.start) continue;
    if (a.end != null && nowSec > a.end) continue;
    if (!routeMatch(a) && !(stopMatch(a) && vehicleTypeMatch(a)) && !(agencyWide(a) && vehicleTypeMatch(a))) continue;
    out.push({
      id: a.id,
      header: pickText(a.header) || `Service alert ${a.id}`,
      description: stripHtml(pickText(a.description)),
      url: pickText(a.url) || undefined,
      effectType: a.effectType ?? a.routes?.[0]?.effectType,
      routeIds: a.routeIds ?? a.routes?.map((r) => r.routeId).filter((x): x is string => Boolean(x)),
      stopIds: a.stopIds,
    });
  }
  // Most disruptive first: SEVERE-ish effect types beat generic info; then
  // header alphabetical.
  return out.sort((a, b) => {
    const pa = effectWeight(a.effectType);
    const pb = effectWeight(b.effectType);
    if (pa !== pb) return pa - pb;
    return (a.header || '').localeCompare(b.header || '');
  });
}

function effectWeight(effectType: string | undefined): number {
  switch (effectType) {
    case 'NO_SERVICE':
      return 0;
    case 'DETOUR':
    case 'MODIFIED_SERVICE':
    case 'REDUCED_SERVICE':
      return 1;
    case 'SIGNIFICANT_DELAYS':
      return 2;
    default:
      return 3;
  }
}

/** "3 MIN", "71 MIN", "120 MIN" — total-minute countdown text for departure
 *  cards. Hours fold into minutes so a long night-service wait reads as one
 *  number instead of a clipped "1H 11 MIN". */
export function formatCountdown(minutes: number): string {
  if (minutes <= 0) return 'NOW';
  return `${minutes} MIN`;
}

/** "2'", "73'", "120'" — prime-marked total minutes for the compact departure
 *  readouts (trips, dashboard tiles). Hours fold into minutes; no horizon. */
export function formatPrimeCountdown(minutes: number): string {
  if (minutes <= 0) return 'NOW';
  return `${minutes}'`;
}

/** Glance horizon for the dashboard's compact departure rows: a departure
 *  farther out than this is too far away to earn a row at a stop. */
export const COMPACT_COUNTDOWN_MAX_MINUTES = 99;

/** "2'", "70'" — compact minutes for the dashboard's glance tiles. Hours are
 *  folded into minutes up to the horizon; past it (or with no parsed arrival)
 *  returns null so the caller can drop the row entirely. */
export function formatCompactCountdown(minutes: number): string | null {
  if (minutes > COMPACT_COUNTDOWN_MAX_MINUTES) return null;
  return formatPrimeCountdown(minutes);
}

/** Arrivals snapshots older than this (or an offline browser) are treated as
 *  stale: the countdown freezes at its snapshot value instead of decaying into
 *  a "NOW" the data can no longer stand behind. 3× the 20s arrivals poll. */
export const ARRIVALS_STALE_MS = 60_000;

/** Whether an arrivals snapshot is too old to stand behind (or we're offline). */
export function isArrivalsStale(
  dataUpdatedAt: number | undefined,
  now: number,
  offline: boolean
): boolean {
  if (offline) return true;
  const at = dataUpdatedAt ?? 0;
  return at > 0 && now - at > ARRIVALS_STALE_MS;
}

/** The clock a countdown should be evaluated against: real now, or the
 *  snapshot time when the snapshot is stale — freezing the readout at the last
 *  moment the data was still true. */
export function arrivalsStaleNow(
  dataUpdatedAt: number | undefined,
  now: number,
  offline: boolean
): number {
  const at = dataUpdatedAt ?? 0;
  return isArrivalsStale(dataUpdatedAt, now, offline) ? Math.min(now, at) : now;
}

export const MAX_TRACKER_STOPS = 4;
export const MAX_FAVORITE_TRACKERS = 5;

/**
 * Normalizes a TransitTracker's stops into a TrackerStop array.
 * If the tracker has a valid `stops` field (array or JSON string), returns it.
 * Otherwise falls back to the legacy single-stop columns (`stop_id`, `stop_name`, `headsign`).
 */
export function getTrackerStops(tracker: TransitTracker): TrackerStop[] {
  if (Array.isArray(tracker.stops) && tracker.stops.length > 0) {
    return (tracker.stops as unknown as TrackerStop[]).slice(0, MAX_TRACKER_STOPS);
  }
  if (typeof tracker.stops === 'string') {
    try {
      const parsed = JSON.parse(tracker.stops);
      if (Array.isArray(parsed) && parsed.length > 0) return (parsed as TrackerStop[]).slice(0, MAX_TRACKER_STOPS);
    } catch {
      // ignore JSON parse error, fall back to legacy fields
    }
  }
  return [
    {
      stop_id: tracker.stop_id,
      stop_name: tracker.stop_name,
      headsign: tracker.headsign || '',
    },
  ];
}

export function addOrUpdateTrackerStop(stops: TrackerStop[], newStop: TrackerStop): TrackerStop[] {
  const existingIndex = stops.findIndex((s) => s.stop_id === newStop.stop_id);
  if (existingIndex >= 0) {
    const next = [...stops];
    next[existingIndex] = newStop;
    return next;
  }
  if (stops.length >= MAX_TRACKER_STOPS) {
    return stops;
  }
  return [...stops, newStop];
}

export function removeTrackerStop(stops: TrackerStop[], stopId: string): TrackerStop[] {
  return stops.filter((s) => s.stop_id !== stopId);
}

export function splitRouteTitle(title?: string | null): string[] {
  if (!title) return [];
  return title.split(/\s*\|\s*/).filter(Boolean);
}

/**
 * Normalizes a platform stop ID into its station-level area ID.
 * - Already normalized area IDs (BKK_CS..., AREA_CS...) are kept unchanged.
 * - Surface stop platforms (BKK_F...) map to station complexes (BKK_CSF...).
 * - 6-digit platform stops (BKK_\d{6}) map to station areas (BKK_CS\d{6}).
 * - 8-digit railway/HÉV platforms (BKK_\d{8}) map to their 5-digit station code (BKK_\d{5}),
 *   because HÉV stations in Futár are 5-digit IDs (e.g. BKK_09001) without a CS prefix.
 * - 5-digit station IDs (e.g. BKK_09001, BKK_19720) are already station IDs and stay unchanged.
 */
export function normalizeStopIdToStation(stopId: string | null | undefined): string {
  if (!stopId) return '';
  if (stopId.startsWith('BKK_CS') || stopId.startsWith('AREA_CS')) return stopId;
  if (stopId.startsWith('BKK_F')) {
    return stopId.replace(/^BKK_F/, 'BKK_CSF');
  }
  if (/^BKK_\d{6}$/.test(stopId)) {
    return stopId.replace(/^BKK_/, 'BKK_CS');
  }
  if (/^BKK_\d{8}$/.test(stopId)) {
    return stopId.slice(0, 9); // e.g. BKK_09001188 -> BKK_09001
  }
  return stopId;
}

