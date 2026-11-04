import { afterAll, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import type { FutarArrivalsResponse, FutarStop } from './types';
import type { CatalogRoute, CatalogStop } from './catalog-types';
import {
  BUDAPEST_CENTER,
  buildExplorerMapStops,
  findNearbyStops,
  findStopsInBounds,
  formatDistanceMeters,
  getCatalogRouteMap,
  getCatalogStops,
  isInHungary,
  parseNearbyBoards,
  resolveAnchor,
  calculateWalkMinutes,
  calculateFeasibility,
  buildRadarDepartures,
  clampBottomInset,
  filterDeparturesByMode,
  matchesLineSearch,
  padBoundsToMinSpan,
  viewportFromUncoveredRect,
  type LatLonBounds,
  type NearbyDeparture,
  type NearbyStop,
} from './nearby-stops';

const CATALOG_FIXTURE: CatalogStop[] = [
  { id: 'BKK_CSF01108', name: 'Astoria', lat: 47.494479, lon: 19.060643 },
  { id: 'BKK_CSF01116', name: 'Blaha Lujza tér', lat: 47.49665, lon: 19.070596 },
  { id: 'BKK_CSF00065', name: 'Batthyány tér', lat: 47.506377, lon: 19.038676 },
];

const ROUTE_MAP_FIXTURE = new Map<string, CatalogRoute>([
  ['BKK_5400', { id: 'BKK_5400', shortName: 'M4', description: 'Kelenföld vasútállomás M | Keleti pályaudvar M', type: 'SUBWAY', color: '12A13C', textColor: 'FFFFFF' }],
  ['BKK_3010', { id: 'BKK_3010', shortName: '1', description: 'Kelenföld vasútállomás M | Bécsi út / Vörösvári út', type: 'TRAM', color: 'FFD800', textColor: '000000' }],
]);

function makeArrivals(): FutarArrivalsResponse {
  const now = Date.now();
  const soon = Math.floor(now / 1000) + 180; // +3 min
  const later = Math.floor(now / 1000) + 600; // +10 min
  const past = Math.floor(now / 1000) - 300; // -5 min (outside the 60s grace)
  return {
    currentTime: now,
    status: 'OK',
    data: {
      entry: {
        stopId: 'BKK_CSF01108',
        stopTimes: [
          { stopId: 'BKK_F00073', stopHeadsign: 'Keleti pályaudvar M', predictedArrivalTime: soon, tripId: 'trip-m4-1' },
          { stopId: 'BKK_CSF01108', stopHeadsign: 'Kelenföld vasútállomás M', predictedArrivalTime: later, tripId: 'trip-m4-2' },
          { stopId: 'BKK_CSF01108', stopHeadsign: 'Bécsi út / Vörösvári út', predictedArrivalTime: soon, tripId: 'trip-tram-1' },
          { stopId: 'BKK_CSF01108', stopHeadsign: 'Bécsi út / Vörösvári út', predictedArrivalTime: past, tripId: 'trip-tram-past' },
          { stopId: 'BKK_CSF01108', stopHeadsign: 'Cancelled', predictedArrivalTime: soon, tripId: 'trip-cancelled', canceled: true },
          { stopId: 'BKK_CSF01108', stopHeadsign: 'No route ref', predictedArrivalTime: soon, tripId: 'trip-no-route' },
        ],
      },
      references: {
        stops: {
          // Blaha Lujza tér has separate physical stops per line group; the M4
          // departure leaves from a platform distinct from the station centre.
          BKK_F00073: { id: 'BKK_F00073', lat: 47.498321, lon: 19.071412, name: 'Blaha Lujza tér', parentStationId: 'BKK_CSF01108' },
        },
        trips: {
          'trip-m4-1': { routeId: 'BKK_5400' },
          'trip-m4-2': { routeId: 'BKK_5400' },
          'trip-tram-1': { routeId: 'BKK_3010' },
          'trip-tram-past': { routeId: 'BKK_3010' },
          'trip-cancelled': { routeId: 'BKK_5400' },
          // trip-no-route intentionally absent
        },
        routes: {
          BKK_9999: { id: 'BKK_9999', shortName: 'X9', description: 'Fallback route', type: 'BUS', color: '009EE3', textColor: 'FFFFFF' },
        },
      },
    },
  };
}

describe('findNearbyStops', () => {
  it('returns the anchor stop at ~0m and sorts the rest by distance', () => {
    // Radius large enough to include all three fixtures (Batthyány tér is ~2.1 km).
    const stops = findNearbyStops(CATALOG_FIXTURE, 47.494479, 19.060643, { radiusMeters: 3000 });
    expect(stops[0].id).toBe('BKK_CSF01108');
    expect(stops[0].distanceMeters).toBeLessThan(10);
    expect(stops[1].id).toBe('BKK_CSF01116'); // Blaha, ~790 m away
    expect(stops[1].distanceMeters).toBeGreaterThan(700);
    expect(stops[1].distanceMeters).toBeLessThan(900);
    expect(stops[2].id).toBe('BKK_CSF00065'); // Batthyány tér, furthest
  });

  it('respects radius and limit', () => {
    const tight = findNearbyStops(CATALOG_FIXTURE, 47.494479, 19.060643, { radiusMeters: 400 });
    expect(tight.map((s) => s.id)).toEqual(['BKK_CSF01108']);
    const limited = findNearbyStops(CATALOG_FIXTURE, 47.494479, 19.060643, { radiusMeters: 1000, limit: 2 });
    expect(limited.map((s) => s.id)).toEqual(['BKK_CSF01108', 'BKK_CSF01116']);
  });
});

describe('isInHungary', () => {
  it('accepts Hungarian cities', () => {
    expect(isInHungary(47.4979, 19.0402)).toBe(true); // Budapest
    expect(isInHungary(47.53, 21.63)).toBe(true); // Debrecen
    expect(isInHungary(47.68, 17.63)).toBe(true); // Győr
    expect(isInHungary(48.1, 20.79)).toBe(true); // Miskolc
    expect(isInHungary(46.07, 18.23)).toBe(true); // Pécs
    expect(isInHungary(46.3, 20.1)).toBe(true); // just north of Szeged
  });

  it('rejects foreign cities near the border', () => {
    expect(isInHungary(48.2082, 16.3738)).toBe(false); // Vienna
    expect(isInHungary(48.1486, 17.1077)).toBe(false); // Bratislava
    expect(isInHungary(48.72, 21.26)).toBe(false); // Košice
    expect(isInHungary(46.17, 21.32)).toBe(false); // Arad
    expect(isInHungary(45.81, 15.98)).toBe(false); // Zagreb
    expect(isInHungary(46.2, 20.1)).toBe(false); // just south of Szeged (Serbia)
    expect(isInHungary(48.8566, 2.3522)).toBe(false); // Paris
    expect(isInHungary(40.7128, -74.006)).toBe(false); // New York
  });
});

describe('resolveAnchor', () => {
  it('uses the user location inside Hungary', () => {
    expect(resolveAnchor(47.4979, 19.0402)).toEqual({
      lat: 47.4979,
      lon: 19.0402,
      label: 'you',
      outsideHungary: false,
    });
  });

  it('falls back to Budapest center outside Hungary', () => {
    expect(resolveAnchor(48.2082, 16.3738)).toEqual({
      lat: BUDAPEST_CENTER.lat,
      lon: BUDAPEST_CENTER.lon,
      label: 'budapest-center',
      outsideHungary: true,
    });
  });
});

describe('parseNearbyBoards', () => {
  it('groups arrivals by route, drops canceled/past/unresolvable, sorts by time', () => {
    const boards = parseNearbyBoards(makeArrivals(), ROUTE_MAP_FIXTURE);
    expect(boards.map((b) => b.routeId)).toEqual(['BKK_5400', 'BKK_3010']); // metro first
    const metro = boards[0];
    expect(metro.shortName).toBe('M4');
    expect(metro.color).toBe('12A13C');
    expect(metro.arrivals.map((a) => a.headSign)).toEqual(['Keleti pályaudvar M', 'Kelenföld vasútállomás M']);
    expect(metro.arrivals[0].arrivalMs).toBeLessThan(metro.arrivals[1].arrivalMs);
    // The departure's physical stop reference rides along; the station-centre
    // arrival (no reference entry) carries no platform fields.
    expect(metro.arrivals[0].platformStopId).toBe('BKK_F00073');
    expect(metro.arrivals[0].platformLat).toBeCloseTo(47.498321, 5);
    expect(metro.arrivals[0].platformLon).toBeCloseTo(19.071412, 5);
    expect(metro.arrivals[1].platformStopId).toBeUndefined();
    expect(metro.arrivals[1].platformLat).toBeUndefined();
    expect(metro.arrivals[1].platformLon).toBeUndefined();
    const tram = boards[1];
    expect(tram.arrivals).toHaveLength(1); // past + cancelled + no-route dropped
    expect(tram.arrivals[0].headSign).toBe('Bécsi út / Vörösvári út');
  });

  it('falls back to references.routes, then the raw routeId', () => {
    const data = makeArrivals();
    data.data!.references!.trips!['trip-fallback'] = { routeId: 'BKK_9999' };
    data.data!.entry!.stopTimes.push({
      stopId: 'BKK_CSF01108',
      stopHeadsign: 'Fallback',
      predictedArrivalTime: Math.floor(Date.now() / 1000) + 240,
      tripId: 'trip-fallback',
    });
    const boards = parseNearbyBoards(data, ROUTE_MAP_FIXTURE);
    const fallback = boards.find((b) => b.routeId === 'BKK_9999');
    expect(fallback?.shortName).toBe('X9'); // from references.routes
    expect(fallback?.color).toBe('009EE3');

    const noCatalog = parseNearbyBoards(makeArrivals(), null);
    // No catalog map and no references.routes entry for BKK_5400 -> raw id fallback
    // (type priority ties, so look it up by routeId rather than assuming index 0).
    const m4 = noCatalog.find((b) => b.routeId === 'BKK_5400');
    expect(m4?.shortName).toBe('BKK_5400');
    expect(m4?.routeId).toBe('BKK_5400');
  });

  it('returns an empty board list for undefined data', () => {
    expect(parseNearbyBoards(undefined, ROUTE_MAP_FIXTURE)).toEqual([]);
  });
});

describe('filterDeparturesByMode', () => {
  const departure = (routeType: string): NearbyDeparture => ({
    routeId: `BKK_${routeType}`,
    shortName: routeType,
    routeType,
    color: '000000',
    textColor: 'FFFFFF',
    arrivals: [{ tripId: `${routeType}-t`, arrivalMs: 0, headSign: 'Test', isPredicted: false }],
  });

  const departures = [departure('SUBWAY'), departure('TRAM'), departure('BUS')];

  it('returns everything when no filter is set', () => {
    expect(filterDeparturesByMode(departures)).toHaveLength(3);
    expect(filterDeparturesByMode(departures, 'ALL')).toHaveLength(3);
    expect(filterDeparturesByMode(departures, [])).toHaveLength(3);
  });

  it('keeps the legacy single-mode form working', () => {
    expect(filterDeparturesByMode(departures, 'TRAM').map((d) => d.routeType)).toEqual(['TRAM']);
  });

  it('matches any of the selected modes', () => {
    expect(filterDeparturesByMode(departures, ['SUBWAY', 'BUS']).map((d) => d.routeType)).toEqual(['SUBWAY', 'BUS']);
  });

  it('is case-insensitive and ignores ALL in a mixed list', () => {
    expect(filterDeparturesByMode(departures, ['tram', 'ALL']).map((d) => d.routeType)).toEqual(['TRAM']);
  });

  it('matches nothing for an unknown mode', () => {
    expect(filterDeparturesByMode(departures, ['FERRY'])).toEqual([]);
  });
});

describe('formatDistanceMeters', () => {
  it('formats meters and kilometers', () => {
    expect(formatDistanceMeters(0)).toBe('0 m');
    expect(formatDistanceMeters(350)).toBe('350 m');
    expect(formatDistanceMeters(1200)).toBe('1.2 km');
  });
});

describe('lazy catalog getters', () => {
  afterAll(() => {
    vi.unstubAllGlobals();
  });

  it('resolve the real catalogs (single call, memoized)', async () => {
    vi.stubGlobal('fetch', async (input: RequestInfo | URL) => {
      const file = String(input).replace('/catalog/', '');
      return new Response(
        readFileSync(new URL(`../../../public/catalog/${file}`, import.meta.url), 'utf8'),
        { status: 200, headers: { 'content-type': 'application/json' } }
      );
    });

    const stops1 = await getCatalogStops();
    const stops2 = await getCatalogStops();
    expect(stops1).toBe(stops2); // memoized
    expect(stops1.length).toBeGreaterThan(1000);
    expect(stops1[0]).toHaveProperty('lat');

    const routes1 = await getCatalogRouteMap();
    const routes2 = await getCatalogRouteMap();
    expect(routes1).toBe(routes2);
    expect(routes1.has('BKK_5400')).toBe(true); // M4 metro
  });
});

describe('Transit Radar Feasibility & Calculations', () => {
  it('calculates walking minutes based on 72m/min speed floor of 1 min', () => {
    expect(calculateWalkMinutes(0)).toBe(1);
    expect(calculateWalkMinutes(70)).toBe(1);
    expect(calculateWalkMinutes(144)).toBe(2);
    expect(calculateWalkMinutes(500)).toBe(7);
  });

  it('determines arrival feasibility correctly', () => {
    // Walk is 2 min, arrival in 5 min -> delta = 3 -> catchable
    expect(calculateFeasibility(5, 2)).toBe('catchable');
    // Walk is 3 min, arrival in 4 min -> delta = 1 -> hurry
    expect(calculateFeasibility(4, 3)).toBe('hurry');
    // Walk is 4 min, arrival in 4 min -> delta = 0 -> hurry
    expect(calculateFeasibility(4, 4)).toBe('hurry');
    // Walk is 5 min, arrival in 3 min -> delta = -2 -> missed
    expect(calculateFeasibility(3, 5)).toBe('missed');
  });

  it('builds and ranks imminent departures across candidate stops', () => {
    const stops: NearbyStop[] = [
      { id: 'BKK_F01', name: 'Deák tér', lat: 47.4979, lon: 19.054, distanceMeters: 140 },
      { id: 'BKK_F02', name: 'Astoria', lat: 47.494, lon: 19.06, distanceMeters: 400 },
    ];
    const now = 1000000;
    const stopDeparturesMap = new Map<string, NearbyDeparture[]>([
      [
        'BKK_F01',
        [
          {
            routeId: 'BKK_M3',
            shortName: 'M3',
            routeType: 'SUBWAY',
            color: '005CA5',
            textColor: 'FFFFFF',
            arrivals: [
              {
                tripId: 't1',
                arrivalMs: now + 4 * 60_000,
                headSign: 'Újpest',
                isPredicted: true,
                platformStopId: 'BKK_F01P',
                platformLat: 47.4981,
                platformLon: 19.0543,
              },
            ],
          },
        ],
      ],
      [
        'BKK_F02',
        [
          {
            routeId: 'BKK_47',
            shortName: '47',
            routeType: 'TRAM',
            color: 'FFD900',
            textColor: '1A1A1A',
            arrivals: [
              { tripId: 't2', arrivalMs: now + 6 * 60_000, headSign: 'Városház tér', isPredicted: true },
            ],
          },
        ],
      ],
    ]);

    const radar = buildRadarDepartures({ stops, stopDeparturesMap, now });
    expect(radar.length).toBe(2);
    // Departure cards group trams before metro, then sort routes/ETAs within
    // each mode group.
    expect(radar[0].shortName).toBe('47');
    expect(radar[0].tripId).toBe('t2');
    expect(radar[1].shortName).toBe('M3');
    expect(radar[1].tripId).toBe('t1');
    expect(radar[1].feasibility).toBe('catchable');
    expect(radar[1].walkMinutes).toBe(2);
    expect(radar[1].platformStopId).toBe('BKK_F01P');
    expect(radar[1].platformLat).toBeCloseTo(47.4981, 4);
    expect(radar[1].platformLon).toBeCloseTo(19.0543, 4);
    expect(radar[0].platformLat).toBeUndefined();
  });

  it('deduplicates a trip reported once per platform at the same stop', () => {
    const stops: NearbyStop[] = [
      { id: 'BKK_F01', name: 'Deák tér', lat: 47.4979, lon: 19.054, distanceMeters: 140 },
    ];
    const now = 1000000;
    const stopDeparturesMap = new Map<string, NearbyDeparture[]>([
      [
        'BKK_F01',
        [
          {
            routeId: 'BKK_M3',
            shortName: 'M3',
            routeType: 'SUBWAY',
            color: '005CA5',
            textColor: 'FFFFFF',
            arrivals: [
              { tripId: 't1', arrivalMs: now + 4 * 60_000, headSign: 'Újpest', isPredicted: true },
              { tripId: 't1', arrivalMs: now + 4 * 60_000, headSign: 'Újpest', isPredicted: true },
            ],
          },
        ],
      ],
    ]);

    const radar = buildRadarDepartures({ stops, stopDeparturesMap, now });
    expect(radar.length).toBe(1);
    expect(radar[0].id).toBe('BKK_F01-BKK_M3-t1-1240000');
  });

  it('keeps the same tripId when it serves the same stop twice at different times', () => {
    const stops: NearbyStop[] = [
      { id: 'BKK_F01', name: 'Deák tér', lat: 47.4979, lon: 19.054, distanceMeters: 140 },
    ];
    const now = 1000000;
    const stopDeparturesMap = new Map<string, NearbyDeparture[]>([
      [
        'BKK_F01',
        [
          {
            routeId: 'BKK_M3',
            shortName: 'M3',
            routeType: 'SUBWAY',
            color: '005CA5',
            textColor: 'FFFFFF',
            arrivals: [
              { tripId: 't1', arrivalMs: now + 4 * 60_000, headSign: 'Újpest', isPredicted: true },
              { tripId: 't1', arrivalMs: now + 30 * 60_000, headSign: 'Újpest', isPredicted: true },
            ],
          },
        ],
      ],
    ]);

    const radar = buildRadarDepartures({ stops, stopDeparturesMap, now });
    expect(radar.length).toBe(2);
    expect(radar.map((r) => r.arrivalMs)).toEqual([now + 4 * 60_000, now + 30 * 60_000]);
    expect(new Set(radar.map((r) => r.id)).size).toBe(2);
  });

  it('filters radar departures by transit mode', () => {
    const stops: NearbyStop[] = [
      { id: 'BKK_F01', name: 'Deák tér', lat: 47.4979, lon: 19.054, distanceMeters: 140 },
    ];
    const now = 1000000;
    const stopDeparturesMap = new Map<string, NearbyDeparture[]>([
      [
        'BKK_F01',
        [
          {
            routeId: 'BKK_M3',
            shortName: 'M3',
            routeType: 'SUBWAY',
            color: '005CA5',
            textColor: 'FFFFFF',
            arrivals: [{ tripId: 't1', arrivalMs: now + 4 * 60_000, headSign: 'Újpest', isPredicted: true }],
          },
          {
            routeId: 'BKK_47',
            shortName: '47',
            routeType: 'TRAM',
            color: 'FFD900',
            textColor: '1A1A1A',
            arrivals: [{ tripId: 't2', arrivalMs: now + 3 * 60_000, headSign: 'Városház tér', isPredicted: true }],
          },
        ],
      ],
    ]);

    const onlyMetro = buildRadarDepartures({ stops, stopDeparturesMap, now, modeFilter: 'SUBWAY' });
    expect(onlyMetro.length).toBe(1);
    expect(onlyMetro[0].shortName).toBe('M3');

    const onlyTram = buildRadarDepartures({ stops, stopDeparturesMap, now, modeFilter: 'TRAM' });
    expect(onlyTram.length).toBe(1);
    expect(onlyTram[0].shortName).toBe('47');
  });

  it('filters radar departures by quick search query (line name or destination)', () => {
    const stops: NearbyStop[] = [
      { id: 'BKK_F01', name: 'Deák tér', lat: 47.4979, lon: 19.054, distanceMeters: 140 },
    ];
    const now = 1000000;
    const stopDeparturesMap = new Map<string, NearbyDeparture[]>([
      [
        'BKK_F01',
        [
          {
            routeId: 'BKK_M3',
            shortName: 'M3',
            routeType: 'SUBWAY',
            color: '005CA5',
            textColor: 'FFFFFF',
            arrivals: [{ tripId: 't1', arrivalMs: now + 4 * 60_000, headSign: 'Újpest-központ', isPredicted: true }],
          },
          {
            routeId: 'BKK_47',
            shortName: '47',
            routeType: 'TRAM',
            color: 'FFD900',
            textColor: '1A1A1A',
            arrivals: [{ tripId: 't2', arrivalMs: now + 3 * 60_000, headSign: 'Városház tér', isPredicted: true }],
          },
        ],
      ],
    ]);

    const searchM3 = buildRadarDepartures({ stops, stopDeparturesMap, now, searchQuery: 'm3' });
    expect(searchM3.length).toBe(1);
    expect(searchM3[0].shortName).toBe('M3');

    const searchDestination = buildRadarDepartures({ stops, stopDeparturesMap, now, searchQuery: 'városház' });
    expect(searchDestination.length).toBe(1);
    expect(searchDestination[0].shortName).toBe('47');
  });

  it('strictly isolates M4 departures when searching M4 without returning other lines', () => {
    const stops: NearbyStop[] = [
      { id: 'BKK_F01', name: 'Kálvin tér M', lat: 47.489, lon: 19.061, distanceMeters: 200 },
    ];
    const now = 1000000;
    const stopDeparturesMap = new Map<string, NearbyDeparture[]>([
      [
        'BKK_F01',
        [
          {
            routeId: 'BKK_M4',
            shortName: 'M4',
            routeType: 'SUBWAY',
            color: '12A13C',
            textColor: 'FFFFFF',
            arrivals: [{ tripId: 't-m4', arrivalMs: now + 3 * 60_000, headSign: 'Keleti pályaudvar M', isPredicted: true }],
          },
          {
            routeId: 'BKK_M3',
            shortName: 'M3',
            routeType: 'SUBWAY',
            color: '005CA5',
            textColor: 'FFFFFF',
            arrivals: [{ tripId: 't-m3', arrivalMs: now + 2 * 60_000, headSign: 'Újpest (átszállás M4)', isPredicted: true }],
          },
          {
            routeId: 'BKK_4',
            shortName: '4',
            routeType: 'TRAM',
            color: 'FFD900',
            textColor: '1A1A1A',
            arrivals: [{ tripId: 't-tram4', arrivalMs: now + 4 * 60_000, headSign: 'Újbuda-központ', isPredicted: true }],
          },
          {
            routeId: 'BKK_47',
            shortName: '47',
            routeType: 'TRAM',
            color: 'FFD900',
            textColor: '1A1A1A',
            arrivals: [{ tripId: 't-tram47', arrivalMs: now + 5 * 60_000, headSign: 'Városház tér', isPredicted: true }],
          },
          {
            routeId: 'BKK_7',
            shortName: '7',
            routeType: 'BUS',
            color: '009EE3',
            textColor: 'FFFFFF',
            arrivals: [{ tripId: 't-bus7', arrivalMs: now + 2 * 60_000, headSign: 'Kelenföld vasútállomás M (M4)', isPredicted: true }],
          },
        ],
      ],
    ]);

    const result = buildRadarDepartures({ stops, stopDeparturesMap, now, searchQuery: 'M4' });
    // ONLY M4 should be returned — not M3, not Tram 4, not Tram 47, and not Bus 7
    expect(result.length).toBe(1);
    expect(result[0].shortName).toBe('M4');
    expect(result[0].routeId).toBe('BKK_M4');
  });
});

describe('matchesLineSearch', () => {
  it('strictly matches M4 for line queries', () => {
    // Exact matches
    expect(matchesLineSearch('M4', 'Keleti pályaudvar M', 'Kálvin tér', 'M4')).toBe(true);
    expect(matchesLineSearch('M4', 'Keleti pályaudvar M', 'Kálvin tér', 'm4')).toBe(true);
    expect(matchesLineSearch('M4', 'Keleti pályaudvar M', 'Kálvin tér', 'M 4')).toBe(true);
    expect(matchesLineSearch('M4', 'Keleti pályaudvar M', 'Kálvin tér', 'metro 4')).toBe(true);

    // Negative matches: other lines should NEVER match M4 even if headsign/stop mentions M4
    expect(matchesLineSearch('M3', 'Újpest (átszállás M4)', 'Deák Ferenc tér', 'M4')).toBe(false);
    expect(matchesLineSearch('4', 'Újbuda-központ', 'Kálvin tér M4', 'M4')).toBe(false);
    expect(matchesLineSearch('47', 'Városház tér', 'Fővám tér M4', 'M4')).toBe(false);
    expect(matchesLineSearch('7', 'Kelenföld M (átszállás M4)', 'Keleti M4', 'M4')).toBe(false);
  });

  it('strictly matches single-digit and multi-character lines', () => {
    expect(matchesLineSearch('4', 'Újbuda-központ', 'Margit híd', '4')).toBe(true);
    expect(matchesLineSearch('14', 'Lehel tér', 'Káposztásmegyer', '4')).toBe(false);
    expect(matchesLineSearch('24', 'Keleti pályaudvar', 'Közvágóhíd', '4')).toBe(false);
    expect(matchesLineSearch('47', 'Deák tér', 'Városház tér', '4')).toBe(false);
    expect(matchesLineSearch('4-6', 'Széll Kálmán tér', 'Nyugati pályaudvar', '4')).toBe(false);

    // Complex line names like 4-6 and 2B
    expect(matchesLineSearch('4-6', 'Széll Kálmán tér', 'Nyugati pályaudvar', '4-6')).toBe(true);
    expect(matchesLineSearch('4-6', 'Széll Kálmán tér', 'Nyugati pályaudvar', '4/6')).toBe(true);
    expect(matchesLineSearch('4-6', 'Széll Kálmán tér', 'Nyugati pályaudvar', '4 6')).toBe(true);
    expect(matchesLineSearch('2B', 'Jászai Mari tér', 'Pesterzsébet', '2B')).toBe(true);
    expect(matchesLineSearch('2B', 'Jászai Mari tér', 'Pesterzsébet', '2b')).toBe(true);
  });

  it('allows destination and stop search for textual queries with accent-insensitivity', () => {
    expect(matchesLineSearch('7', 'Keleti pályaudvar M', 'Astoria', 'keleti')).toBe(true);
    expect(matchesLineSearch('47', 'Városház tér', 'Deák Ferenc tér', 'deak')).toBe(true);
    expect(matchesLineSearch('47', 'Városház tér', 'Deák Ferenc tér', 'varoshaz')).toBe(true);
    expect(matchesLineSearch('7', 'Újpalota', 'Blaha Lujza tér M', 'blaha')).toBe(true);
  });
});

describe('findStopsInBounds', () => {
  const STOPS: CatalogStop[] = [
    { id: 'a', name: 'North', lat: 47.5, lon: 19.05 },
    { id: 'b', name: 'South', lat: 47.485, lon: 19.05 },
    { id: 'c', name: 'Outside east', lat: 47.5, lon: 19.2 },
    { id: 'd', name: 'Outside south', lat: 47.4, lon: 19.05 },
  ];
  const BOUNDS: LatLonBounds = { north: 47.51, south: 47.47, east: 19.06, west: 19.04 };

  it('keeps only stops inside the box, nearest to the box center first', () => {
    const result = findStopsInBounds(STOPS, BOUNDS);
    expect(result.map((s) => s.id)).toEqual(['b', 'a']);
    expect(result[0].distanceMeters).toBeGreaterThan(0);
  });

  it('applies the limit after sorting', () => {
    expect(findStopsInBounds(STOPS, BOUNDS, { limit: 1 }).map((s) => s.id)).toEqual(['b']);
  });

  it('returns an empty array for an empty catalog', () => {
    expect(findStopsInBounds([], BOUNDS)).toEqual([]);
  });
});

describe('padBoundsToMinSpan', () => {
  const MIN_SPAN = 500;
  const METERS_PER_DEGREE_LAT = 111_320;

  it('expands tiny bounds symmetrically around the center to the minimum span', () => {
    const bounds: LatLonBounds = { north: 47.5001, south: 47.4999, east: 19.0501, west: 19.0499 };
    const padded = padBoundsToMinSpan(bounds, MIN_SPAN);
    const centerLat = (padded.north + padded.south) / 2;
    const centerLon = (padded.east + padded.west) / 2;
    expect(centerLat).toBeCloseTo(47.5, 10);
    expect(centerLon).toBeCloseTo(19.05, 10);
    expect((padded.north - padded.south) * METERS_PER_DEGREE_LAT).toBeCloseTo(MIN_SPAN, 3);
    expect(
      (padded.east - padded.west) *
        METERS_PER_DEGREE_LAT *
        Math.cos((centerLat * Math.PI) / 180)
    ).toBeCloseTo(MIN_SPAN, 3);
  });

  it('leaves bounds larger than the minimum span untouched', () => {
    const bounds: LatLonBounds = { north: 47.51, south: 47.48, east: 19.08, west: 19.03 };
    expect(padBoundsToMinSpan(bounds, MIN_SPAN)).toEqual(bounds);
  });

  it('expands only the short axis', () => {
    const bounds: LatLonBounds = { north: 47.505, south: 47.495, east: 19.0501, west: 19.0499 };
    const padded = padBoundsToMinSpan(bounds, MIN_SPAN);
    expect(padded.north).toBeCloseTo(47.505, 10);
    expect(padded.south).toBeCloseTo(47.495, 10);
    const lonMeters =
      (padded.east - padded.west) *
      METERS_PER_DEGREE_LAT *
      Math.cos(((padded.north + padded.south) / 2 * Math.PI) / 180);
    expect(lonMeters).toBeCloseTo(MIN_SPAN, 3);
  });

  it('returns invalid input unchanged', () => {
    const invalidBounds: LatLonBounds = { north: Number.NaN, south: 47.5, east: 19.05, west: 19.04 };
    expect(padBoundsToMinSpan(invalidBounds, MIN_SPAN)).toEqual(invalidBounds);
    const bounds: LatLonBounds = { north: 47.5001, south: 47.4999, east: 19.0501, west: 19.0499 };
    expect(padBoundsToMinSpan(bounds, 0)).toEqual(bounds);
    expect(padBoundsToMinSpan(bounds, -500)).toEqual(bounds);
    expect(padBoundsToMinSpan(bounds, Number.NaN)).toEqual(bounds);
  });

  it('stays finite at a degenerate longitude scale', () => {
    const bounds: LatLonBounds = { north: 90.0001, south: 89.9999, east: 19.0001, west: 18.9999 };
    const padded = padBoundsToMinSpan(bounds, MIN_SPAN);
    expect(Number.isFinite(padded.east)).toBe(true);
    expect(Number.isFinite(padded.west)).toBe(true);
  });
});

describe('viewportFromUncoveredRect', () => {
  it('pads a tiny uncovered rect up to the minimum view span', () => {
    const viewport = viewportFromUncoveredRect(
      { lat: 47.5001, lng: 19.0499 },
      { lat: 47.4999, lng: 19.0501 },
      { lat: 47.5, lng: 19.05 },
      19
    );
    const latMeters = (viewport.bounds.north - viewport.bounds.south) * 111_320;
    expect(latMeters).toBeCloseTo(500, 3);
  });

  it('builds bounds from the uncovered corners and centers on the uncovered center', () => {
    const viewport = viewportFromUncoveredRect(
      { lat: 47.51, lng: 19.03 },
      { lat: 47.48, lng: 19.08 },
      { lat: 47.495, lng: 19.055 },
      15.5
    );
    expect(viewport).toEqual({
      lat: 47.495,
      lon: 19.055,
      zoom: 15.5,
      bounds: { north: 47.51, south: 47.48, east: 19.08, west: 19.03 },
    });
  });

  it('can include the lower map area while keeping the feed center above the sheet', () => {
    const viewport = viewportFromUncoveredRect(
      { lat: 47.51, lng: 19.03 },
      { lat: 47.45, lng: 19.08 },
      { lat: 47.495, lng: 19.055 },
      15.5
    );
    expect(viewport.bounds.south).toBe(47.45);
    expect(viewport.lat).toBe(47.495);
  });

  it('normalizes whichever corner comes first (min/max both axes)', () => {
    const viewport = viewportFromUncoveredRect(
      { lat: 47.48, lng: 19.08 },
      { lat: 47.51, lng: 19.03 },
      { lat: 47.49, lng: 19.06 },
      12
    );
    expect(viewport.bounds).toEqual({ north: 47.51, south: 47.48, east: 19.08, west: 19.03 });
  });
});

describe('clampBottomInset', () => {
  it('keeps coverage inside 0..80% of the canvas height', () => {
    expect(clampBottomInset(0, 800)).toBe(0);
    expect(clampBottomInset(300, 800)).toBe(300);
    expect(clampBottomInset(640, 800)).toBe(640);
    expect(clampBottomInset(999, 800)).toBe(640);
  });

  it('treats negative/non-finite coverage and degenerate heights as no inset', () => {
    expect(clampBottomInset(-50, 800)).toBe(0);
    expect(clampBottomInset(Number.NaN, 800)).toBe(0);
    expect(clampBottomInset(Number.POSITIVE_INFINITY, 800)).toBe(0);
    expect(clampBottomInset(100, 0)).toBe(0);
  });
});

describe('buildExplorerMapStops', () => {
  const BOUNDS: LatLonBounds = { north: 47.51, south: 47.48, east: 19.08, west: 19.05 };
  const BLAHA: NearbyStop = {
    id: 'BKK_CSF01116',
    name: 'Blaha Lujza tér',
    lat: 47.49665,
    lon: 19.070596,
    distanceMeters: 40,
  };
  const ASTORIA: NearbyStop = {
    id: 'BKK_CSF01108',
    name: 'Astoria',
    lat: 47.494479,
    lon: 19.060643,
    distanceMeters: 300,
  };

  function arrivals(stops: Record<string, FutarStop>): FutarArrivalsResponse {
    return {
      currentTime: Date.now(),
      status: 'OK',
      data: {
        entry: { stopId: BLAHA.id, stopTimes: [] },
        references: { stops },
      },
    };
  }

  it('replaces the station dot with its in-viewport platform dots', () => {
    const refs: Record<string, FutarStop> = {
      BKK_CSF01116: { id: 'BKK_CSF01116', lat: BLAHA.lat, lon: BLAHA.lon, name: 'Blaha Lujza tér' },
      BKK_F01166: { id: 'BKK_F01166', lat: 47.49665, lon: 19.070696, name: 'Blaha Lujza tér M' },
      BKK_F01168: {
        id: 'BKK_F01168',
        lat: 47.4969,
        lon: 19.0707,
        name: 'Blaha Lujza tér M (Népszínház utca)',
      },
    };
    const mapStops = buildExplorerMapStops(
      [BLAHA, ASTORIA],
      new Map([[BLAHA.id, arrivals(refs)]]),
      BOUNDS
    );
    expect(mapStops.map((s) => s.id)).toEqual(['BKK_F01166', 'BKK_F01168', 'BKK_CSF01108']);
    expect(mapStops[0]).toMatchObject({
      name: 'Blaha Lujza tér M',
      stationId: 'BKK_CSF01116',
      isPlatform: true,
    });
    expect(mapStops[1].stationId).toBe('BKK_CSF01116');
    expect(mapStops[1].isPlatform).toBe(true);
    expect(mapStops[2].stationId).toBeUndefined();
    expect(mapStops[2].isPlatform).toBeUndefined();
  });

  it('dedupes platforms by id and by near-identical coordinates, skipping zero/out-of-bounds coords', () => {
    const refs: Record<string, FutarStop> = {
      alias1: { id: 'BKK_F1', lat: 47.4968, lon: 19.0708, name: 'A' },
      alias2: { id: 'BKK_F1', lat: 47.497, lon: 19.071, name: 'A duplicate id' },
      BKK_F2: { id: 'BKK_F2', lat: 47.49681, lon: 19.070801, name: 'A stacked twin' },
      BKK_F3: { id: 'BKK_F3', lat: 47.4975, lon: 19.0725, name: 'C' },
      BKK_F0: { id: 'BKK_F0', lat: 0, lon: 0, name: 'Zero' },
      BKK_FNAN: { id: 'BKK_FNAN', lat: Number.NaN, lon: 19.07, name: 'NaN' },
      BKK_FAR: { id: 'BKK_FAR', lat: 47.9, lon: 19.9, name: 'Out of bounds' },
    };
    const mapStops = buildExplorerMapStops(
      [BLAHA],
      new Map([[BLAHA.id, arrivals(refs)]]),
      BOUNDS
    );
    expect(mapStops.map((s) => s.id)).toEqual(['BKK_F1', 'BKK_F3']);
  });

  it('keeps the catalog dot when references yield no other physical stop', () => {
    const stationOnly: Record<string, FutarStop> = {
      BKK_CSF01116: { id: 'BKK_CSF01116', lat: BLAHA.lat, lon: BLAHA.lon, name: 'Blaha Lujza tér' },
    };
    expect(
      buildExplorerMapStops([BLAHA], new Map([[BLAHA.id, arrivals(stationOnly)]]), BOUNDS)
    ).toEqual([BLAHA]);
    expect(buildExplorerMapStops([BLAHA], new Map(), BOUNDS)).toEqual([BLAHA]);
  });

  it('keeps the catalog dot when every referenced platform is out of view', () => {
    const offscreen: Record<string, FutarStop> = {
      BKK_FAR: { id: 'BKK_FAR', lat: 47.9, lon: 19.9, name: 'Far away' },
    };
    expect(
      buildExplorerMapStops([BLAHA], new Map([[BLAHA.id, arrivals(offscreen)]]), BOUNDS)
    ).toEqual([BLAHA]);
  });
});

describe('buildRadarDepartures mode arrays', () => {
  it('filters to the requested modes', () => {
    const now = Date.now();
    const stops: NearbyStop[] = [
      { id: 's1', name: 'Stop', lat: 47.49, lon: 19.05, distanceMeters: 100 },
    ];
    const boards: NearbyDeparture[] = [
      {
        routeId: 'r-tram',
        shortName: '4',
        routeType: 'TRAM',
        color: 'F7C600',
        textColor: '000000',
        arrivals: [{ arrivalMs: now + 120_000, headSign: 'A', isPredicted: true, tripId: 't1' }],
      },
      {
        routeId: 'r-bus',
        shortName: '7',
        routeType: 'BUS',
        color: '0072BC',
        textColor: 'FFFFFF',
        arrivals: [{ arrivalMs: now + 300_000, headSign: 'B', isPredicted: false, tripId: 't2' }],
      },
      {
        // Night bus: BUS in the catalog even though it runs on tram corridors.
        routeId: 'r-night-bus',
        shortName: '907',
        routeType: 'BUS',
        color: '000000',
        textColor: 'FFFFFF',
        arrivals: [{ arrivalMs: now + 180_000, headSign: 'C', isPredicted: true, tripId: 't3' }],
      },
    ];
    const items = buildRadarDepartures({
      stops,
      stopDeparturesMap: new Map([['s1', boards]]),
      now,
      modeFilter: ['TRAM'],
      maxItems: 10,
    });
    expect(items.map((i) => i.routeId)).toEqual(['r-tram']);
  });
});
