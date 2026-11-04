/**
 * Shapes of the daily-refreshed BKK catalog files in public/catalog/.
 * scripts/build-catalog.mjs is the only writer; keep these in sync with it.
 */

/** A BKK route (line) — public/catalog/routes.json. */
export interface CatalogRoute {
  id: string;
  shortName: string;
  description: string;
  type: string;
  color: string;
  textColor: string;
}

/** A stop area (station level; platforms fold in) — public/catalog/stops.json. */
export interface CatalogStop {
  id: string;
  name: string;
  lat: number;
  lon: number;
}

/**
 * One route direction — public/catalog/route-variants.json.
 * h = headsign, s = ordered normalized stop names.
 */
export interface CatalogRouteVariant {
  h: string;
  s: string[];
}
