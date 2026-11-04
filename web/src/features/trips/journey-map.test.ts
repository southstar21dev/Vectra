import { describe, it, expect, vi, beforeEach } from 'vitest';
import { buildJourneyGeometry, walkBetweenJourneyLegs, resolveMissingAddressCoords, type JourneyLeg } from './journey-map';
import * as geocode from './geocode';
import type { FutarRouteDetailsResponse, FutarStop } from '@/features/transit/types';
import type { LatLon } from '@/features/transit/route-geometry';

/** A straight north-south polyline, 1e-4° per vertex — predictable snapping. */
function line(n: number, startLat = 47.47): LatLon[] {
  return Array.from({ length: n }, (_, i) => ({ lat: startLat + i * 0.0001, lon: 19.05 }));
}

/** Google-polyline encode at 1e-5 (inverse of decodePolyline) so the builder
 *  decodes fixtures back to the exact vertices. */
function encode(line: LatLon[]): string {
  const enc = (v: number): string => {
    let x = v < 0 ? ~(v << 1) : v << 1;
    let out = '';
    while (x >= 0x20) {
      out += String.fromCharCode((0x20 | (x & 0x1f)) + 63);
      x >>= 5;
    }
    return out + String.fromCharCode(x + 63);
  };
  let out = '';
  let prevLat = 0;
  let prevLon = 0;
  for (const p of line) {
    const lat = Math.round(p.lat * 1e5);
    const lon = Math.round(p.lon * 1e5);
    out += enc(lat - prevLat) + enc(lon - prevLon);
    prevLat = lat;
    prevLon = lon;
  }
  return out;
}

/** Route-details response: one variant whose ordered stops sit at `idx` on the polyline. */
function routeDetails(pts: LatLon[], stopIdxs: number[], routeId = 'BKK_3280', names?: string[]): FutarRouteDetailsResponse {
  const refs: Record<string, FutarStop> = {};
  const stopIds: string[] = [];
  stopIdxs.forEach((i, j) => {
    const id = `${routeId}_S${j}`;
    stopIds.push(id);
    refs[id] = { id, name: names?.[j] ?? `Stop ${j}`, lat: pts[i].lat, lon: pts[i].lon };
  });
  return {
    status: 'OK',
    data: {
      entry: { id: routeId, variants: [{ headsign: 'X', stopIds, polyline: { points: encode(pts) } }] },
      references: { stops: refs },
    },
  };
}

const leg = (partial: Partial<JourneyLeg>): JourneyLeg => ({
  routeType: 'TRAM',
  fromName: '',
  toName: '',
  routes: [],
  ...partial,
});

describe('resolveMissingAddressCoords', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('leaves an address with coords untouched', async () => {
    const a = { name: 'Teleki László tér 19', distanceMeters: 120, lat: 47.49, lon: 19.06 };
    await expect(resolveMissingAddressCoords(a)).resolves.toEqual(a);
  });

  it('geocodes a legacy address that has a name but no coords', async () => {
    const spy = vi.spyOn(geocode, 'searchPlaces').mockResolvedValue([
      { id: 'x', name: 'Teleki László tér 19', displayName: 'Teleki László tér 19', lat: 47.49435, lon: 19.0841, kind: 'house' },
    ]);
    const a = { name: 'Teleki László tér 19', distanceMeters: 120 };
    await expect(resolveMissingAddressCoords(a)).resolves.toEqual({
      name: 'Teleki László tér 19',
      distanceMeters: 120,
      lat: 47.49435,
      lon: 19.0841,
    });
    expect(spy).toHaveBeenCalledWith('Teleki László tér 19');
  });

  it('returns the address unchanged when geocoding finds nothing or fails', async () => {
    const spy = vi.spyOn(geocode, 'searchPlaces').mockResolvedValue([]);
    const a = { name: 'Old Stop', distanceMeters: 50 };
    await expect(resolveMissingAddressCoords(a)).resolves.toEqual(a);
    spy.mockRejectedValueOnce(new Error('photon down'));
    await expect(resolveMissingAddressCoords(a)).resolves.toEqual(a);
  });

  it('passes null through', async () => {
    await expect(resolveMissingAddressCoords(null)).resolves.toBeNull();
  });
});

