/**
 * Central TanStack Query key factory. The literal arrays are cache contracts —
 * changing one invalidates every user's cache, so the `query-keys.test.ts`
 * snapshots them deliberately.
 */
export const queryKeys = {
  auth: {
    /** Prefix key — invalidating it also invalidates the session query. */
    root: ['auth'] as const,
    session: ['auth', 'session'] as const,
  },
  profile: ['profile'] as const,
  passkeys: ['passkeys'] as const,
  schedule: ['schedule'] as const,
  scheduleItems: (userId?: string | null) => ['schedule-items', userId ?? 'anonymous'] as const,
  courseTrackers: ['course_trackers'] as const,
  transitTrackers: ['transit-trackers'] as const,
  transitCatalogStops: ['transit-catalog-stops'] as const,
  transitCatalogRoutes: ['transit-catalog-routes'] as const,
  // Truthiness (not `undefined`) mirrors the original conditional key: a 0
  // limit must produce the two-element key, exactly as before.
  transitArrivals: (stopId: string | null, limit?: number) =>
    limit
      ? (['transit-arrivals', stopId, limit] as const)
      : (['transit-arrivals', stopId] as const),
  transitArrivalsBatch: (batchKey: string) => ['transit-arrivals-batch', batchKey] as const,
  transitVehicles: (routeId: string | null) => ['transit-vehicles', routeId] as const,
  transitTripDetails: (tripId: string | null) => ['transit-trip-details', tripId] as const,
  transitRouteDetails: (routeId: string | null) => ['transit-route-details', routeId] as const,
  tripPlans: ['trip-plans'] as const,
  weather: (latitude: number, longitude: number) => ['weather', latitude, longitude] as const,
  radar: ['rainviewer-radar-data'] as const,
  pushSubscriptions: ['push_subscriptions'] as const,
} as const;
