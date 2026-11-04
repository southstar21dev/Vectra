import type { CatalogRoute, CatalogRouteVariant } from '@/features/transit/catalog-types';
import { COMBINED_ROUTE_GROUPS } from '@/features/transit/transit-utils';
import { normalizeStopName } from './route-variants';

/** A route that serves both stops, ready to become a trip leg. */
export interface RouteSuggestion {
  /** Primary route id (first member for combined groups like 4/6). */
  routeId: string;
  /** Every member route id (combined groups) — used for arrivals merging. */
  routeIds: string[];
  shortName: string;
  displayName: string;
  type: string;
  color: string;
  textColor: string;
  /** Direction headsign for departure → destination. */
  headsign: string;
  /** Every direction calling at the stop (transfer rows) — display-only. */
  headsigns?: string[];
}

/** Structural route metadata the suggestion engine needs — catalog or dialog. */
export type RouteMeta = Pick<CatalogRoute, 'id' | 'shortName' | 'type' | 'color' | 'textColor'> & {
  description?: string;
};

/** Routes that exist in the catalog but should not be offered as everyday
 * journey suggestions. R29Y is a paid heritage service; 9xx buses are BKK
 * night services and should not be proposed as transfer legs. */
export function isExcludedFromSuggestions(route: Pick<RouteMeta, 'id' | 'shortName' | 'type'>): boolean {
  if (route.id === 'BKK_R3299' || route.shortName.toUpperCase() === 'R29Y') return true;
  return route.type === 'BUS' && /^9\d{2}[A-Z]?$/.test(route.shortName.trim().toUpperCase());
}

/** First direction headsign among the routes whose variant serves dep before dest. */
function firstHeadsign(
  routeIds: string[],
  index: Record<string, CatalogRouteVariant[]>,
  departureName: string,
  destinationName: string
): string | null {
  const dep = normalizeStopName(departureName);
  const dest = normalizeStopName(destinationName);
  if (!dep || !dest) return null;
  for (const id of routeIds) {
    for (const v of index[id] ?? []) {
      const d = v.s.indexOf(dep);
      const t = v.s.indexOf(dest);
      if (d >= 0 && t > d) return v.h;
    }
  }
  return null;
}

/** Expand a single route id into its combined-group members (identity when not grouped). */
export function groupForRoute(routeId: string): string[] {
  const group = COMBINED_ROUTE_GROUPS.find((g) => g.routeIds.includes(routeId));
  return group ? group.routeIds : [routeId];
}

/** True when any route in the group serves the departure stop before the destination. */
export function servesPair(
  index: Record<string, CatalogRouteVariant[]>,
  routeIds: string[],
  departureName: string,
  destinationName: string
): boolean {
  return firstHeadsign(routeIds, index, departureName, destinationName) !== null;
}

/** Headsign of the direction that serves departure before destination, else null. */
export function headsignFor(
  index: Record<string, CatalogRouteVariant[]>,
  routeIds: string[],
  departureName: string,
  destinationName: string
): string | null {
  return firstHeadsign(routeIds, index, departureName, destinationName);
}

/**
 * Per-suggestion direction headsign for a departure → destination pair.
 * `null` means that route does not serve the pair in that direction — the
 * caller must not build the leg with it. Used to store each alternative
 * route's own headsign on an either/or leg.
 */
export function headsignsForSuggestions(
  index: Record<string, CatalogRouteVariant[]>,
  suggestions: RouteSuggestion[],
  departureName: string,
  destinationName: string
): Array<string | null> {
  return suggestions.map((s) => firstHeadsign(s.routeIds, index, departureName, destinationName));
}

const TYPE_PRIORITY: Record<string, number> = {
  SUBWAY: 0,
  TRAM: 1,
  TROLLEYBUS: 2,
  BUS: 3,
  RAIL: 4,
  FERRY: 5,
};

const TYPE_ORDER = ['SUBWAY', 'TRAM', 'TROLLEYBUS', 'BUS', 'RAIL', 'FERRY'];

/**
 * Find every route that directly serves the departure stop before the
 * destination stop — entirely offline from the static direction index, so
 * suggestions are instant and correct regardless of service hours. Combined
 * lines (4/6, 28/28A, 37/37A, H5) merge into one suggestion.
 */
