import { describe, expect, it } from 'vitest';
import { selectCandidateTripIds } from './use-trip-timeline';
import type { Arrival } from '@/features/transit/types';

const at = (tripId: string, arrivalMs: number): Arrival => ({ tripId, arrivalMs, headSign: '', isPredicted: true });

describe('selectCandidateTripIds', () => {
  it('takes the first departures of every leg, deduped', () => {
    const ids = selectCandidateTripIds(
      [
        [at('a', 100), at('b', 200), at('c', 300)],
        [at('a', 150), at('x', 250)],
      ],
      2
    );
    expect(ids).toEqual(['a', 'b', 'x']);
  });

  it('skips later-leg vehicles that leave before the first leg can start', () => {
    const ids = selectCandidateTripIds(
      [
        [at('first', 1_000)],
        [at('gone1', 100), at('gone2', 900), at('ok1', 1_000), at('ok2', 2_000)],
      ],
      8
    );
    expect(ids).toEqual(['first', 'ok1', 'ok2']);
  });

  it('keeps every later-leg candidate when the first leg has none', () => {
    expect(selectCandidateTripIds([[], [at('a', 1), at('b', 2)]], 8)).toEqual(['a', 'b']);
  });
});
