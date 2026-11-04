import { describe, expect, it } from 'vitest';
import {
  normalizeSearchText,
  parseAlerts,
  stripHtml,
  isRouteMatch,
  formatRouteDisplayName,
  parseStopArrivals,
  getTrackerStops,
  addOrUpdateTrackerStop,
  removeTrackerStop,
  normalizeStopIdToStation,
  extractHeadsignDestination,
  isHeadsignMatch,
  formatCountdown,
  formatPrimeCountdown,
  formatCompactCountdown,
  COMPACT_COUNTDOWN_MAX_MINUTES,
  isArrivalsStale,
  arrivalsStaleNow,
  ARRIVALS_STALE_MS,
  MAX_TRACKER_STOPS,
  MAX_FAVORITE_TRACKERS,
} from './transit-utils';
import type { FutarAlert, FutarArrivalsResponse } from './types';

const NOW = 1_800_000_000_000; // fixed epoch ms

const CTX = { routeId: 'BKK_9220', routeShortName: '922', routeType: 'BUS', stopId: 'BKK_F04788' };

/** arrivals response with references.alerts (entry.alertIds deliberately empty). */
function responseWithRefAlerts(
  alerts: Array<{
    id: string;
    routeIds?: string[];
    stopIds?: string[];
    vehicleRouteTypes?: string[];
    start?: number;
    end?: number;
    effectType?: string;
  }>
): FutarArrivalsResponse {
  const refs: Record<string, FutarAlert> = {};
  for (const a of alerts) {
    refs[a.id] = {
      id: a.id,
      start: a.start,
      end: a.end,
      routeIds: a.routeIds,
      stopIds: a.stopIds,
      vehicleRouteTypes: a.vehicleRouteTypes,
      routes: (a.routeIds ?? []).map((routeId) => ({ routeId, effectType: a.effectType })),
      header: { someTranslation: `header-${a.id}` },
      description: { someTranslation: `desc-${a.id}` },
    };
  }
  return {
    currentTime: NOW,
    status: 'OK',
    data: {
      entry: { stopId: CTX.stopId, stopTimes: [], alertIds: [] },
      references: {
        alerts: refs,
        routes: {
          BKK_9220: { id: 'BKK_9220', shortName: '922', description: '', type: 'BUS', color: '000000', textColor: 'FFFFFF' },
          BKK_VP51: { id: 'BKK_VP51', shortName: '51', description: '', type: 'TRAM', color: 'FFD800', textColor: '000000' },
          BKK_MV123: { id: 'BKK_MV123', shortName: 'S60', description: '', type: 'RAIL', color: '0055AA', textColor: 'FFFFFF' },
        },
        stops: {
          [CTX.stopId]: { id: CTX.stopId, name: 'Test stop', lat: 47.5, lon: 19.0, parentStationId: 'BKK_F00001' },
        },
      },
    },
  };
}

describe('stripHtml', () => {
  it('removes tags, decodes entities and collapses whitespace', () => {
    expect(stripHtml('<ul><li>Tram <strong>2B</strong> does not operate &amp; is replaced by bus <i>223E</i>.</li></ul>')).toBe(
      'Tram 2B does not operate & is replaced by bus 223E.'
    );
  });

  it('handles line breaks, nbsp and numeric entities', () => {
    expect(stripHtml('line 1<br>line 2&nbsp;&nbsp;x')).toBe('line 1 line 2 x');
    expect(stripHtml('M3 &#8211; megszűnik')).toBe('M3 – megszűnik');
  });
});

describe('normalizeSearchText', () => {
  it('strips Hungarian diacritics and lowercases', () => {
    expect(normalizeSearchText('Széll Kálmán tér')).toBe('szell kalman ter');
    expect(normalizeSearchText('Hősök tere')).toBe('hosok tere');
    expect(normalizeSearchText('Deák Ferenc tér')).toBe('deak ferenc ter');
  });
});