describe('walkBetweenJourneyLegs', () => {
  it('always walks between different stops', () => {
    expect(walkBetweenJourneyLegs(leg({ toName: 'A' }), leg({ fromName: 'B' }))).toBe(true);
  });

  it('does not walk between the same stop at the same level', () => {
    expect(walkBetweenJourneyLegs(leg({ routeType: 'TRAM', toName: 'A' }), leg({ routeType: 'BUS', fromName: 'A' }))).toBe(false);
  });

  it('walks between the same stop when crossing surface/underground levels', () => {
    expect(walkBetweenJourneyLegs(leg({ routeType: 'TRAM', toName: 'A' }), leg({ routeType: 'SUBWAY', fromName: 'A' }))).toBe(true);
  });

  it('walks between different metro lines at the same stop area', () => {
    const from = leg({ routeType: 'SUBWAY', toName: 'Kálvin tér', routes: [{ routeId: 'BKK_M3', color: '003DA5' }] });
    const to = leg({ routeType: 'SUBWAY', fromName: 'Kálvin tér', routes: [{ routeId: 'BKK_M4', color: '00A650' }] });
    expect(walkBetweenJourneyLegs(from, to)).toBe(true);
  });

  it('does not walk when continuing on the same metro line at the same stop area', () => {
    const from = leg({ routeType: 'SUBWAY', toName: 'Kálvin tér', routes: [{ routeId: 'BKK_M3', color: '003DA5' }] });
    const to = leg({ routeType: 'SUBWAY', fromName: 'Kálvin tér', routes: [{ routeId: 'BKK_M3', color: '003DA5' }] });
    expect(walkBetweenJourneyLegs(from, to)).toBe(false);
  });
});

