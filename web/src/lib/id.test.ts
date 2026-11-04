import { describe, expect, it } from 'vitest';
import { genId } from './id';

describe('genId', () => {
  it('returns a non-empty string', () => {
    expect(genId()).toMatch(/\S/);
  });

  it('returns unique values across calls', () => {
    expect(genId()).not.toBe(genId());
  });
});
