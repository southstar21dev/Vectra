import { describe, expect, it } from 'vitest';
import { getWeeksInMonth, resolveAdjacentWeekIndex, resolveWeekIndexAfterRecompute } from './use-week-navigation';

describe('resolveAdjacentWeekIndex', () => {
  // Sep 29 – Oct 5, 2025 belongs to both September and October.
  const september = getWeeksInMonth(new Date(2025, 8, 1));
  const october = getWeeksInMonth(new Date(2025, 9, 1));

  it('moves past a week shared by both months when going forward', () => {
    const lastSeptember = september[september.length - 1];
    expect(lastSeptember.startStr).toBe('2025-09-29');
    expect(october[0].startStr).toBe('2025-09-29');
    const idx = resolveAdjacentWeekIndex(october, 'after', lastSeptember.startStr);
    expect(october[idx].startStr).toBe('2025-10-06');
  });

  it('moves past a week shared by both months when going back', () => {
    const idx = resolveAdjacentWeekIndex(september, 'before', october[0].startStr);
    expect(september[idx].startStr).toBe('2025-09-22');
  });

  it('lands on the first / last week when months do not share a week', () => {
    // Dec 2025 ends on Wednesday the 31st; Jan 2026 starts with that same week.
    const december = getWeeksInMonth(new Date(2025, 11, 1));
    const january = getWeeksInMonth(new Date(2026, 0, 1));
    const idx = resolveAdjacentWeekIndex(january, 'after', december[december.length - 1].startStr);
    expect(january[idx].startStr).toBe('2026-01-05');
    expect(resolveAdjacentWeekIndex([], 'after', '2026-01-01')).toBe(0);
  });
});

describe('resolveWeekIndexAfterRecompute', () => {
  it('keeps the navigated week when only the day rolled over', () => {
    expect(resolveWeekIndexAfterRecompute({ contextChanged: false, current: 3, previousDefault: 1, nextDefault: 1 })).toBe(3);
    expect(resolveWeekIndexAfterRecompute({ contextChanged: false, current: 3, previousDefault: 1, nextDefault: 2 })).toBe(3);
  });

  it('follows the current week across a rollover when the user had not navigated', () => {
    expect(resolveWeekIndexAfterRecompute({ contextChanged: false, current: 1, previousDefault: 1, nextDefault: 2 })).toBe(2);
    expect(resolveWeekIndexAfterRecompute({ contextChanged: false, current: null, previousDefault: 1, nextDefault: 2 })).toBe(2);
  });

  it('starts a new month or semester on its default week', () => {
    expect(resolveWeekIndexAfterRecompute({ contextChanged: true, current: 3, previousDefault: 1, nextDefault: 0 })).toBe(0);
  });
});
