import type { Arrival, FutarArrivalsResponse } from './types';
import { haversineMeters, type LatLon } from './route-geometry';
import type { CatalogRoute, CatalogRouteVariant, CatalogStop } from './catalog-types';
import { loadCatalogRouteMap, loadCatalogRouteVariants, loadCatalogStops } from './catalog-data';

/** A catalog stop with its straight-line distance from the anchor (meters). */
export interface NearbyStop extends CatalogStop {
  distanceMeters: number;
}

/** One route's departure board at a stop. */
export interface NearbyDeparture {
  routeId: string;
  shortName: string;
  routeType: string;
  color: string;
  textColor: string;
  arrivals: Arrival[];
}

/** Where the Near Me sheet anchors, after the outside-Hungary check. */
export interface MapAnchor {
  lat: number;
  lon: number;
  label: 'you' | 'budapest-center';
  outsideHungary: boolean;
}

/** Budapest center — the fallback anchor when the user is outside Hungary. */
export const BUDAPEST_CENTER: LatLon = { lat: 47.4979, lon: 19.0402 };

/**
 * Approximate Hungary border as a lat/lon polygon (ray-casting test). Coarse
 * (~10 km accuracy) but deliberately accurate along the NORTH border, where
 * the critical cases live: Vienna (48.21, 16.37) and Bratislava (48.15, 17.11)
 * must classify as OUTSIDE while Győr / Miskolc / the Danube-bend towns stay
 * inside. Vertices trace the border clockwise from Rajka (NW).
 */
const HUNGARY_POLYGON: LatLon[] = [
  { lat: 47.99, lon: 17.2 }, // Rajka (NW)
  { lat: 47.87, lon: 17.27 }, // Mosonmagyaróvár
  { lat: 47.68, lon: 16.59 }, // Sopron
  { lat: 46.87, lon: 16.1 }, // Felsőszölnök (westernmost)
  { lat: 46.43, lon: 16.73 }, // Letenye (SW bend)
  { lat: 45.96, lon: 17.46 }, // Barcs
  { lat: 45.95, lon: 18.95 }, // Hercegszántó (southernmost)
  { lat: 46.25, lon: 20.15 }, // Szeged
  { lat: 46.65, lon: 21.28 }, // Gyula (SE)
  { lat: 48.16, lon: 22.55 }, // Beregsurány (east)
  { lat: 48.41, lon: 22.17 }, // Záhony (NE)
  { lat: 48.54, lon: 21.46 }, // Füzér (northernmost)
  { lat: 48.4, lon: 21.66 }, // Sátoraljaújhely
  { lat: 48.53, lon: 21.1 }, // border north of Szendrő
  { lat: 48.47, lon: 20.7 }, // Aggtelek area
  { lat: 48.43, lon: 20.5 }, // Bánréve area
  { lat: 48.4, lon: 20.3 }, // Ózd area
  { lat: 48.33, lon: 20.1 }, // Somoskőújfalu
  { lat: 48.25, lon: 19.7 }, // Ipolytarnóc
  { lat: 48.17, lon: 19.5 }, // Szécsény
  { lat: 48.08, lon: 19.3 }, // Balassagyarmat
  { lat: 48.02, lon: 19.2 }, // Diósjenő area
  { lat: 47.88, lon: 19.0 }, // Ipolydamásd area
  { lat: 47.82, lon: 18.87 }, // Szob
  { lat: 47.79, lon: 18.74 }, // Esztergom
  { lat: 47.74, lon: 18.11 }, // Komárom
];

/** Ray-casting point-in-polygon: true when `point` is inside HUNGARY_POLYGON. */
export function isInHungary(lat: number, lon: number): boolean {
  let inside = false;
  for (let i = 0, j = HUNGARY_POLYGON.length - 1; i < HUNGARY_POLYGON.length; j = i++) {
    const a = HUNGARY_POLYGON[i];
    const b = HUNGARY_POLYGON[j];
    const crosses =
      (a.lon > lon) !== (b.lon > lon) &&
      lat < ((b.lat - a.lat) * (lon - a.lon)) / (b.lon - a.lon) + a.lat;
    if (crosses) inside = !inside;
  }
  return inside;
}