describe('parseAlerts', () => {
  it('returns nothing when there is no data', () => {
    expect(parseAlerts(undefined, CTX, NOW)).toEqual([]);
  });

  it('shows route-scoped alerts whose routeIds include the tracked route (entry.alertIds empty)', () => {
    const res = responseWithRefAlerts([{ id: 'A1', routeIds: ['BKK_9220'], start: 1 }]);
    expect(parseAlerts(res, CTX, NOW).map((a) => a.id)).toEqual(['A1']);
  });

  it('hides route-scoped alerts for other lines at the same stop (the 985 false positive)', () => {
    const res = responseWithRefAlerts([{ id: 'A-other', routeIds: ['BKK_9230'], start: 1 }]);
    expect(parseAlerts(res, CTX, NOW)).toEqual([]);
  });

  it('matches by short name when ids differ, honoring vehicleRouteTypes', () => {
    const res = responseWithRefAlerts([
      { id: 'A-mismatch', routeIds: ['BKK_3510'], vehicleRouteTypes: ['TRAM'], start: 1 },
    ]);
    expect(
      parseAlerts(
        res,
        { ...CTX, routeId: 'BKK_VP51', routeShortName: '51', routeType: 'TRAM' },
        NOW,
        (id) => (id === 'BKK_3510' ? '51' : undefined)
      ).map((a) => a.id)
    ).toEqual(['A-mismatch']);
  });

  it('does not match same short name with a conflicting vehicle type', () => {
    const res = responseWithRefAlerts([
      { id: 'A-bus51', routeIds: ['BKK_3510'], vehicleRouteTypes: ['BUS'], start: 1 },
    ]);
    expect(
      parseAlerts(
        res,
        { ...CTX, routeId: 'BKK_VP51', routeShortName: '51', routeType: 'TRAM' },
        NOW,
        (id) => (id === 'BKK_3510' ? '51' : undefined)
      )
    ).toEqual([]);
  });

  it('shows stop-attached alerts matching the stop id or its parent station', () => {
    const byStop = responseWithRefAlerts([{ id: 'A-stop', stopIds: [CTX.stopId], start: 1 }]);
    expect(parseAlerts(byStop, CTX, NOW).map((a) => a.id)).toEqual(['A-stop']);
    const byParent = responseWithRefAlerts([{ id: 'A-parent', stopIds: ['BKK_F00001'], start: 1 }]);
    expect(parseAlerts(byParent, CTX, NOW).map((a) => a.id)).toEqual(['A-parent']);
  });

  it('hides a stop-scoped alert whose vehicleRouteTypes exclude the tracked route type', () => {
    // Railway alert at a shared intermodal station — must not show on a bus.
    const res = responseWithRefAlerts([{ id: 'A-rail', stopIds: ['BKK_F00001'], vehicleRouteTypes: ['RAIL'], start: 1 }]);
    expect(parseAlerts(res, CTX, NOW)).toEqual([]);
  });

  it('shows a stop-scoped alert whose vehicleRouteTypes include the tracked route type', () => {
    const res = responseWithRefAlerts([{ id: 'A-bus', stopIds: ['BKK_F00001'], vehicleRouteTypes: ['BUS'], start: 1 }]);
    expect(parseAlerts(res, CTX, NOW).map((a) => a.id)).toEqual(['A-bus']);
  });

  it('shows a stop-scoped alert with no vehicleRouteTypes (generic — affects all modes)', () => {
    const res = responseWithRefAlerts([{ id: 'A-generic', stopIds: ['BKK_F00001'], start: 1 }]);
    expect(parseAlerts(res, CTX, NOW).map((a) => a.id)).toEqual(['A-generic']);
  });

  it('hides a stop-scoped alert whose routeIds resolve to a different type (fallback)', () => {
    // Railway alert at a shared stop with routeIds for rail lines only.
    const res = responseWithRefAlerts([{ id: 'A-rail-fb', stopIds: ['BKK_F00001'], routeIds: ['BKK_MV123'], start: 1 }]);
    expect(parseAlerts(res, CTX, NOW)).toEqual([]);
  });

  it('hides a stop-scoped alert whose routeIds resolve via the extra type resolver', () => {
    const res = responseWithRefAlerts([{ id: 'A-external', stopIds: ['BKK_F00001'], routeIds: ['BKK_XTRAIN'], start: 1 }]);
    expect(
      parseAlerts(res, CTX, NOW, undefined, (id) => (id === 'BKK_XTRAIN' ? 'RAIL' : undefined))
    ).toEqual([]);
  });

  it('hides an agency-wide alert whose vehicleRouteTypes exclude the tracked type', () => {
    const res = responseWithRefAlerts([{ id: 'A-agency-rail', start: 1, vehicleRouteTypes: ['RAIL'] }]);
    expect(parseAlerts(res, CTX, NOW)).toEqual([]);
  });

  it('shows agency-wide alerts sorted last', () => {
    const res = responseWithRefAlerts([
      { id: 'A-noservice', routeIds: ['BKK_9220'], start: 1, effectType: 'NO_SERVICE' },
      { id: 'A-agency', start: 1 },
    ]);
    const ids = parseAlerts(res, CTX, NOW).map((a) => a.id);
    expect(ids).toEqual(['A-noservice', 'A-agency']);
  });

  it('applies the validity window', () => {
    const res = responseWithRefAlerts([
      { id: 'A-active', routeIds: ['BKK_9220'], start: 1, end: NOW / 1000 + 1000 },
      { id: 'A-expired', routeIds: ['BKK_9220'], start: 1, end: NOW / 1000 - 1 },
    ]);
    expect(parseAlerts(res, CTX, NOW).map((a) => a.id)).toEqual(['A-active']);
  });

  it('picks the english header and strips HTML from the description', () => {
    const res = responseWithRefAlerts([{ id: 'A1', routeIds: ['BKK_9220'], start: 1 }]);
    // responseWithRefAlerts uses someTranslation only; verify the pass-through.
    const [a] = parseAlerts(res, CTX, NOW);
    expect(a.header).toBe('header-A1');
    expect(a.description).toBe('desc-A1');
  });
});

