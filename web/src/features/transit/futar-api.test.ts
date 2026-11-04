import { beforeEach, describe, expect, it, vi } from 'vitest';

// vi.hoisted: vi.mock factories are hoisted above module-scope declarations.
const { sendMock } = vi.hoisted(() => ({ sendMock: vi.fn() }));

vi.mock('@/lib/pocketbase', () => ({
  pb: { send: sendMock },
}));

import { fetchStopArrivals } from './futar-api';

const ok = {
  status: 'OK',
  currentTime: 1,
  data: { entry: { stopId: 'x', stopTimes: [] } },
};

/** The `{ endpoint, params }` body the transport posted to the proxy route. */
function postedBody() {
  const options = sendMock.mock.calls[0][1] as {
    body: { endpoint: string; params: Record<string, unknown> };
  };
  return options.body;
}

describe('fetchStopArrivals', () => {
  beforeEach(() => {
    sendMock.mockReset();
    sendMock.mockResolvedValue(ok);
  });

  it('posts to the PocketBase futar route', async () => {
    await fetchStopArrivals('BKK_123', 90);
    expect(sendMock.mock.calls[0][0]).toBe('/api/vectra/futar');
    expect((sendMock.mock.calls[0][1] as { method: string }).method).toBe('POST');
  });

  it('forwards the deep limit so chained arrivals stay visible', async () => {
    await fetchStopArrivals('BKK_123', 90, 200);
    expect(postedBody()).toEqual({
      endpoint: 'arrivals-and-departures-for-stop',
      params: {
        stopId: 'BKK_123',
        minutesBefore: 0,
        minutesAfter: 90,
        limit: 200,
      },
    });
  });

  it('omits the limit for the compact tracker fetch', async () => {
    await fetchStopArrivals('BKK_123', 90);
    expect(postedBody()).toEqual({
      endpoint: 'arrivals-and-departures-for-stop',
      params: {
        stopId: 'BKK_123',
        minutesBefore: 0,
        minutesAfter: 90,
      },
    });
  });
});
