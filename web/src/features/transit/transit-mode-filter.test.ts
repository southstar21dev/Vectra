import { describe, expect, it } from 'vitest';
import {
  createModeFilterWriteQueue,
  parseModeFilter,
  sameModeFilter,
  selectSingleModeFilter,
} from './transit-mode-filter';

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

describe('parseModeFilter', () => {
  it('reads the array form and uppercases entries', () => {
    expect(parseModeFilter({ transit_mode_filter: ['tram', 'BUS'] })).toEqual(['TRAM', 'BUS']);
  });

  it('drops non-string array entries', () => {
    expect(parseModeFilter({ transit_mode_filter: ['TRAM', 3, null] })).toEqual(['TRAM']);
  });

  it('reads the legacy single string', () => {
    expect(parseModeFilter({ transit_mode_filter: 'tram' })).toEqual(['TRAM']);
  });

  it('treats the legacy ALL string and missing values as no filter', () => {
    expect(parseModeFilter({ transit_mode_filter: 'ALL' })).toEqual([]);
    expect(parseModeFilter({})).toEqual([]);
    expect(parseModeFilter(null)).toEqual([]);
  });

  it('drops ALL entries from the array form', () => {
    expect(parseModeFilter({ transit_mode_filter: ['TRAM', 'ALL'] })).toEqual(['TRAM']);
    expect(parseModeFilter({ transit_mode_filter: ['all'] })).toEqual([]);
  });
});

describe('selectSingleModeFilter', () => {
  it('replaces the active mode with the tapped one', () => {
    expect(selectSingleModeFilter(['BUS'], 'TRAM')).toEqual(['TRAM']);
  });

  it('toggles the active mode off when it is the only selection', () => {
    expect(selectSingleModeFilter(['TRAM'], 'TRAM')).toEqual([]);
  });

  it('collapses a multi-mode selection to the tapped mode', () => {
    expect(selectSingleModeFilter(['BUS', 'TRAM'], 'TRAM')).toEqual(['TRAM']);
  });

  it('clears everything for ALL', () => {
    expect(selectSingleModeFilter(['TRAM'], 'ALL')).toEqual([]);
    expect(selectSingleModeFilter(['TRAM'], 'all')).toEqual([]);
  });

  it('normalizes the incoming id', () => {
    expect(selectSingleModeFilter([], 'tram')).toEqual(['TRAM']);
  });
});

describe('sameModeFilter', () => {
  it('compares order-insensitively', () => {
    expect(sameModeFilter(['BUS', 'TRAM'], ['TRAM', 'BUS'])).toBe(true);
    expect(sameModeFilter([], [])).toBe(true);
    expect(sameModeFilter(['TRAM'], ['TRAM', 'BUS'])).toBe(false);
    expect(sameModeFilter(['TRAM'], ['BUS'])).toBe(false);
  });
});

describe('createModeFilterWriteQueue', () => {
  it('serializes writes: the next only starts after the previous settles', async () => {
    const calls: string[][] = [];
    const gate = deferred<void>();
    const write = (filter: string[]) => {
      calls.push(filter);
      return gate.promise;
    };
    const queue = createModeFilterWriteQueue();

    const first = queue(['TRAM'], write);
    await flush();
    expect(calls).toEqual([['TRAM']]);

    const second = queue(['BUS'], write);
    await flush();
    expect(calls).toEqual([['TRAM']]);

    gate.resolve();
    await Promise.all([first, second]);
    expect(calls).toEqual([['TRAM'], ['BUS']]);
  });

  it('collapses a rapid burst to the last selected filter', async () => {
    const calls: string[][] = [];
    const gate = deferred<void>();
    const write = (filter: string[]) => {
      calls.push(filter);
      return gate.promise;
    };
    const queue = createModeFilterWriteQueue();

    const first = queue(['TRAM'], write);
    const second = queue(['BUS'], write);
    const third = queue(['TRAM'], write);
    gate.resolve();
    await Promise.all([first, second, third]);

    expect(calls).toEqual([['TRAM']]);
  });

  it('skips a write that repeats the last successfully written value', async () => {
    const calls: string[][] = [];
    const queue = createModeFilterWriteQueue();
    const write = async (filter: string[]) => {
      calls.push(filter);
    };

    await queue(['TRAM'], write);
    await queue(['TRAM'], write);
    expect(calls).toEqual([['TRAM']]);
  });

  it('keeps the queue alive after a failure and rejects only that write', async () => {
    const calls: string[][] = [];
    const failing = deferred<void>();
    const queue = createModeFilterWriteQueue();

    const first = queue(['TRAM'], (filter) => {
      calls.push(filter);
      return failing.promise;
    });
    await flush();
    const second = queue(['BUS'], async (filter) => {
      calls.push(filter);
    });
    failing.reject(new Error('network'));

    await expect(first).rejects.toThrow('network');
    await expect(second).resolves.toBeUndefined();
    expect(calls).toEqual([['TRAM'], ['BUS']]);
  });
});

