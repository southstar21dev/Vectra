import type { CatalogRouteVariant } from '@/features/transit/catalog-types';
import { headsignsForSuggestions, type RouteSuggestion } from './route-suggest';
import type { TripLeg, TripRouteOption } from './types';

export interface AlternativeLegInput {
  /** Direction index — null only before it loads (falls back to suggestion headsigns). */
  index: Record<string, CatalogRouteVariant[]> | null;
  /** One or more either/or alternative routes for the leg. */
  suggestions: RouteSuggestion[];
  departure: { id: string; name: string };
  destination: { id: string; name: string };
  /** Resolve a route id to its badge metadata (shortName/colors). */
  memberMeta: (routeId: string) => { shortName?: string; color?: string; textColor?: string } | null;
}

/**
 * Build one leg from one or more either/or routes. Each route entry stores
 * its OWN direction headsign (re-derived for the actual pair when the index
 * is available) so the timeline filters each route's departures correctly.
 * Returns null when a route does not serve the pair — the caller must not
 * add the leg.
 */
export function buildAlternativeLeg(input: AlternativeLegInput): TripLeg | null {
  const { index, suggestions, departure, destination, memberMeta } = input;
  if (suggestions.length === 0) return null;

  const hss =
    index != null
      ? headsignsForSuggestions(index, suggestions, departure.name, destination.name)
      : suggestions.map((s) => s.headsign);
  if (index != null && hss.some((h) => h === null || h === '')) return null;

  const routes: TripRouteOption[] = [];
  let displayName = '';
  for (let i = 0; i < suggestions.length; i++) {
    const s = suggestions[i];
    for (const id of s.routeIds) {
      const m = memberMeta(id);
      routes.push({
        routeId: id,
        routeShortName: m?.shortName ?? id,
        routeColor: m?.color ?? '52525b',
        routeTextColor: m?.textColor ?? 'FFFFFF',
        headsign: hss[i] ?? undefined,
      });
    }
    displayName = displayName ? `${displayName} / ${s.displayName}` : s.displayName;
  }

  const distinct = [...new Set(hss.filter((h): h is string => !!h))];
  return {
    routes,
    routeType: suggestions[0].type,
    routeLongName: displayName,
    departureStopId: departure.id,
    departureStopName: departure.name,
    destinationStopId: destination.id,
    destinationStopName: destination.name,
    headsign: distinct.join(' / '),
    waitMinutes: 5,
  };
}