describe('isRouteMatch & Combined Tram Lines (37/37A, 28/28A, 4/6)', () => {
  it('matches 37 with 37A and vice versa', () => {
    expect(isRouteMatch('BKK_3370', 'BKK_3371')).toBe(true);
    expect(isRouteMatch('BKK_3371', 'BKK_3370')).toBe(true);
    expect(isRouteMatch('BKK_3370', 'BKK_3370')).toBe(true);
  });

  it('matches 28 with 28A and vice versa', () => {
    expect(isRouteMatch('BKK_3280', 'BKK_3281')).toBe(true);
    expect(isRouteMatch('BKK_3281', 'BKK_3280')).toBe(true);
    expect(isRouteMatch('OTHER_28', 'BKK_3281', '28', '28A')).toBe(true);
  });

  it('matches 4 with 6 and vice versa', () => {
    expect(isRouteMatch('BKK_3040', 'BKK_3060')).toBe(true);
    expect(isRouteMatch('BKK_3060', 'BKK_3040')).toBe(true);
    expect(isRouteMatch('OTHER_4', 'BKK_3060', '4', '6')).toBe(true);
    expect(isRouteMatch('OTHER_6', 'BKK_3040', '6', '4')).toBe(true);
    expect(isRouteMatch('BKK_3046', 'BKK_3040')).toBe(true);
    expect(isRouteMatch('BKK_3046', 'BKK_3060')).toBe(true);
    expect(isRouteMatch('volan_H5', 'BKK_H5')).toBe(true);
  });

  it('does NOT match unrelated routes across different groups', () => {
    expect(isRouteMatch('BKK_3370', 'BKK_9220')).toBe(false);
    expect(isRouteMatch('BKK_3040', 'BKK_3370')).toBe(false);
    expect(isRouteMatch('BKK_3280', 'BKK_3060')).toBe(false);
    expect(isRouteMatch('BKK_H5', 'BKK_3040')).toBe(false);
  });
});