describe('buildJourneyGeometry', () => {
  it('clips each leg to its boarding → destination segment', () => {
    const pts = line(12);
    const details = new Map([['BKK_3280', routeDetails(pts, [2, 8])]]);
    const out = buildJourneyGeometry(
      [leg({ routeType: 'TRAM', fromName: 'Stop 0', toName: 'Stop 1', routes: [{ routeId: 'BKK_3280', color: 'FFD800' }] })],
      details
    );
    expect(out.polylines).toHaveLength(1);
    // Taken segment: vertices 2..8.
    expect(out.polylines[0].length).toBe(7);
    expect(out.polylines[0][0]).toEqual(pts[2]);
    expect(out.polylines[0][out.polylines[0].length - 1]).toEqual(pts[8]);
    expect(out.colors).toEqual(['FFD800']);
    // Only the stops in range (both ends inclusive).
    expect(out.stops.map((s) => s.name)).toEqual(['Stop 0', 'Stop 1']);
  });

  it('connects the origin address to the first boarding point with a walk', () => {
    const pts = line(10);
    const details = new Map([['BKK_3280', routeDetails(pts, [2, 7])]]);
    const out = buildJourneyGeometry(
      [leg({ routeType: 'TRAM', fromName: 'Stop 0', toName: 'Stop 1', routes: [{ routeId: 'BKK_3280', color: 'FFD800' }] })],
      details,
      { name: 'Teleki László tér 19', distanceMeters: 180, lat: 47.49, lon: 19.06 }
    );
    expect(out.walks).toHaveLength(1);
    // Walk starts at the address and lands exactly on the boarding vertex.
    expect(out.walks[0][0]).toEqual({ lat: 47.49, lon: 19.06 });
    expect(out.walks[0][1]).toEqual(pts[2]);
  });

  it('connects consecutive legs with a walk between their clipped endpoints', () => {
    const pts1 = line(10, 47.47);
    const pts2 = line(10, 47.50);
    const details = new Map([
      ['BKK_3280', routeDetails(pts1, [2, 7])],
      ['BKK_5400', routeDetails(pts2, [2, 7], 'BKK_5400', ['Stop 1', 'Stop 2'])],
    ]);
    const out = buildJourneyGeometry(
      [
        leg({ routeType: 'TRAM', fromName: 'Stop 0', toName: 'Stop 1', routes: [{ routeId: 'BKK_3280', color: 'FFD800' }] }),
        leg({ routeType: 'SUBWAY', fromName: 'Stop 1', toName: 'Stop 2', routes: [{ routeId: 'BKK_5400', color: '009645' }] }),
      ],
      details
    );
    // One walk between the legs (both legs' endpoints differ — the tram and
    // metro platforms are at different coordinates).
    expect(out.walks).toHaveLength(1);
    expect(out.walks[0][0]).toEqual(pts1[7]);
    expect(out.walks[0][1]).toEqual(pts2[2]);
    expect(out.colors).toEqual(['FFD800', '009645']);
  });

  it('skips the walk for a same-stop same-level change', () => {
    const pts1 = line(10, 47.47);
    const pts2 = line(10, 47.47); // same coordinates — same platform area
    const details = new Map([
      ['BKK_3280', routeDetails(pts1, [2, 7])],
      ['BKK_3370', routeDetails(pts2, [2, 7], 'BKK_3370')],
    ]);
    const out = buildJourneyGeometry(
      [
        leg({ routeType: 'TRAM', fromName: 'Stop 0', toName: 'Stop 1', routes: [{ routeId: 'BKK_3280', color: 'FFD800' }] }),
        leg({ routeType: 'TRAM', fromName: 'Stop 1', toName: 'Stop 2', routes: [{ routeId: 'BKK_3370', color: 'FFD800' }] }),
      ],
      details
    );
    expect(out.walks).toHaveLength(0);
  });

  it('connects different metro lines at the same stop area with a walk', () => {
    const pts1 = line(10, 47.47);
    const pts2 = line(10, 47.47).map((p) => ({ ...p, lon: 19.052 })); // M4 platforms ~150 m east
    const details = new Map([
      ['BKK_M3', routeDetails(pts1, [2, 7], 'BKK_M3', ['Stop 0', 'Kálvin tér'])],
      ['BKK_M4', routeDetails(pts2, [2, 7], 'BKK_M4', ['Kálvin tér', 'Stop 2'])],
    ]);
    const out = buildJourneyGeometry(
      [
        leg({ routeType: 'SUBWAY', fromName: 'Stop 0', toName: 'Kálvin tér', routes: [{ routeId: 'BKK_M3', color: '003DA5' }] }),
        leg({ routeType: 'SUBWAY', fromName: 'Kálvin tér', toName: 'Stop 2', routes: [{ routeId: 'BKK_M4', color: '00A650' }] }),
      ],
      details
    );
    expect(out.walks).toHaveLength(1);
    expect(out.walks[0][0]).toEqual(pts1[7]);
    expect(out.walks[0][1]).toEqual(pts2[2]);
  });

  it('draws the destination address walk from the last alighting point', () => {
    const pts = line(10);
    const details = new Map([['BKK_3280', routeDetails(pts, [2, 7])]]);
    const out = buildJourneyGeometry(
      [leg({ routeType: 'TRAM', fromName: 'Stop 0', toName: 'Stop 1', routes: [{ routeId: 'BKK_3280', color: 'FFD800' }] })],
      details,
      null,
      { name: 'Corvinus University', distanceMeters: 300, lat: 47.48, lon: 19.04 }
    );
    expect(out.walks).toHaveLength(1);
    expect(out.walks[0][0]).toEqual(pts[7]);
    expect(out.walks[0][1]).toEqual({ lat: 47.48, lon: 19.04 });
  });

  it('skips address walks whose coords are missing (legacy trips)', () => {
    const pts = line(10);
    const details = new Map([['BKK_3280', routeDetails(pts, [2, 7])]]);
    const out = buildJourneyGeometry(
      [leg({ routeType: 'TRAM', fromName: 'Stop 0', toName: 'Stop 1', routes: [{ routeId: 'BKK_3280', color: 'FFD800' }] })],
      details,
      { name: 'Old', distanceMeters: 100 }
    );
    expect(out.walks).toHaveLength(0);
  });

  it('falls back to the whole line when stop names do not resolve', () => {
    const pts = line(10);
    const details = new Map([['BKK_3280', routeDetails(pts, [2, 7])]]);
    const out = buildJourneyGeometry(
      [leg({ routeType: 'TRAM', fromName: 'Unknown A', toName: 'Unknown B', routes: [{ routeId: 'BKK_3280', color: 'FFD800' }] })],
      details
    );
    expect(out.polylines).toHaveLength(1);
    expect(out.polylines[0].length).toBe(pts.length); // full line
  });

  it('merges either/or routes of one leg into separate segments with the same range', () => {
    const pts = line(12);
    const details = new Map([
      ['BKK_3280', routeDetails(pts, [2, 8])],
      ['BKK_3370', routeDetails(pts, [3, 9], 'BKK_3370')],
    ]);
    const out = buildJourneyGeometry(
      [
        leg({
          routeType: 'TRAM',
          fromName: 'Stop 0',
          toName: 'Stop 1',
          routes: [
            { routeId: 'BKK_3280', color: 'FFD800' },
            { routeId: 'BKK_3370', color: 'FFD800' },
          ],
        }),
      ],
      details
    );
    expect(out.polylines).toHaveLength(2);
    expect(out.colors).toEqual(['FFD800', 'FFD800']);
  });

  it('keeps each either/or route at its own stop and walks reach every boarding point', () => {
    // Route A boards at pts[2] on its own polyline; route B (the 99 bus) boards
    // at a DIFFERENT stop on its own geometry. Neither polyline may be
    // stretched; instead the origin walk fans out so every boarding point is
    // reached by its own dashed walk.
    const pts = line(12);
    const busPts = line(12, 47.50); // far away — its own geometry
    const details = new Map([
      ['BKK_3280', routeDetails(pts, [2, 8], 'BKK_3280', ['Teleki László tér', 'II. János Pál pápa tér'])],
      ['BKK_0990', routeDetails(busPts, [2, 8], 'BKK_0990', ['Teleki László tér', 'II. János Pál pápa tér'])],
    ]);
    const out = buildJourneyGeometry(
      [
        leg({
          routeType: 'TRAM',
          fromName: 'Teleki László tér',
          toName: 'II. János Pál pápa tér',
          routes: [
            { routeId: 'BKK_3280', color: 'FFD800' },
            { routeId: 'BKK_0990', color: '009EE3' },
          ],
        }),
      ],
      details,
      { name: 'Teleki László tér 19', distanceMeters: 180, lat: 47.49, lon: 19.06 }
    );
    expect(out.polylines).toHaveLength(2);
    // Routes keep their OWN endpoints — no polyline stretching.
    expect(out.polylines[0][0]).toEqual(pts[2]);
    expect(out.polylines[0][out.polylines[0].length - 1]).toEqual(pts[8]);
    expect(out.polylines[1][0]).toEqual(busPts[2]);
    expect(out.polylines[1][out.polylines[1].length - 1]).toEqual(busPts[8]);
    // The origin walk reaches BOTH boarding points (deduped: two distinct
    // platforms here).
    expect(out.walks).toHaveLength(2);
    // Both walks start at the origin address.
    expect(out.walks.every((w) => w[0].lat === 47.49 && w[0].lon === 19.06)).toBe(true);
    expect(out.walks.map((w) => w[1])).toContainEqual(pts[2]);
    expect(out.walks.map((w) => w[1])).toContainEqual(busPts[2]);
  });

  it('fans transfer walks from every alighting point to every boarding point of the next leg', () => {
    const pts1 = line(10, 47.47);
    const pts2 = line(10, 47.50);
    const busPts = line(10, 47.53); // the 99 bus alights elsewhere too
    const details = new Map([
      ['BKK_3280', routeDetails(pts1, [2, 7])],
      ['BKK_0990', routeDetails(busPts, [2, 7], 'BKK_0990')],
      ['BKK_5400', routeDetails(pts2, [2, 7], 'BKK_5400', ['Stop 1', 'Stop 2'])],
    ]);
    const out = buildJourneyGeometry([
      leg({
        routeType: 'TRAM',
        fromName: 'Stop 0',
        toName: 'Stop 1',
        routes: [
          { routeId: 'BKK_3280', color: 'FFD800' },
          { routeId: 'BKK_0990', color: '009EE3' },
        ],
      }),
      leg({ routeType: 'SUBWAY', fromName: 'Stop 1', toName: 'Stop 2', routes: [{ routeId: 'BKK_5400', color: '009645' }] }),
    ], details);
    // Tram alight -> metro board AND bus alight -> metro board.
    expect(out.walks).toHaveLength(2);
    expect(out.walks.map((w) => w[0])).toContainEqual(pts1[7]);
    expect(out.walks.map((w) => w[0])).toContainEqual(busPts[7]);
    for (const w of out.walks) expect(w[1]).toEqual(pts2[2]);
  });
});