/** Anchor for the Near Me sheet: the user's location in Hungary, else Budapest. */
export function resolveAnchor(lat: number, lon: number): MapAnchor {
  if (isInHungary(lat, lon)) return { lat, lon, label: 'you', outsideHungary: false };
  return { ...BUDAPEST_CENTER, label: 'budapest-center', outsideHungary: true };
}

/** Lazily-loaded full BKK stop catalog (runtime JSON; memoized per session). */
export const getCatalogStops: () => Promise<CatalogStop[]> = loadCatalogStops;

/**
 * Lazily-loaded per-route direction index (ordered stop names per variant)
 * powering the trip planner's offline route suggestions. ~700KB raw, so it is
 * only fetched when the route picker is actually opened.
 */
export const getRouteVariants: () => Promise<Record<string, CatalogRouteVariant[]>> =
  loadCatalogRouteVariants;

/** Lazily-loaded route catalog keyed by id, full metadata for badges. */
export const getCatalogRouteMap: () => Promise<Map<string, CatalogRoute>> = loadCatalogRouteMap;

/** Nearest N catalog stops within the radius, sorted by distance. */
export function findNearbyStops(
  stops: CatalogStop[],
  lat: number,
  lon: number,
  opts: { radiusMeters?: number; limit?: number } = {}
): NearbyStop[] {
  const { radiusMeters = 800, limit = 6 } = opts;
  const anchor: LatLon = { lat, lon };
  const out: NearbyStop[] = [];
  for (const s of stops) {
    const distanceMeters = haversineMeters(anchor, s);
    if (distanceMeters <= radiusMeters) out.push({ ...s, distanceMeters });
  }
  out.sort((a, b) => a.distanceMeters - b.distanceMeters);
  return out.slice(0, limit);
}

/** A geographic bounding box (WGS84). */
export interface LatLonBounds {
  north: number;
  south: number;
  east: number;
  west: number;
}

/** A coordinate as returned by MapLibre's `unproject` (uses the `lng` key). */
export interface UnprojectedPoint {
  lat: number;
  lng: number;
}

/** Viewport derived from the map rectangle the sheet does not cover. */
export interface VisibleViewport {
  lat: number;
  lon: number;
  zoom: number;
  bounds: LatLonBounds;
}

/**
 * Viewport for the map area left uncovered by the departures sheet: bounds are
 * the min/max of the uncovered rect's unprojected corners, and the center is
 * the uncovered center (never the full-canvas center, which is covered).
 */
export function viewportFromUncoveredRect(
  topLeft: UnprojectedPoint,
  bottomRight: UnprojectedPoint,
  center: UnprojectedPoint,
  zoom: number
): VisibleViewport {
  const rawBounds: LatLonBounds = {
    north: Math.max(topLeft.lat, bottomRight.lat),
    south: Math.min(topLeft.lat, bottomRight.lat),
    east: Math.max(topLeft.lng, bottomRight.lng),
    west: Math.min(topLeft.lng, bottomRight.lng),
  };
  return {
    lat: center.lat,
    lon: center.lng,
    zoom,
    // At high zoom the uncovered rect can be narrower than the stop spacing,
    // which empties the dots and the feed; pad to a minimum span so nearby
    // stops stay in view. stopsInView, mapStops, the feed and the
    // visible/polled counts all derive from these padded bounds.
    bounds: padBoundsToMinSpan(rawBounds, MIN_VIEW_SPAN_METERS),
  };
}

/** Mean meters per degree of latitude (enough for a viewport floor). */
const METERS_PER_DEGREE_LAT = 111_320;

/** High-zoom floor for the uncovered-rect bounds (see viewportFromUncoveredRect). */
export const MIN_VIEW_SPAN_METERS = 500;

/**
 * Expand `bounds` around its center until each axis spans at least
 * `minSpanMeters`; longitude is scaled by cos(center latitude). Already-large
 * axes and the center stay put. Invalid input or a non-positive span is
 * returned unchanged.
 */