describe('formatRouteDisplayName', () => {
  it('formats combined lines with full or compact display names', () => {
    expect(formatRouteDisplayName('37', 'BKK_3370')).toBe('37 / 37A');
    expect(formatRouteDisplayName('37A', 'BKK_3371')).toBe('37 / 37A');
    expect(formatRouteDisplayName('37', 'BKK_3370', true)).toBe('37/37A');

    expect(formatRouteDisplayName('28', 'BKK_3280')).toBe('28 / 28A');
    expect(formatRouteDisplayName('28A', 'BKK_3281')).toBe('28 / 28A');
    expect(formatRouteDisplayName('28', 'BKK_3280', true)).toBe('28/28A');

    expect(formatRouteDisplayName('4', 'BKK_3040')).toBe('4 / 6');
    expect(formatRouteDisplayName('6', 'BKK_3060')).toBe('4 / 6');
    expect(formatRouteDisplayName('4', 'BKK_3040', true)).toBe('4/6');
  });

  it('preserves normal route names', () => {
    expect(formatRouteDisplayName('922', 'BKK_9220')).toBe('922');
    expect(formatRouteDisplayName('M3', 'BKK_5300')).toBe('M3');
    expect(formatRouteDisplayName('5', 'BKK_0050')).toBe('5');
  });
});

describe('isHeadsignMatch & via destination extraction', () => {
  it('extracts destinations correctly from via signs', () => {
    expect(extractHeadsignDestination('Salgótarjáni utca ► Sörgyár')).toBe('Sörgyár');
    expect(extractHeadsignDestination('Kőbánya alsó vá. > Kőbánya felső')).toBe('Kőbánya felső');
    expect(extractHeadsignDestination('Blaha Lujza tér M')).toBe('Blaha Lujza tér M');
  });

  it('matches when stopHeadsign has via indicator (e.g. Salgótarjáni utca ► Sörgyár vs Sörgyár)', () => {
    // Exact bug reported by user: Tram 37 at II. János Pál pápa tér choosing direction Sörgyár
    expect(isHeadsignMatch('Sörgyár', 'Salgótarjáni utca ► Sörgyár', 'Sörgyár')).toBe(true);
    expect(isHeadsignMatch('sörgyár', 'Salgótarjáni utca ► Sörgyár', 'Sörgyár')).toBe(true);
    expect(isHeadsignMatch('Sörgyár', 'Salgótarjáni utca ► Sörgyár', undefined)).toBe(true);
  });

  it('matches canonical tripHeadsign even if stopHeadsign is specialized', () => {
    expect(isHeadsignMatch('Új köztemető (Kozma u.)', 'Új köztemető', 'Új köztemető (Kozma u.)')).toBe(true);
    expect(isHeadsignMatch('Széll Kálmán tér M', 'Széll Kálmán tér', 'Széll Kálmán tér M')).toBe(true);
  });

  it('matches all when targetHeadsign is empty', () => {
    expect(isHeadsignMatch('', 'Salgótarjáni utca ► Sörgyár', 'Sörgyár')).toBe(true);
    expect(isHeadsignMatch(null, 'Blaha Lujza tér M', 'Blaha Lujza tér M')).toBe(true);
  });

  it('does not match conflicting destinations', () => {
    expect(isHeadsignMatch('Blaha Lujza tér M', 'Salgótarjáni utca ► Sörgyár', 'Sörgyár')).toBe(false);
    expect(isHeadsignMatch('Széll Kálmán tér M', 'Újbuda-központ M', 'Újbuda-központ M')).toBe(false);
  });
});

