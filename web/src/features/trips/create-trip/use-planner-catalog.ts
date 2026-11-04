import { useCallback, useEffect, useState } from 'react';
import { getCatalogRouteMap, getCatalogStops, getRouteVariants } from '@/features/transit/nearby-stops';
import type { CatalogRoute, CatalogRouteVariant, CatalogStop } from '@/features/transit/catalog-types';
import { buildRouteList, type DisplayRoute } from './constants';

export interface PlannerCatalog {
  /** Display list (combined groups collapsed: 4/6, 28/28A…). */
  routes: DisplayRoute[];
  /** Full catalog, one entry per route id — badge metadata per member. */
  catalogRoutes: CatalogRoute[];
  stops: CatalogStop[];
  /** Static direction index: route id → variants (headsign + stop sequence). */
  index: Record<string, CatalogRouteVariant[]>;
}

type MemberMeta = { shortName: string; color: string; textColor: string } | null;

/**
 * Everything the planner needs from the generated transit catalog, fetched
 * once when the dialog first opens (runtime JSON; service-worker cached on the
 * installed app). `error` is set when any part fails, with `retry` to reload.
 */
export function usePlannerCatalog(open: boolean) {
  const [catalog, setCatalog] = useState<PlannerCatalog | null>(null);
  const [error, setError] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!open || catalog) return;
    let alive = true;
    Promise.all([getCatalogRouteMap(), getCatalogStops(), getRouteVariants()])
      .then(([routeMap, stops, index]) => {
        if (!alive) return;
        const catalogRoutes = Array.from(routeMap.values());
        setCatalog({ routes: buildRouteList(catalogRoutes), catalogRoutes, stops, index });
        setError(false);
      })
      .catch(() => {
        if (alive) setError(true);
      });
    return () => {
      alive = false;
    };
  }, [open, catalog, attempt]);

  /** Badge metadata for one member route id (28, 28A… rather than the group label). */
  const memberMeta = useCallback(
    (id: string): MemberMeta => {
      const full = catalog?.catalogRoutes.find((r) => r.id === id);
      if (full) return { shortName: full.shortName, color: full.color, textColor: full.textColor };
      const listed = catalog?.routes.find((r) => r.id === id);
      return listed ? { shortName: listed.shortName, color: listed.color, textColor: listed.textColor } : null;
    },
    [catalog]
  );

  const retry = useCallback(() => {
    setError(false);
    setAttempt((n) => n + 1);
  }, []);

  return { catalog, error, retry, memberMeta };
}