export function suggestRoutes(
  routes: RouteMeta[],
  index: Record<string, CatalogRouteVariant[]>,
  departureName: string,
  destinationName: string
): RouteSuggestion[] {
  const dep = normalizeStopName(departureName);
  const dest = normalizeStopName(destinationName);
  if (!dep || !dest) return [];

  const byId = new Map(routes.map((r) => [r.id, r]));
  const out: RouteSuggestion[] = [];
  const seen = new Set<string>();

  for (const group of COMBINED_ROUTE_GROUPS) {
    const headsign = firstHeadsign(group.routeIds, index, dep, dest);
    if (headsign !== null) {
      const meta = group.routeIds.map((id) => byId.get(id)).find(Boolean);
      if (meta) {
        out.push({
          routeId: meta.id,
          routeIds: group.routeIds,
          shortName: group.compactName,
          displayName: group.name,
          type: meta.type,
          color: meta.color,
          textColor: meta.textColor,
          headsign,
        });
      }
      for (const id of group.routeIds) seen.add(id);
    }
  }

  for (const r of routes) {
    if (seen.has(r.id) || isExcludedFromSuggestions(r)) continue;
    const headsign = firstHeadsign([r.id], index, dep, dest);
    if (headsign !== null) {
      out.push({
        routeId: r.id,
        routeIds: [r.id],
        shortName: r.shortName,
        displayName: r.description || r.shortName,
        type: r.type,
        color: r.color,
        textColor: r.textColor,
        headsign,
      });
    }
  }

  return out.sort((a, b) => {
    const pa = TYPE_PRIORITY[a.type] ?? TYPE_ORDER.length;
    const pb = TYPE_PRIORITY[b.type] ?? TYPE_ORDER.length;
    if (pa !== pb) return pa - pb;
    const na = parseFloat(a.shortName) || 0;
    const nb = parseFloat(b.shortName) || 0;
    if (na !== nb) return na - nb;
    return a.shortName.localeCompare(b.shortName);
  });
}

/**
 * A suggested one-transfer chain: take the first-leg route to a transfer
 * stop, then a route from there to the destination (e.g. 28/28A →
 * II. János Pál pápa tér → M4 → Fővám tér).
 */
export interface JourneyChainLeg {
  route: RouteSuggestion;
  fromStopName: string;
  toStopName: string;
  headsign: string;
}

export interface JourneyChain {
  legs: JourneyChainLeg[];
}

const MAX_CONSECUTIVE_SUBWAY_LEGS = 2;

const APPROX_MINUTES_PER_STOP: Record<string, number> = {
  SUBWAY: 1.5,
  TRAM: 2,
  TROLLEYBUS: 2.5,
  BUS: 2.5,
  RAIL: 3,
  FERRY: 4,
};
const APPROX_BOARDING_MINUTES = 5;
const APPROX_TRANSFER_MINUTES = 4;

function stopCountForSegment(
  index: Record<string, CatalogRouteVariant[]>,
  routeIds: string[],
  fromName: string,
  toName: string
): number {
  const from = normalizeStopName(fromName);
  const to = normalizeStopName(toName);
  let shortest = Infinity;
  for (const id of routeIds) {
    for (const variant of index[id] ?? []) {
      const fromIndex = variant.s.indexOf(from);
      const toIndex = variant.s.indexOf(to);
      if (fromIndex >= 0 && toIndex > fromIndex) shortest = Math.min(shortest, toIndex - fromIndex);
    }
  }
  return Number.isFinite(shortest) ? shortest : 1;
}

/** Approximate transit duration for one route between two stops, excluding waits. */
export function estimateRouteMinutes(
  index: Record<string, CatalogRouteVariant[]>,
  route: Pick<RouteSuggestion, 'routeIds' | 'type'>,
  fromName: string,
  toName: string
): number {
  const stops = stopCountForSegment(index, route.routeIds, fromName, toName);
  const perStop = APPROX_MINUTES_PER_STOP[route.type] ?? 2.5;
  return Math.max(2, Math.round(stops * perStop));
}

/** Approximate end-to-end duration for a complete suggested journey. */
export function estimateJourneyMinutes(
  index: Record<string, CatalogRouteVariant[]>,
  chain: JourneyChain
): number {
  const riding = chain.legs.reduce(
    (total, leg) => total + estimateRouteMinutes(index, leg.route, leg.fromStopName, leg.toStopName),
    0
  );
  return riding + APPROX_BOARDING_MINUTES + Math.max(0, chain.legs.length - 1) * APPROX_TRANSFER_MINUTES;
}

function hasTooManyConsecutiveSubwayLegs(legs: JourneyChainLeg[]): boolean {
  let count = 0;
  for (let i = legs.length - 1; i >= 0 && legs[i].route.type === 'SUBWAY'; i--) count++;
  return count > MAX_CONSECUTIVE_SUBWAY_LEGS;
}

export interface TransferChain {
  /** The first-leg route (or combined group), as suggested from departure. */
  first: RouteSuggestion;
  /** Normalized transfer stop name where the second leg can be caught. */
  transferStopName: string;
  /** Direction headsign of the first leg toward the transfer stop. */
  firstHeadsign: string;
  /** Routes from the transfer stop to the destination (the second leg's
   *  either/or options, each with its pair-specific headsign). */
  secondLeg: RouteSuggestion[];
}