export function padBoundsToMinSpan(
  bounds: LatLonBounds,
  minSpanMeters: number
): LatLonBounds {
  const { north, south, east, west } = bounds;
  if (
    !Number.isFinite(minSpanMeters) ||
    minSpanMeters <= 0 ||
    !Number.isFinite(north) ||
    !Number.isFinite(south) ||
    !Number.isFinite(east) ||
    !Number.isFinite(west)
  ) {
    return bounds;
  }
  const centerLat = (north + south) / 2;
  const cosLat = Math.cos((centerLat * Math.PI) / 180);
  const metersPerDegreeLon = METERS_PER_DEGREE_LAT * Math.max(cosLat, 1e-6);
  const latSpanMeters = (north - south) * METERS_PER_DEGREE_LAT;
  const lonSpanMeters = (east - west) * metersPerDegreeLon;
  const padLat = Math.max(0, minSpanMeters - latSpanMeters) / 2 / METERS_PER_DEGREE_LAT;
  const padLon = Math.max(0, minSpanMeters - lonSpanMeters) / 2 / metersPerDegreeLon;
  return {
    north: north + padLat,
    south: south - padLat,
    east: east + padLon,
    west: west - padLon,
  };
}

/** Clamp the sheet's reported coverage to 0..80% of the map canvas height. */
export function clampBottomInset(insetPx: number, canvasHeightPx: number): number {
  if (!Number.isFinite(insetPx) || !Number.isFinite(canvasHeightPx) || canvasHeightPx <= 0) {
    return 0;
  }
  return Math.max(0, Math.min(insetPx, canvasHeightPx * 0.8));
}

/** Catalog stops inside a bounding box, nearest to the box center first. */
export function findStopsInBounds(
  stops: CatalogStop[],
  bounds: LatLonBounds,
  opts: { limit?: number } = {}
): NearbyStop[] {
  const { limit = 60 } = opts;
  const center: LatLon = {
    lat: (bounds.north + bounds.south) / 2,
    lon: (bounds.east + bounds.west) / 2,
  };
  const out: NearbyStop[] = [];
  for (const s of stops) {
    if (s.lat < bounds.south || s.lat > bounds.north) continue;
    if (s.lon < bounds.west || s.lon > bounds.east) continue;
    out.push({ ...s, distanceMeters: haversineMeters(center, s) });
  }
  out.sort((a, b) => a.distanceMeters - b.distanceMeters);
  return out.slice(0, limit);
}

/**
 * A catalog station-dot stand-in drawn on the explorer map. Platform dots
 * replace their station's single dot when the arrivals response resolves the
 * station's physical stops; `stationId` keeps them pointing at the station.
 */
export interface ExplorerMapStop extends NearbyStop {
  stationId?: string;
  isPlatform?: boolean;
}

/** Two physical stops closer than this are treated as the same map dot. */
export const PLATFORM_DOT_DEDUPE_METERS = 5;

/**
 * Merge the viewport's catalog dots with the physical stops each polled
 * station reports in its arrivals response (`references.stops`). A station with
 * at least one other in-viewport physical stop is replaced by those platform
 * dots (deduped by id and by sub-5 m coordinates); every other station keeps
 * its catalog dot. No network calls — the references ride along with the
 * arrivals already fetched for the feed.
 */
export function buildExplorerMapStops(
  stops: NearbyStop[],
  arrivalsByStopId: ReadonlyMap<string, FutarArrivalsResponse | undefined>,
  bounds: LatLonBounds,
  dedupeMeters = PLATFORM_DOT_DEDUPE_METERS
): ExplorerMapStop[] {
  const out: ExplorerMapStop[] = [];

  for (const stop of stops) {
    const refs = arrivalsByStopId.get(stop.id)?.data?.references?.stops;
    if (!refs) {
      out.push(stop);
      continue;
    }

    const platforms: ExplorerMapStop[] = [];
    const seenIds = new Set<string>();
    for (const [key, ref] of Object.entries(refs)) {
      if (!ref) continue;
      const id = ref.id || key;
      if (!id || id === stop.id || seenIds.has(id)) continue;
      if (!Number.isFinite(ref.lat) || !Number.isFinite(ref.lon)) continue;
      if (ref.lat === 0 && ref.lon === 0) continue;
      if (ref.lat < bounds.south || ref.lat > bounds.north) continue;
      if (ref.lon < bounds.west || ref.lon > bounds.east) continue;
      const stacked = platforms.some(
        (p) => haversineMeters(p, ref) <= dedupeMeters
      );
      if (stacked) continue;
      seenIds.add(id);
      platforms.push({
        id,
        name: ref.name || stop.name,
        lat: ref.lat,
        lon: ref.lon,
        distanceMeters: stop.distanceMeters,
        stationId: stop.id,
        isPlatform: true,
      });
    }

    if (platforms.length > 0) out.push(...platforms);
    else out.push(stop);
  }

  return out;
}

