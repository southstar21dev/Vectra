import { describe, expect, it } from 'vitest';
import { buildWalkingRouteUrl, parseWalkingRouteResponse } from './walking-route';

describe('walking-route', () => {
  it('parses routed distance and duration', () => {
    expect(
      parseWalkingRouteResponse({
        code: 'Ok',
        routes: [{ distance: 842.4, duration: 611 }],
      })
    ).toEqual({ distanceMeters: 842, durationMinutes: 11 });
  });

  it('rejects an empty routing response', () => {
    expect(() => parseWalkingRouteResponse({ code: 'NoRoute', routes: [] })).toThrow('Walking route unavailable');
  });

  it('builds a foot route URL in lon,lat order', () => {
    const url = buildWalkingRouteUrl({ lat: 47.5, lon: 19.05 }, { lat: 47.51, lon: 19.06 });
    expect(url).toContain('/19.05,47.5;19.06,47.51');
    expect(url).toContain('overview=false');
  });
});