/**
 * For each first-leg route, find its BEST transfer point on the way to the
 * destination: the first stop after departure where SOME route connects to
 * the destination. Ranking prefers the second leg's mode (subway > tram >
 * bus) so the M4 transfer wins over a bus one even if the bus stop is
 * earlier; ties break by how early the transfer stop is.
 */
/**
 * Find a complete journey after the user chooses the first route. The search
 * follows route variants in their travel direction and can discover several
 * transfers, so the user only chooses the line they want to start with.
 */
export function findJourneyChains(
  routes: RouteMeta[],
  index: Record<string, CatalogRouteVariant[]>,
  first: RouteSuggestion,
  departureName: string,
  destinationName: string,
  maxLegs = 4,
  limit = 8
): JourneyChain[] {
  const departure = normalizeStopName(departureName);
  const destination = normalizeStopName(destinationName);
  if (!departure || !destination) return [];

  type State = { stop: string; legs: JourneyChainLeg[]; used: Set<string> };
  const queue: State[] = [];
  const priorityQueue: State[] = [];
  const seen = new Set<string>();
  const results: JourneyChain[] = [];
  const subwayStops = new Set<string>();
  for (const route of routes) {
    if (route.type !== 'SUBWAY') continue;
    for (const variant of index[route.id] ?? []) {
      for (const stop of variant.s) subwayStops.add(stop);
    }
  }
  const routesAtStopCache = new Map<string, RouteSuggestion[]>();
  const routesAtStop = (stop: string) => {
    const cached = routesAtStopCache.get(stop);
    if (cached) return cached;
    const value = routesThrough(routes, index, stop);
    routesAtStopCache.set(stop, value);
    return value;
  };
  const resultKeys = new Set<string>();

  const addDownstreamStates = (route: RouteSuggestion, from: string, base: JourneyChainLeg[], used: Set<string>) => {
    for (const id of route.routeIds) {
      for (const variant of index[id] ?? []) {
        const start = variant.s.indexOf(from);
        if (start < 0) continue;
        const destinationIndex = variant.s.indexOf(destination, start + 1);
        if (destinationIndex >= 0) {
          // If this direction reaches the final destination, use that as the
          // terminal stop. Never branch through another stop on the same
          // vehicle first — that is how impossible-looking chains such as
          // M3 → M4 → Szent Gellért tér → 49 → Fővám were created even
          // though M4 already serves Fővám directly.
          const leg: JourneyChainLeg = {
            route,
            fromStopName: from,
            toStopName: destination,
            headsign: variant.h,
          };
          const legs = [...base, leg];
          if (!hasTooManyConsecutiveSubwayLegs(legs)) {
            const key = legs.map((item) => `${item.route.routeId}:${item.fromStopName}>${item.toStopName}`).join('|');
            if (!resultKeys.has(key)) {
              resultKeys.add(key);
              results.push({ legs });
            }
          }
          continue;
        }

        for (let i = start + 1; i < variant.s.length; i++) {
          const stop = variant.s[i];
          const leg: JourneyChainLeg = { route, fromStopName: from, toStopName: stop, headsign: variant.h };
          const legs = [...base, leg];
          // Avoid presenting chains with three metro legs in a row. Two
          // consecutive metro legs (one metro transfer) remain valid.
          if (hasTooManyConsecutiveSubwayLegs(legs)) continue;
          if (legs.length < maxLegs) {
            const key = `${stop}|${[...used, route.routeId].sort().join(',')}`;
            if (!seen.has(key)) {
              seen.add(key);
              const nextState = { stop, legs, used: new Set([...used, route.routeId]) };
              if (subwayStops.has(stop)) priorityQueue.push(nextState);
              else queue.push(nextState);
            }
          }
        }
      }
    }
  };

  addDownstreamStates(first, departure, [], new Set());
  let explored = 0;
  const hasSubwayTransferChain = () => results.some((chain) => chain.legs.filter((leg) => leg.route.type === 'SUBWAY').length >= 2);
  while ((priorityQueue.length > 0 || queue.length > 0) && (results.length < limit || !hasSubwayTransferChain()) && explored < 1500) {
    const state = priorityQueue.shift() ?? queue.shift()!;
    explored++;
    if (state.legs.length >= maxLegs) continue;
    for (const candidate of routesAtStop(state.stop)) {
      if (state.used.has(candidate.routeId)) continue;
      addDownstreamStates(candidate, state.stop, state.legs, state.used);
      if (results.length >= limit) break;
    }
  }
  // Sort by the same approximate end-to-end duration shown in the UI.
  // Transfer count only breaks ties, so a faster two-transfer option can
  // correctly outrank a slower direct-but-longer route.
  results.sort((a, b) => estimateJourneyMinutes(index, a) - estimateJourneyMinutes(index, b) || a.legs.length - b.legs.length);
  return results.slice(0, limit);
}