describe('parseStopArrivals for 37/37A combining', () => {
  const futureSec = Math.floor(Date.now() / 1000) + 300;
  const mockResponse: FutarArrivalsResponse = {
    currentTime: Date.now(),
    status: 'OK',
    data: {
      entry: {
        stopId: 'BKK_F01234',
        stopTimes: [
          {
            stopId: 'BKK_F01234',
            tripId: 'TRIP_37A_1',
            stopHeadsign: 'Blaha Lujza tér M',
            predictedArrivalTime: futureSec,
          },
          {
            stopId: 'BKK_F01234',
            tripId: 'TRIP_37_1',
            stopHeadsign: 'Új köztemető (Kozma utca)',
            predictedArrivalTime: futureSec + 300,
          },
        ],
      },
      references: {
        trips: {
          TRIP_37A_1: { routeId: 'BKK_3371' },
          TRIP_37_1: { routeId: 'BKK_3370' },
        },
        routes: {
          BKK_3371: { id: 'BKK_3371', shortName: '37A', description: '', type: 'TRAM', color: 'FFD800', textColor: '000000' },
          BKK_3370: { id: 'BKK_3370', shortName: '37', description: '', type: 'TRAM', color: 'FFD800', textColor: '000000' },
        },
      },
    },
  };

  it('parses both 37 and 37A arrivals and retains routeShortName and routeId', () => {
    const arrivals = parseStopArrivals(mockResponse, 'BKK_3370');
    expect(arrivals).toHaveLength(2);
    expect(arrivals[0].routeShortName).toBe('37A');
    expect(arrivals[0].routeId).toBe('BKK_3371');
    expect(arrivals[0].headSign).toBe('Blaha Lujza tér M');

    expect(arrivals[1].routeShortName).toBe('37');
    expect(arrivals[1].routeId).toBe('BKK_3370');
    expect(arrivals[1].headSign).toBe('Új köztemető (Kozma utca)');
  });

  it('matches arrivals when user selects Sörgyár at stops displaying via signs (II. János Pál pápa tér bug)', () => {
    const viaResponse: FutarArrivalsResponse = {
      currentTime: Date.now(),
      status: 'OK',
      data: {
        entry: {
          stopId: 'BKK_CS056232', // II. János Pál pápa tér
          stopTimes: [
            {
              stopId: 'BKK_CS056232',
              tripId: 'TRIP_VIA_1',
              stopHeadsign: 'Salgótarjáni utca ► Sörgyár',
              predictedArrivalTime: futureSec,
            },
            {
              stopId: 'BKK_CS056232',
              tripId: 'TRIP_OTHER_1',
              stopHeadsign: 'Blaha Lujza tér M',
              predictedArrivalTime: futureSec + 120,
            },
          ],
        },
        references: {
          trips: {
            TRIP_VIA_1: { id: 'TRIP_VIA_1', routeId: 'BKK_3371', tripHeadsign: 'Sörgyár' },
            TRIP_OTHER_1: { id: 'TRIP_OTHER_1', routeId: 'BKK_3371', tripHeadsign: 'Blaha Lujza tér M' },
          },
          routes: {
            BKK_3371: { id: 'BKK_3371', shortName: '37A', description: '', type: 'TRAM', color: 'FFD800', textColor: '000000' },
          },
        },
      },
    };

    // User filters by target direction 'Sörgyár' on Tram 37 (BKK_3370)
    const arrivals = parseStopArrivals(viaResponse, 'BKK_3370', 'Sörgyár');
    expect(arrivals).toHaveLength(1);
    expect(arrivals[0].tripId).toBe('TRIP_VIA_1');
    expect(arrivals[0].headSign).toBe('Salgótarjáni utca ► Sörgyár');
    expect(arrivals[0].tripHeadsign).toBe('Sörgyár');
  });
});

