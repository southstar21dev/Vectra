import type { LatLon } from '@/features/transit/route-geometry';

const FOOT_ROUTER_ENDPOINT = 'https://routing.openstreetmap.de/routed-foot/route/v1/foot';

export interface WalkingRoute {
  distanceMeters: number;
  durationMinutes: number;
}

/** Build the public OpenStreetMap foot-router URL for two coordinates. */
export function buildWalkingRouteUrl(from: LatLon, to: LatLon): string {
  const coordinates = `${from.lon},${from.lat};${to.lon},${to.lat}`;
  const url = new URL(`${FOOT_ROUTER_ENDPOINT}/${coordinates}`);
  url.searchParams.set('overview', 'false');
  return url.toString();
}

/** Parse the first route returned by the OSRM-compatible foot router. */
export function parseWalkingRouteResponse(data: unknown): WalkingRoute {
  const payload = data as {
    code?: string;
    routes?: Array<{ distance?: number; duration?: number }>;
  };
  const route = payload?.routes?.[0];
  if (payload?.code !== 'Ok' || !route || !Number.isFinite(route.distance) || !Number.isFinite(route.duration)) {
    throw new Error('Walking route unavailable');
  }
  return {
    distanceMeters: Math.max(1, Math.round(route.distance!)),
    durationMinutes: Math.max(1, Math.ceil(route.duration! / 60)),
  };
}

/** Fetch a pedestrian route, timing out so GPS selection can fall back cleanly. */
export async function fetchWalkingRoute(from: LatLon, to: LatLon): Promise<WalkingRoute> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 8_000);
  try {
    const response = await fetch(buildWalkingRouteUrl(from, to), { signal: controller.signal });
    if (!response.ok) throw new Error(`Walking route failed (HTTP ${response.status})`);
    return parseWalkingRouteResponse(await response.json());
  } finally {
    window.clearTimeout(timeout);
  }
}
