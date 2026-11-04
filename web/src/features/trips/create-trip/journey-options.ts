import type { CatalogRouteVariant, CatalogStop } from '@/features/transit/catalog-types';
import {
  estimateJourneyMinutes,
  estimateRouteMinutes,
  findJourneyChains,
  routesThrough,
  suggestRoutes,
  type RouteMeta,
  type RouteSuggestion,
} from '../route-suggest';
import { normalizeStopName } from '../route-variants';

/** One ride inside a proposed journey: the routes (either/or) and its stops. */
export interface JourneyOptionLeg {
  routes: RouteSuggestion[];
  from: CatalogStop;
  to: CatalogStop;
}

/** A complete door-to-door proposal the planner offers for a From → To pair. */
export interface JourneyOption {
  key: string;
  minutes: number;
  legs: JourneyOptionLeg[];
}

interface JourneyOptionsInput {
  routes: RouteMeta[];
  index: Record<string, CatalogRouteVariant[]>;
  stops: CatalogStop[];
  from: CatalogStop;
  to: CatalogStop;
  /** Cap on the number of proposals (direct + transfer combined). */
  limit?: number;
}

/** Catalog stop for a (normalized) stop name; the pair's own endpoints win so
 *  the journey keeps the exact stops the user picked. */
export function stopResolver(stops: CatalogStop[], from: CatalogStop, to: CatalogStop) {
  const byName = new Map<string, CatalogStop>();
  for (const stop of stops) {
    const key = normalizeStopName(stop.name);
    if (!byName.has(key)) byName.set(key, stop);
  }
  return (name: string): CatalogStop | null => {
    const key = normalizeStopName(name);
    if (key === normalizeStopName(from.name)) return from;
    if (key === normalizeStopName(to.name)) return to;
    return byName.get(key) ?? null;
  };
}

/**
 * Every sensible way to ride From → To, ranked by estimated door-to-door
 * time: each direct route (or combined group) is its own one-ride option,
 * and multi-ride journeys come from the network search over every route that
 * calls at the starting stop. Fewer transfers only break near-ties.
 */
export function buildJourneyOptions({ routes, index, stops, from, to, limit = 6 }: JourneyOptionsInput): JourneyOption[] {
  if (normalizeStopName(from.name) === normalizeStopName(to.name)) return [];
  const resolve = stopResolver(stops, from, to);
  const options: JourneyOption[] = [];
  const seen = new Set<string>();

  for (const route of suggestRoutes(routes, index, from.name, to.name)) {
    const key = route.routeId;
    if (seen.has(key)) continue;
    seen.add(key);
    options.push({
      key,
      minutes: estimateRouteMinutes(index, route, from.name, to.name) + 5,
      legs: [{ routes: [route], from, to }],
    });
  }

  for (const first of routesThrough(routes, index, from.name)) {
    for (const chain of findJourneyChains(routes, index, first, from.name, to.name, 4, 3)) {
      if (chain.legs.length < 2) continue;
      const legs: JourneyOptionLeg[] = [];
      let complete = true;
      for (const leg of chain.legs) {
        const legFrom = resolve(leg.fromStopName);
        const legTo = resolve(leg.toStopName);
        if (!legFrom || !legTo) {
          complete = false;
          break;
        }
        legs.push({ routes: [{ ...leg.route, headsign: leg.headsign || leg.route.headsign }], from: legFrom, to: legTo });
      }
      if (!complete) continue;
      const key = chain.legs.map((leg) => `${leg.route.routeId}:${normalizeStopName(leg.toStopName)}`).join('|');
      if (seen.has(key)) continue;
      seen.add(key);
      options.push({ key, minutes: estimateJourneyMinutes(index, chain), legs });
    }
  }

  return mergeAlternatives(options)
    .sort((a, b) => a.minutes - b.minutes || a.legs.length - b.legs.length)
    .slice(0, limit);
}

/**
 * Journeys that ride the same stops in the same time and differ only in the
 * line on a stretch (59 / 59A / 59B → M2 → M3) are one journey with either/or
 * lines, not three rows. Each leg's routes are unioned; every one of them
 * serves that leg's stop pair, so any combination is a valid ride.
 */
function mergeAlternatives(options: JourneyOption[]): JourneyOption[] {
  const byShape = new Map<string, JourneyOption>();
  for (const option of options) {
    const shape = `${option.minutes}|${option.legs.map((l) => `${l.from.id}>${l.to.id}`).join('|')}`;
    const existing = byShape.get(shape);
    if (!existing) {
      byShape.set(shape, { ...option, legs: option.legs.map((l) => ({ ...l, routes: [...l.routes] })) });
      continue;
    }
    option.legs.forEach((leg, i) => {
      for (const route of leg.routes) {
        if (!existing.legs[i].routes.some((r) => r.routeId === route.routeId)) existing.legs[i].routes.push(route);
      }
    });
    existing.key = `${existing.key}+${option.key}`;
  }
  return [...byShape.values()];
}

/**
 * The stops a route reaches after `from`, in riding order — the "where do you
 * get off?" list when the user picks a line that does not go all the way.
 * Union over every variant (and combined-group member) that calls at `from`.
 */
export function stopsAfter(
  index: Record<string, CatalogRouteVariant[]>,
  routeIds: string[],
  from: CatalogStop,
  stops: CatalogStop[]
): CatalogStop[] {
  const origin = normalizeStopName(from.name);
  const names: string[] = [];
  const seen = new Set<string>([origin]);
  for (const id of routeIds) {
    for (const variant of index[id] ?? []) {
      const start = variant.s.indexOf(origin);
      if (start < 0) continue;
      for (const name of variant.s.slice(start + 1)) {
        if (seen.has(name)) continue;
        seen.add(name);
        names.push(name);
      }
    }
  }
  const resolve = stopResolver(stops, from, from);
  return names.map(resolve).filter((stop): stop is CatalogStop => !!stop);
}
