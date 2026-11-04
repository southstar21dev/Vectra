import { describe, it, expect } from 'vitest';
import { formatBytes } from './format';

describe('formatBytes', () => {
  it('defaults to binary units and trims trailing zeros', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(1024)).toBe('1 KB');
    expect(formatBytes(1048576)).toBe('1 MB');
    expect(formatBytes(1536000)).toBe('1.46 MB');
  });

  it('supports decimal units (storage marketing)', () => {
    expect(formatBytes(900_000, { base: 1000, decimals: 1 })).toBe('900 KB');
    expect(formatBytes(15_015_000, { base: 1000, decimals: 1 })).toBe('15 MB');
  });

  it('handles invalid/negative input', () => {
    expect(formatBytes(Number.NaN)).toBe('0 B');
    expect(formatBytes(-5)).toBe('0 B');
  });
});