export function findJourneyChain(
  routes: RouteMeta[],
  index: Record<string, CatalogRouteVariant[]>,
  first: RouteSuggestion,
  departureName: string,
  destinationName: string,
  maxLegs = 4
): JourneyChain | null {
  return findJourneyChains(routes, index, first, departureName, destinationName, maxLegs, 1)[0] ?? null;
}

export function findTransferChains(
  routes: RouteMeta[],
  index: Record<string, CatalogRouteVariant[]>,
  firstLegs: RouteSuggestion[],
  departureName: string,
  destinationName: string
): TransferChain[] {
  const dep = normalizeStopName(departureName);
  const dest = normalizeStopName(destinationName);
  if (!dep || !dest) return [];

  const out: TransferChain[] = [];
  for (const first of firstLegs) {
    let best: TransferChain | null = null;
    // [second-leg mode priority, stop index] — lower is better.
    let bestScore: [number, number] = [Infinity, Infinity];
    for (const id of first.routeIds) {
      for (const v of index[id] ?? []) {
        const d = v.s.indexOf(dep);
        if (d < 0) continue;
        for (let i = d + 1; i < v.s.length; i++) {
          const stop = v.s[i];
          if (stop === dest) continue; // direct — handled by suggestRoutes
          const secondLeg = suggestRoutes(routes, index, stop, destinationName);
          if (secondLeg.length === 0) continue;
          const type = Math.min(...secondLeg.map((s) => TYPE_PRIORITY[s.type] ?? TYPE_ORDER.length));
          if (type < bestScore[0] || (type === bestScore[0] && i < bestScore[1])) {
            bestScore = [type, i];
            best = { first, transferStopName: stop, firstHeadsign: v.h, secondLeg };
          }
          if (type === 0) break; // a subway second leg can't be beaten on this variant
        }
      }
    }
    if (best) out.push(best);
  }
  return out;
}

/**
 * Routes that call at a stop (any direction) — the "transfer" answer when no
 * direct route exists. Returns one entry per route/combined group, with the
 * headsign of the first direction calling at the stop, ordered by the same
 * type priority as suggestRoutes.
 */
export function routesThrough(
  routes: RouteMeta[],
  index: Record<string, CatalogRouteVariant[]>,
  stopName: string
): RouteSuggestion[] {
  const target = normalizeStopName(stopName);
  if (!target) return [];

  const byId = new Map(routes.map((r) => [r.id, r]));
  const out: RouteSuggestion[] = [];
  const seen = new Set<string>();

  for (const group of COMBINED_ROUTE_GROUPS) {
    const headsigns = headsignsAtStop(group.routeIds, index, target);
    if (headsigns.length > 0) {
      const meta = group.routeIds.map((id) => byId.get(id)).find(Boolean);
      if (meta) {
        out.push({
          routeId: meta.id,
          routeIds: group.routeIds,
          shortName: group.compactName,
          displayName: group.name,
          type: meta.type,
          color: meta.color,
          textColor: meta.textColor,
          headsign: headsigns[0],
          headsigns,
        });
      }
      for (const id of group.routeIds) seen.add(id);
    }
  }

  for (const r of routes) {
    if (seen.has(r.id) || isExcludedFromSuggestions(r)) continue;
    const headsigns = headsignsAtStop([r.id], index, target);
    if (headsigns.length > 0) {
      out.push({
        routeId: r.id,
        routeIds: [r.id],
        shortName: r.shortName,
        displayName: r.description || r.shortName,
        type: r.type,
        color: r.color,
        textColor: r.textColor,
        headsign: headsigns[0],
        headsigns,
      });
    }
  }

  return out.sort((a, b) => {
    const pa = TYPE_PRIORITY[a.type] ?? TYPE_ORDER.length;
    const pb = TYPE_PRIORITY[b.type] ?? TYPE_ORDER.length;
    if (pa !== pb) return pa - pb;
    const na = parseFloat(a.shortName) || 0;
    const nb = parseFloat(b.shortName) || 0;
    if (na !== nb) return na - nb;
    return a.shortName.localeCompare(b.shortName);
  });
}

/** Every distinct direction calling at the stop, in index order. */
function headsignsAtStop(
  routeIds: string[],
  index: Record<string, CatalogRouteVariant[]>,
  stopName: string
): string[] {
  const stop = normalizeStopName(stopName);
  if (!stop) return [];
  const seen = new Set<string>();
  const out: string[] = [];
  for (const id of routeIds) {
    for (const v of index[id] ?? []) {
      if (v.s.includes(stop) && !seen.has(v.h)) {
        seen.add(v.h);
        out.push(v.h);
      }
    }
  }
  return out;
}