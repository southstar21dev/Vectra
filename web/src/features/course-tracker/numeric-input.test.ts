import { describe, it, expect } from 'vitest';
import { clampNumber, normalizeDecimalInput, parseFiniteNumber, validateScaleOrder } from './numeric-input';
import { DEFAULT_GRADING_SCALE } from './tracker-types';

describe('numeric input helpers', () => {
  it('accepts Hungarian decimal commas without corrupting the value', () => {
    expect(normalizeDecimalInput('12,5')).toBe('12.5');
    expect(parseFiniteNumber('12,5')).toBe(12.5);
    expect(normalizeDecimalInput('1.234,5')).toBe('1.2345');
  });

  it('allows an in-progress value without clamping it mid-typing', () => {
    expect(normalizeDecimalInput('0')).toBe('0');
    expect(normalizeDecimalInput('0.')).toBe('0.');
    expect(normalizeDecimalInput('0.5')).toBe('0.5');
    expect(normalizeDecimalInput('.')).toBe('.');
  });

  it('never yields NaN from a draft value', () => {
    expect(parseFiniteNumber('')).toBeNull();
    expect(parseFiniteNumber('.')).toBeNull();
    expect(parseFiniteNumber('abc')).toBeNull();
    expect(parseFiniteNumber('7.5')).toBe(7.5);
  });

  it('clamps committed values', () => {
    expect(clampNumber(-2, 0, 100)).toBe(0);
    expect(clampNumber(120, 0, 100)).toBe(100);
    expect(clampNumber(75, 0, 100)).toBe(75);
  });

  it('rejects out-of-order grading scales and accepts valid ones', () => {
    expect(validateScaleOrder(DEFAULT_GRADING_SCALE)).toBeNull();
    expect(
      validateScaleOrder([
        { grade: 5, minPercentage: 60 },
        { grade: 4, minPercentage: 90 },
        { grade: 3, minPercentage: 62 },
        { grade: 2, minPercentage: 50 },
        { grade: 1, minPercentage: 0 },
      ])
    ).toBe('Grade 5 cutoff must be at least the Grade 4 cutoff (90%).');
    expect(
      validateScaleOrder([
        { grade: 5, minPercentage: 87 },
        { grade: 4, minPercentage: 75 },
        { grade: 3, minPercentage: 80 },
        { grade: 2, minPercentage: 50 },
        { grade: 1, minPercentage: 0 },
      ])
    ).toBe('Grade 4 cutoff must be at least the Grade 3 cutoff (80%).');
  });
});