/** Route-board order used when grouping boards at an individual stop. */
const BOARD_TYPE_PRIORITY = ['SUBWAY', 'TRAM', 'TROLLEYBUS', 'BUS', 'RAIL', 'FERRY'];
/** Departure-sheet order: trams first, then metro, bus, trolley, etc. */
const RADAR_MODE_PRIORITY = ['TRAM', 'SUBWAY', 'BUS', 'TROLLEYBUS', 'RAIL', 'FERRY'];
const ROUTE_NAME_COLLATOR = new Intl.Collator(undefined, {
  numeric: true,
  sensitivity: 'base',
});

/**
 * Group a stop's arrivals into per-route departure boards. Route metadata
 * resolves from the catalog map, falling back to the response's
 * references.routes, then the raw routeId. Cancelled trips and trips without a
 * resolvable route are dropped; only due-now (60s grace) or upcoming arrivals
 * are kept, sorted by arrival time. Boards sort metro/tram first, then by
 * short name.
 */
export function parseNearbyBoards(
  data: FutarArrivalsResponse | undefined,
  catalogRoutes: Map<string, CatalogRoute> | null,
  now = Date.now()
): NearbyDeparture[] {
  const refs = data?.data?.references;
  const trips = refs?.trips ?? {};
  const refRoutes = refs?.routes ?? {};
  const refStops = refs?.stops ?? {};
  const groups = new Map<string, Arrival[]>();

  for (const st of data?.data?.entry?.stopTimes ?? []) {
    if (st.canceled) continue;
    const routeId = trips[st.tripId || '']?.routeId;
    if (!routeId) continue;
    const predicted = st.predictedArrivalTime ?? st.predictedDepartureTime;
    const scheduled = st.arrivalTime ?? st.departureTime;
    const arrivalMs = ((predicted ?? scheduled ?? 0) > 0 ? (predicted ?? scheduled ?? 0) : 0) * 1000;
    if (arrivalMs <= 0 || arrivalMs < now - 60_000) continue;
    const arrival: Arrival = {
      arrivalMs,
      headSign: st.stopHeadsign || '',
      isPredicted: !!predicted,
      tripId: st.tripId || '',
    };
    // The departure's physical stop rides along when the response carries it;
    // same-named platforms at one station can sit a block apart.
    const platform = st.stopId ? refStops[st.stopId] : undefined;
    if (
      platform &&
      Number.isFinite(platform.lat) &&
      Number.isFinite(platform.lon) &&
      !(platform.lat === 0 && platform.lon === 0)
    ) {
      arrival.platformStopId = platform.id || st.stopId;
      arrival.platformLat = platform.lat;
      arrival.platformLon = platform.lon;
    }
    const list = groups.get(routeId);
    if (list) list.push(arrival);
    else groups.set(routeId, [arrival]);
  }

  const rows: NearbyDeparture[] = [];
  for (const [routeId, arrivals] of groups) {
    const cat = catalogRoutes?.get(routeId);
    const ref = refRoutes[routeId];
    rows.push({
      routeId,
      shortName: cat?.shortName || ref?.shortName || routeId,
      routeType: cat?.type || ref?.type || '',
      color: cat?.color || ref?.color || '52525b',
      textColor: cat?.textColor || ref?.textColor || 'FFFFFF',
      arrivals: arrivals.sort((a, b) => a.arrivalMs - b.arrivalMs),
    });
  }

  return rows.sort((a, b) => {
    const pa = BOARD_TYPE_PRIORITY.indexOf(a.routeType);
    const pb = BOARD_TYPE_PRIORITY.indexOf(b.routeType);
    if (pa !== pb) return (pa === -1 ? 99 : pa) - (pb === -1 ? 99 : pb);
    return a.shortName.localeCompare(b.shortName);
  });
}