describe('Multi-stop tracker utilities', () => {
  it('normalizes legacy single-stop trackers into a 1-element TrackerStop array', () => {
    const legacyTracker: any = {
      id: 't-1',
      user_id: 'u-1',
      stop_id: 'BKK_F01000',
      stop_name: 'Nyugati pályaudvar M',
      route_id: 'BKK_0040',
      route_short_name: '4',
      route_long_name: 'Tram 4',
      route_type: 'TRAM',
      route_color: 'FFD800',
      route_text_color: '000000',
      headsign: 'Újbuda-központ M',
      is_favorite: false,
      dedupe_key: 'key-1',
      created_at: new Date().toISOString(),
      stops: null,
    };
    const stops = getTrackerStops(legacyTracker);
    expect(stops).toEqual([
      {
        stop_id: 'BKK_F01000',
        stop_name: 'Nyugati pályaudvar M',
        headsign: 'Újbuda-központ M',
      },
    ]);
  });

  it('reads multi-stop array when present', () => {
    const multiStopTracker: any = {
      id: 't-2',
      user_id: 'u-1',
      stop_id: 'BKK_F01000',
      stop_name: 'Nyugati pályaudvar M',
      route_id: 'BKK_0040',
      route_short_name: '4',
      route_long_name: 'Tram 4',
      route_type: 'TRAM',
      route_color: 'FFD800',
      route_text_color: '000000',
      headsign: '',
      is_favorite: true,
      dedupe_key: 'key-2',
      created_at: new Date().toISOString(),
      stops: [
        { stop_id: 'BKK_F01000', stop_name: 'Nyugati pályaudvar M', headsign: '' },
        { stop_id: 'BKK_F01001', stop_name: 'Oktogon M', headsign: 'Széll Kálmán tér M' },
      ],
    };
    const stops = getTrackerStops(multiStopTracker);
    expect(stops).toHaveLength(2);
    expect(stops[1].stop_name).toBe('Oktogon M');
    expect(stops[1].headsign).toBe('Széll Kálmán tér M');
  });

  it('reads JSON string stops array when present', () => {
    const jsonTracker: any = {
      id: 't-3',
      user_id: 'u-1',
      stop_id: 'BKK_F01000',
      stop_name: 'Nyugati pályaudvar M',
      route_id: 'BKK_0040',
      route_short_name: '4',
      route_long_name: 'Tram 4',
      route_type: 'TRAM',
      route_color: 'FFD800',
      route_text_color: '000000',
      headsign: '',
      is_favorite: false,
      dedupe_key: 'key-3',
      created_at: new Date().toISOString(),
      stops: JSON.stringify([
        { stop_id: 'BKK_F01000', stop_name: 'Nyugati pályaudvar M', headsign: '' },
      ]),
    };
    const stops = getTrackerStops(jsonTracker);
    expect(stops).toHaveLength(1);
    expect(stops[0].stop_name).toBe('Nyugati pályaudvar M');
  });

  it('adds or updates a stop in a stops list', () => {
    const stops = [{ stop_id: 's1', stop_name: 'Stop 1', headsign: '' }];
    const updated = addOrUpdateTrackerStop(stops, { stop_id: 's1', stop_name: 'Stop 1', headsign: 'Direction A' });
    expect(updated).toEqual([{ stop_id: 's1', stop_name: 'Stop 1', headsign: 'Direction A' }]);

    const added = addOrUpdateTrackerStop(updated, { stop_id: 's2', stop_name: 'Stop 2', headsign: '' });
    expect(added).toHaveLength(2);
    expect(added[1].stop_id).toBe('s2');

    const added3 = addOrUpdateTrackerStop(added, { stop_id: 's3', stop_name: 'Stop 3', headsign: '' });
    const added4 = addOrUpdateTrackerStop(added3, { stop_id: 's4', stop_name: 'Stop 4', headsign: '' });
    expect(added4).toHaveLength(4);

    // 5th stop should not be added
    const added5 = addOrUpdateTrackerStop(added4, { stop_id: 's5', stop_name: 'Stop 5', headsign: '' });
    expect(added5).toHaveLength(4);
    expect(added5.map((s) => s.stop_id)).toEqual(['s1', 's2', 's3', 's4']);
  });

  it('removes a stop from a stops list', () => {
    const stops = [
      { stop_id: 's1', stop_name: 'Stop 1', headsign: '' },
      { stop_id: 's2', stop_name: 'Stop 2', headsign: '' },
    ];
    const remaining = removeTrackerStop(stops, 's1');
    expect(remaining).toEqual([{ stop_id: 's2', stop_name: 'Stop 2', headsign: '' }]);
  });

  describe('normalizeStopIdToStation', () => {
    it('returns station id unchanged when already starting with BKK_CS or AREA_CS', () => {
      expect(normalizeStopIdToStation('BKK_CSF01330')).toBe('BKK_CSF01330');
      expect(normalizeStopIdToStation('BKK_CS011732')).toBe('BKK_CS011732');
      expect(normalizeStopIdToStation('AREA_CS778816_1')).toBe('AREA_CS778816_1');
    });

    it('converts platform ids starting with BKK_F to BKK_CSF', () => {
      expect(normalizeStopIdToStation('BKK_F01330')).toBe('BKK_CSF01330');
      expect(normalizeStopIdToStation('BKK_F02072')).toBe('BKK_CSF02072');
    });

    it('converts 6-digit platform ids starting with BKK_ to BKK_CS', () => {
      expect(normalizeStopIdToStation('BKK_011732')).toBe('BKK_CS011732');
      expect(normalizeStopIdToStation('BKK_009543')).toBe('BKK_CS009543');
    });

    it('normalizes 8-digit HÉV platform ids to their 5-digit station without invalid CS prefix', () => {
      expect(normalizeStopIdToStation('BKK_09001188')).toBe('BKK_09001');
      expect(normalizeStopIdToStation('BKK_19720236')).toBe('BKK_19720');
    });

    it('preserves 5-digit HÉV station ids unchanged', () => {
      expect(normalizeStopIdToStation('BKK_09001')).toBe('BKK_09001');
      expect(normalizeStopIdToStation('BKK_09019')).toBe('BKK_09019');
      expect(normalizeStopIdToStation('BKK_19720')).toBe('BKK_19720');
    });

    it('handles null and empty values gracefully', () => {
      expect(normalizeStopIdToStation('')).toBe('');
      expect(normalizeStopIdToStation(null)).toBe('');
      expect(normalizeStopIdToStation(undefined)).toBe('');
    });
  });

  describe('Constants', () => {
    it('enforces expected limits for stops and favorites', () => {
      expect(MAX_TRACKER_STOPS).toBe(4);
      expect(MAX_FAVORITE_TRACKERS).toBe(5);
    });
  });

  describe('formatCountdown', () => {
    it('reads NOW at or below zero and folds hours into total minutes', () => {
      expect(formatCountdown(0)).toBe('NOW');
      expect(formatCountdown(-3)).toBe('NOW');
      expect(formatCountdown(3)).toBe('3 MIN');
      expect(formatCountdown(59)).toBe('59 MIN');
      expect(formatCountdown(60)).toBe('60 MIN');
      expect(formatCountdown(65)).toBe('65 MIN');
      expect(formatCountdown(120)).toBe('120 MIN');
    });
  });

  describe('formatPrimeCountdown', () => {
    it('reads NOW at or below zero and prime-marked total minutes beyond', () => {
      expect(formatPrimeCountdown(0)).toBe('NOW');
      expect(formatPrimeCountdown(-4)).toBe('NOW');
      expect(formatPrimeCountdown(2)).toBe("2'");
      expect(formatPrimeCountdown(73)).toBe("73'");
      expect(formatPrimeCountdown(120)).toBe("120'");
    });
  });

  describe('formatCompactCountdown', () => {
    it('reads NOW at or below zero and prime-marked minutes under the horizon', () => {
      expect(formatCompactCountdown(0)).toBe('NOW');
      expect(formatCompactCountdown(-2)).toBe('NOW');
      expect(formatCompactCountdown(2)).toBe("2'");
      expect(formatCompactCountdown(59)).toBe("59'");
    });

    it('folds hours into minutes up to the 99-minute horizon', () => {
      expect(formatCompactCountdown(60)).toBe("60'");
      expect(formatCompactCountdown(78)).toBe("78'");
      expect(formatCompactCountdown(COMPACT_COUNTDOWN_MAX_MINUTES)).toBe("99'");
    });

    it('returns null past the horizon so the caller drops the row', () => {
      expect(formatCompactCountdown(100)).toBeNull();
      expect(formatCompactCountdown(180)).toBeNull();
    });
  });

  describe('arrivals staleness contract', () => {
    it('is stale only once a snapshot outlives the 60s budget', () => {
      expect(isArrivalsStale(NOW - ARRIVALS_STALE_MS, NOW, false)).toBe(false);
      expect(isArrivalsStale(NOW - ARRIVALS_STALE_MS - 1, NOW, false)).toBe(true);
    });

    it('never marks a snapshot with no timestamp as stale (nothing to freeze yet)', () => {
      expect(isArrivalsStale(undefined, NOW, false)).toBe(false);
      expect(isArrivalsStale(0, NOW, false)).toBe(false);
    });

    it('treats every snapshot as stale while offline', () => {
      expect(isArrivalsStale(NOW, NOW, true)).toBe(true);
      expect(arrivalsStaleNow(NOW, NOW, true)).toBe(NOW);
    });

    it('freezes the countdown clock at the snapshot time when stale', () => {
      const snapshot = NOW - ARRIVALS_STALE_MS - 5_000;
      expect(arrivalsStaleNow(snapshot, NOW, false)).toBe(snapshot);
      expect(arrivalsStaleNow(NOW - 5_000, NOW, false)).toBe(NOW);
    });
  });
});
