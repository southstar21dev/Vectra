import { describe, expect, it } from 'vitest';
import { expandStopArrivalsBatch, type FutarStopArrivalsBatchResponse } from './arrivals-batch';

const PAYLOAD: FutarStopArrivalsBatchResponse = {
  currentTime: 1_700_000_000_000,
  status: 'OK',
  data: {
    shared: {
      routes: {
        BKK_5400: {
          id: 'BKK_5400',
          shortName: 'M4',
          description: 'Kelenföld vasútállomás M | Keleti pályaudvar M',
          type: 'SUBWAY',
          color: '12A13C',
          textColor: 'FFFFFF',
        },
      },
      trips: { 'trip-m4-1': { routeId: 'BKK_5400', tripHeadsign: 'Keleti pályaudvar M' } },
      alerts: { alert1: { id: 'alert1', effectType: 'DETOUR' } },
    },
    arrives: {
      BKK_CSF01108: {
        stopId: 'BKK_CSF01108',
        stopTimes: [
          {
            stopId: 'BKK_F00073',
            stopHeadsign: 'Keleti pályaudvar M',
            predictedArrivalTime: 1_700_000_180,
            tripId: 'trip-m4-1',
          },
        ],
        alertIds: ['alert1'],
        stops: {
          BKK_F00073: { id: 'BKK_F00073', name: 'Astoria M', lat: 47.4944, lon: 19.0607 },
        },
      },
      BKK_CS002: { error: 'Futár returned HTTP 502' },
    },
  },
};

describe('expandStopArrivalsBatch', () => {
  it('rebuilds per-stop arrivals responses with shared references attached', () => {
    const { responses, errors } = expandStopArrivalsBatch(PAYLOAD);

    const entry = responses.BKK_CSF01108;
    expect(entry.currentTime).toBe(1_700_000_000_000);
    expect(entry.status).toBe('OK');
    expect(entry.data?.entry?.stopId).toBe('BKK_CSF01108');
    expect(entry.data?.entry?.stopTimes).toHaveLength(1);
    expect(entry.data?.entry?.alertIds).toEqual(['alert1']);
    expect(entry.data?.references?.routes?.BKK_5400?.shortName).toBe('M4');
    expect(entry.data?.references?.trips?.['trip-m4-1']?.routeId).toBe('BKK_5400');
    expect(entry.data?.references?.alerts?.alert1?.effectType).toBe('DETOUR');
    // Platform references stay per-stop so a station's own dots resolve.
    expect(entry.data?.references?.stops?.BKK_F00073?.name).toBe('Astoria M');

    expect(errors.BKK_CS002).toBe('Futár returned HTTP 502');
    expect(responses.BKK_CS002).toBeUndefined();
  });

  it('does not leak another stop\'s platform references into a stop', () => {
    const { responses } = expandStopArrivalsBatch({
      currentTime: 1,
      data: {
        shared: { stops: { OTHER: { id: 'OTHER', name: 'Elsewhere', lat: 1, lon: 2 } } },
        arrives: { A: { stopId: 'A', stopTimes: [], stops: {} } },
      },
    });
    expect(responses.A.data?.references?.stops).toEqual({});
  });

  it('defaults missing collections instead of throwing', () => {
    const { responses, errors } = expandStopArrivalsBatch({
      data: { arrives: { A: { stopId: 'A' } } },
    });
    expect(responses.A.data?.entry?.stopTimes).toEqual([]);
    expect(responses.A.data?.entry?.alertIds).toBeUndefined();
    expect(responses.A.data?.references).toEqual({ routes: {}, trips: {}, alerts: {}, stops: {} });
    expect(errors).toEqual({});
  });

  it('returns empty results for a missing or malformed payload', () => {
    for (const payload of [undefined, null, {}, { data: {} }, { data: { arrives: undefined } }]) {
      expect(expandStopArrivalsBatch(payload)).toEqual({ responses: {}, errors: {} });
    }
  });
});
