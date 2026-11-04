import { describe, it, expect } from 'vitest';
import { createClickSuppressor, shouldPullSheet } from './nearby-sheet-gesture';

describe('shouldPullSheet', () => {
  const base = { sheetY: 0, feedScrollTop: 0, threshold: 6 };

  it('takes over a downward drag when the list sits at its top', () => {
    expect(shouldPullSheet({ ...base, deltaY: 7 })).toBe(true);
  });

  it('ignores movement within the threshold', () => {
    expect(shouldPullSheet({ ...base, deltaY: 6 })).toBe(false);
  });

  it('leaves upward drags to native list scrolling', () => {
    expect(shouldPullSheet({ ...base, deltaY: -7 })).toBe(false);
  });

  it('leaves downward drags alone once the list has scrolled', () => {
    expect(shouldPullSheet({ ...base, feedScrollTop: 120, deltaY: 7 })).toBe(false);
  });

  it('takes over drags that start outside the list (grabber/chips)', () => {
    expect(shouldPullSheet({ ...base, feedScrollTop: null, deltaY: 7 })).toBe(true);
  });

  it('does not pull while the sheet is already peeked', () => {
    expect(shouldPullSheet({ ...base, sheetY: 40, deltaY: 7 })).toBe(false);
  });
});

describe('createClickSuppressor', () => {
  it('suppresses exactly the click that follows a drag', () => {
    const suppressor = createClickSuppressor();
    suppressor.arm();
    expect(suppressor.consume()).toBe(true);
    expect(suppressor.consume()).toBe(false);
  });

  it('does not arm without a drag', () => {
    expect(createClickSuppressor().consume()).toBe(false);
  });

  it('drops a pending suppression when a new press starts', () => {
    const suppressor = createClickSuppressor();
    suppressor.arm();
    suppressor.beginPress();
    expect(suppressor.consume()).toBe(false);
  });
});
