import { describe, expect, it } from 'vitest';
import { withHash } from './colors';

describe('withHash', () => {
  it('adds a hash only when missing', () => {
    expect(withHash('ff0000')).toBe('#ff0000');
    expect(withHash('#ff0000')).toBe('#ff0000');
  });
});
