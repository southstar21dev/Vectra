import { describe, expect, it } from 'vitest';
import { queryKeys } from './query-keys';

describe('query keys', () => {
  it('keeps the exact literal cache contracts', () => {
    expect(queryKeys.auth.root).toEqual(['auth']);
    expect(queryKeys.auth.session).toEqual(['auth', 'session']);
    expect(queryKeys.profile).toEqual(['profile']);
    expect(queryKeys.passkeys).toEqual(['passkeys']);
    expect(queryKeys.schedule).toEqual(['schedule']);
    expect(queryKeys.scheduleItems('u1')).toEqual(['schedule-items', 'u1']);
    expect(queryKeys.scheduleItems(null)).toEqual(['schedule-items', 'anonymous']);
    expect(queryKeys.scheduleItems()).toEqual(['schedule-items', 'anonymous']);
    expect(queryKeys.courseTrackers).toEqual(['course_trackers']);
    expect(queryKeys.transitTrackers).toEqual(['transit-trackers']);
    expect(queryKeys.transitCatalogStops).toEqual(['transit-catalog-stops']);
    expect(queryKeys.transitCatalogRoutes).toEqual(['transit-catalog-routes']);
    expect(queryKeys.transitArrivals('s1')).toEqual(['transit-arrivals', 's1']);
    expect(queryKeys.transitArrivals('s1', 30)).toEqual(['transit-arrivals', 's1', 30]);
    expect(queryKeys.transitArrivals('s1', 0)).toEqual(['transit-arrivals', 's1']);
    expect(queryKeys.transitArrivalsBatch('a,b')).toEqual(['transit-arrivals-batch', 'a,b']);
    expect(queryKeys.transitVehicles('r1')).toEqual(['transit-vehicles', 'r1']);
    expect(queryKeys.transitTripDetails('t1')).toEqual(['transit-trip-details', 't1']);
    expect(queryKeys.transitRouteDetails('r1')).toEqual(['transit-route-details', 'r1']);
    expect(queryKeys.tripPlans).toEqual(['trip-plans']);
    expect(queryKeys.weather(47.5, 19.05)).toEqual(['weather', 47.5, 19.05]);
    expect(queryKeys.radar).toEqual(['rainviewer-radar-data']);
    expect(queryKeys.pushSubscriptions).toEqual(['push_subscriptions']);
  });
});
