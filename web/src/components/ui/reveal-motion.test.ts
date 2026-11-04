import { describe, it, expect } from 'vitest';
import {
  REVEAL_MAX_STAGGER_STEPS,
  REVEAL_STAGGER_S,
  revealDelay,
} from './reveal-motion';

describe('revealDelay', () => {
  it('starts the first card immediately', () => {
    expect(revealDelay(0)).toBe(0);
  });

  it('adds one interval per card', () => {
    expect(revealDelay(1)).toBeCloseTo(REVEAL_STAGGER_S);
    expect(revealDelay(4) - revealDelay(3)).toBeCloseTo(REVEAL_STAGGER_S);
  });

  it('caps the stagger so the last card never waits on a long page', () => {
    const cap = REVEAL_MAX_STAGGER_STEPS * REVEAL_STAGGER_S;
    expect(revealDelay(REVEAL_MAX_STAGGER_STEPS)).toBeCloseTo(cap);
    expect(revealDelay(40)).toBeCloseTo(cap);
  });

  it('treats a stray negative index as the first card', () => {
    expect(revealDelay(-3)).toBe(0);
  });
});