/** Nearby departure ETAs use a compact apostrophe minute marker. */
export function formatNearbyCountdown(minutes: number): string {
  if (minutes <= 0) return 'NOW';
  return `${minutes}'`;
}

/** "350 m" / "1.2 km" — compact walking-distance readout. */
export function formatDistanceMeters(meters: number): string {
  if (meters < 1000) return `${Math.round(meters)} m`;
  return `${(meters / 1000).toFixed(1)} km`;
}

/** Walking speed constant: ~1.2 m/s = 72 meters per minute. */
export const WALKING_METERS_PER_MINUTE = 72;

/** Calculate walking time in minutes based on distance in meters (minimum 1 min). */
export function calculateWalkMinutes(distanceMeters: number): number {
  if (distanceMeters <= 0) return 1;
  return Math.max(1, Math.ceil(distanceMeters / WALKING_METERS_PER_MINUTE));
}

/** Categorize feasibility of catching a departure based on arrival countdown vs walking time. */
export function calculateFeasibility(
  countdownMinutes: number,
  walkMinutes: number
): 'catchable' | 'hurry' | 'missed' {
  const delta = countdownMinutes - walkMinutes;
  if (delta >= 2) return 'catchable';
  if (delta >= 0) return 'hurry';
  return 'missed';
}

/** Flattened imminent departure card item for the Radar hero feed. */
export interface RadarDepartureItem {
  id: string;
  stopId: string;
  stopName: string;
  distanceMeters: number;
  walkMinutes: number;
  feasibility: 'catchable' | 'hurry' | 'missed';
  routeId: string;
  shortName: string;
  routeType: string;
  color: string;
  textColor: string;
  headsign: string;
  arrivalMs: number;
  countdownMinutes: number;
  isPredicted: boolean;
  /** The exact trip serving this departure, when the feed carries it. */
  tripId?: string;
  /** Physical stop the departure leaves from, when the feed carries it. */
  platformStopId?: string;
  platformLat?: number;
  platformLon?: number;
}

/** Filter departures by transit mode (SUBWAY, TRAM, BUS, TROLLEYBUS). Accepts
 *  a single mode for legacy callers or a list; an empty list / 'ALL' matches
 *  everything, and multiple modes match any of them. */
export function filterDeparturesByMode(
  departures: NearbyDeparture[],
  mode?: string | readonly string[]
): NearbyDeparture[] {
  const requested = typeof mode === 'string' ? (mode ? [mode] : []) : mode ?? [];
  const targets = new Set(
    requested.map((m) => m.toUpperCase()).filter((m) => m !== 'ALL')
  );
  if (targets.size === 0) return departures;
  return departures.filter((d) => targets.has(d.routeType.toUpperCase()));
}

/**
 * Strict line search matching:
 * If query contains a digit or is short (<= 3 chars, e.g. "M4", "4", "2B", "4-6"),
 * it strictly matches the line's shortName so that searching "M4" ONLY returns M4.
 * Non-line textual queries (e.g. "Keleti") fall back to destination and stop matching.
 */
export function matchesLineSearch(
  boardShortName: string,
  headsign: string,
  stopName: string,
  query: string
): boolean {
  const normRoute = boardShortName.trim().toLowerCase();
  const normQuery = query.trim().toLowerCase();
  if (!normQuery) return true;

  // 1. Exact or normalized route match (e.g. "m4" === "m4", "4-6" === "4-6", "2b" === "2b")
  if (normRoute === normQuery) return true;
  const strippedQuery = normQuery.replace(/^metro\s*/i, 'm').replace(/[-\s/]/g, '');
  const strippedRoute = normRoute.replace(/^metro\s*/i, 'm').replace(/[-\s/]/g, '');
  if (strippedRoute === strippedQuery) return true;

  // 2. If query is a line query (contains digits or is short <= 3 chars like "M4", "4", "2B"),
  // treat it strictly as a line search — do NOT match substring occurrences in headsigns or stops!
  const isLineQuery = /\d/.test(normQuery) || normQuery.length <= 3;
  if (isLineQuery) {
    return false;
  }

  // 3. For longer textual queries (e.g. "keleti", "astoria"), allow destination or stop name matching with accent insensitivity
  const cleanQuery = normQuery.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
  const cleanHeadsign = headsign.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');
  const cleanStop = stopName.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');
  return cleanHeadsign.includes(cleanQuery) || cleanStop.includes(cleanQuery);
}

