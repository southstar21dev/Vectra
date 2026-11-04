import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { fetchStopArrivalsMock, fetchStopArrivalsBatchMock } = vi.hoisted(() => ({
  fetchStopArrivalsMock: vi.fn(),
  fetchStopArrivalsBatchMock: vi.fn(),
}));

vi.mock('./futar-api', () => ({
  DEEP_ARRIVALS_LIMIT: 200,
  fetchStopArrivals: fetchStopArrivalsMock,
  fetchStopArrivalsBatch: fetchStopArrivalsBatchMock,
}));

import {
  BATCH_WINDOW_MS,
  DEEP_REUSE_MS,
  loadStopArrivals,
  loadStopArrivalsWithLimit,
  resetArrivalsLoader,
} from './arrivals-loader';

const response = (stopId: string) => ({ status: 'OK', data: { entry: { stopId, stopTimes: [] } } });

describe('arrivals loader', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    resetArrivalsLoader();
    fetchStopArrivalsMock.mockReset();
    fetchStopArrivalsBatchMock.mockReset();
    fetchStopArrivalsMock.mockImplementation(async (id: string) => response(id));
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('coalesces stops requested in one window into a single batch call', async () => {
    fetchStopArrivalsBatchMock.mockResolvedValue({
      responses: { BKK_CS1: response('BKK_CS1'), BKK_CS2: response('BKK_CS2') },
      errors: {},
    });
    const a = loadStopArrivals('BKK_CS1');
    const b = loadStopArrivals('BKK_CS2');
    const a2 = loadStopArrivals('BKK_CS1');
    await vi.advanceTimersByTimeAsync(BATCH_WINDOW_MS);

    await expect(a).resolves.toEqual(response('BKK_CS1'));
    await expect(a2).resolves.toEqual(response('BKK_CS1'));
    await expect(b).resolves.toEqual(response('BKK_CS2'));
    expect(fetchStopArrivalsBatchMock).toHaveBeenCalledTimes(1);
    expect(fetchStopArrivalsBatchMock).toHaveBeenCalledWith(['BKK_CS1', 'BKK_CS2'], 90);
    expect(fetchStopArrivalsMock).not.toHaveBeenCalled();
  });

  it('rejects only the stops the batch could not answer', async () => {
    fetchStopArrivalsBatchMock.mockResolvedValue({
      responses: { BKK_CS1: response('BKK_CS1') },
      errors: { BKK_CS2: 'upstream down' },
    });
    const a = loadStopArrivals('BKK_CS1');
    const b = loadStopArrivals('BKK_CS2');
    const settled = Promise.allSettled([a, b]);
    await vi.advanceTimersByTimeAsync(BATCH_WINDOW_MS);
    const [ra, rb] = await settled;
    expect(ra.status).toBe('fulfilled');
    expect(rb.status).toBe('rejected');
    expect((rb as PromiseRejectedResult).reason.message).toBe('upstream down');
  });

  it('uses the single-stop endpoint when only one stop is queued', async () => {
    const a = loadStopArrivals('BKK_CS1');
    await vi.advanceTimersByTimeAsync(BATCH_WINDOW_MS);
    await expect(a).resolves.toEqual(response('BKK_CS1'));
    expect(fetchStopArrivalsMock).toHaveBeenCalledWith('BKK_CS1', 90);
    expect(fetchStopArrivalsBatchMock).not.toHaveBeenCalled();
  });

  it('answers a shallow request from a fresh deep response', async () => {
    const deep = await loadStopArrivalsWithLimit('BKK_CS1', 200);
    expect(fetchStopArrivalsMock).toHaveBeenCalledWith('BKK_CS1', 90, 200);
    await expect(loadStopArrivals('BKK_CS1')).resolves.toBe(deep);
    expect(fetchStopArrivalsMock).toHaveBeenCalledTimes(1);

    // Once stale, the shallow request goes back to the network.
    vi.advanceTimersByTime(DEEP_REUSE_MS + 1);
    const shallow = loadStopArrivals('BKK_CS1');
    await vi.advanceTimersByTimeAsync(BATCH_WINDOW_MS);
    await shallow;
    expect(fetchStopArrivalsMock).toHaveBeenCalledTimes(2);
  });

  it('never reuses a failed deep response', async () => {
    fetchStopArrivalsMock.mockRejectedValueOnce(new Error('nope'));
    await expect(loadStopArrivalsWithLimit('BKK_CS1', 200)).rejects.toThrow('nope');
    const shallow = loadStopArrivals('BKK_CS1');
    await vi.advanceTimersByTimeAsync(BATCH_WINDOW_MS);
    await expect(shallow).resolves.toEqual(response('BKK_CS1'));
  });
});
