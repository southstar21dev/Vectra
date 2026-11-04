import { COMBINED_ROUTE_GROUPS } from '@/features/transit/transit-utils';
import type { CatalogRoute } from '@/features/transit/catalog-types';
import type { CatalogStop } from '@/features/transit/catalog-types';
import type { TripPlan, TripDirection, TripAddress } from '../types';

export const ROUTE_TYPE_FILTERS = ['ALL', 'SUBWAY', 'TRAM', 'TROLLEYBUS', 'BUS', 'RAIL', 'FERRY'] as const;

export const DIRECTION_OPTIONS = [
  { value: null, label: 'None', selected: 'bg-surface-overlay text-primary border-subtle' },
  { value: 'to_uni', label: 'To uni', selected: 'bg-tint-blue/20 text-tint-blue border-tint-blue/30' },
  { value: 'to_home', label: 'Home', selected: 'bg-tint-emerald/20 text-tint-emerald border-tint-emerald/30' },
] as const satisfies ReadonlyArray<{ value: TripDirection | null; label: string; selected: string }>;

/** HÉV rows can carry RAIL or the legacy SUBURBAN_RAILWAY type — one HÉV pill covers both. */
export function typeMatchesFilter(type: string, filter: string): boolean {
  if (filter === 'ALL') return true;
  if (filter === 'RAIL') return type === 'RAIL' || type === 'SUBURBAN_RAILWAY';
  return type === filter;
}

export interface CreateTripDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  editingTrip?: TripPlan;
}

/** One end of a journey: the stop it rides from/to, plus the address the
 *  stop was snapped from (null when the user picked the stop itself). */
export interface Endpoint {
  stop: CatalogStop;
  address: TripAddress | null;
}

/** What the endpoint is called in the UI: the address when there is one. */
export function endpointName(endpoint: Endpoint): string {
  return endpoint.address?.name || endpoint.stop.name;
}

export interface DisplayRoute {
  id: string;
  shortName: string;
  displayName: string;
  type: string;
  color: string;
  textColor: string;
}

export function buildRouteList(catalog: CatalogRoute[]): DisplayRoute[] {
  const usedIds = new Set<string>();
  const routes: DisplayRoute[] = [];
  for (const group of COMBINED_ROUTE_GROUPS) {
    const match = catalog.find((r) => group.routeIds.includes(r.id));
    if (!match) continue;
    for (const id of group.routeIds) usedIds.add(id);
    routes.push({ id: match.id, shortName: group.compactName, displayName: group.name, type: match.type, color: match.color, textColor: match.textColor });
  }
  for (const r of catalog) {
    if (usedIds.has(r.id)) continue;
    routes.push({ id: r.id, shortName: r.shortName, displayName: r.description || r.shortName, type: r.type, color: r.color, textColor: r.textColor });
  }
  return routes;
}