/** Build aggregated, ranked list of imminent departures across candidate stops. */
export function buildRadarDepartures(params: {
  stops: NearbyStop[];
  stopDeparturesMap: Map<string, NearbyDeparture[]>;
  now?: number;
  modeFilter?: string | readonly string[];
  maxItems?: number;
  searchQuery?: string;
}): RadarDepartureItem[] {
  const { stops, stopDeparturesMap, now = Date.now(), modeFilter, maxItems = 24, searchQuery } = params;
  const items: RadarDepartureItem[] = [];
  const seenRouteCounts = new Map<string, number>();
  // A station can report the same trip once per platform; keep only the first.
  const seenTripKeys = new Set<string>();
  const query = searchQuery?.trim();

  for (const stop of stops) {
    const rawBoards = stopDeparturesMap.get(stop.id) ?? [];
    const boards = filterDeparturesByMode(rawBoards, modeFilter);
    const walkMinutes = calculateWalkMinutes(stop.distanceMeters);

    for (const board of boards) {
      for (const arrival of board.arrivals) {
        if (arrival.arrivalMs < now - 30_000) continue;

        const tripKey = `${stop.id}|${board.routeId}|${arrival.tripId}|${arrival.arrivalMs}`;
        if (arrival.tripId && seenTripKeys.has(tripKey)) continue;
        if (arrival.tripId) seenTripKeys.add(tripKey);

        if (query && !matchesLineSearch(board.shortName, arrival.headSign, stop.name, query)) {
          continue;
        }

        const directionKey = `${stop.id}|${board.routeId}|${arrival.headSign}`;
        const count = seenRouteCounts.get(directionKey) ?? 0;
        if (count >= (query ? 4 : 3)) continue;
        seenRouteCounts.set(directionKey, count + 1);

        const countdownMinutes = Math.max(0, Math.ceil((arrival.arrivalMs - now) / 60_000));
        // Keep the nearby board focused on departures that fit its compact
        // minute-based ETA layout. Longer-horizon services are omitted.
        if (countdownMinutes > 99) continue;
        const feasibility = calculateFeasibility(countdownMinutes, walkMinutes);

        items.push({
          id: `${stop.id}-${board.routeId}-${arrival.tripId || 'anon'}-${arrival.arrivalMs}`,
          stopId: stop.id,
          stopName: stop.name,
          distanceMeters: stop.distanceMeters,
          walkMinutes,
          feasibility,
          routeId: board.routeId,
          shortName: board.shortName,
          routeType: board.routeType,
          color: board.color,
          textColor: board.textColor,
          headsign: arrival.headSign,
          arrivalMs: arrival.arrivalMs,
          countdownMinutes,
          isPredicted: arrival.isPredicted,
          tripId: arrival.tripId || undefined,
          platformStopId: arrival.platformStopId,
          platformLat: arrival.platformLat,
          platformLon: arrival.platformLon,
        });
      }
    }
  }

  // Keep the board grouped by mode, then by route (28A together, followed by
  // 37A, etc.), with the earliest ETA first inside each route group.
  items.sort((a, b) => {
    const modeA = RADAR_MODE_PRIORITY.indexOf(a.routeType.toUpperCase());
    const modeB = RADAR_MODE_PRIORITY.indexOf(b.routeType.toUpperCase());
    const priorityA = modeA === -1 ? Number.MAX_SAFE_INTEGER : modeA;
    const priorityB = modeB === -1 ? Number.MAX_SAFE_INTEGER : modeB;
    if (priorityA !== priorityB) return priorityA - priorityB;

    const routeOrder = ROUTE_NAME_COLLATOR.compare(a.shortName, b.shortName);
    if (routeOrder !== 0) return routeOrder;
    if (a.routeId !== b.routeId) return a.routeId.localeCompare(b.routeId);
    return a.arrivalMs - b.arrivalMs;
  });

  return items.slice(0, maxItems);
}
