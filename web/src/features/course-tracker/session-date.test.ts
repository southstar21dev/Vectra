import { describe, it, expect } from 'vitest';
import { formatSessionDate, formatSessionDateFull } from './session-date';

// TZ is pinned to Europe/Budapest by vite.config.ts, so weekday/day are stable.
describe('formatSessionDate', () => {
  it('reads "Today" for the session date the ledger treats as today', () => {
    expect(formatSessionDate('2026-09-16', '2026-09-16')).toBe('Today');
  });

  it('reads day-month for every other date', () => {
    expect(formatSessionDate('2026-10-02', '2026-09-16')).toBe('Fri 2 Oct');
    expect(formatSessionDate('2026-12-01', '2026-09-16')).toBe('Tue 1 Dec');
  });

  it('never says "Today" for a date that only looks similar', () => {
    expect(formatSessionDate('2026-09-16T00:00:00', '2026-09-16')).not.toBe('Today');
  });
});

describe('formatSessionDateFull', () => {
  it('spells today out instead of repeating a "Today" heading', () => {
    // The focus card heading already says "Today"; the subtitle must not echo it.
    expect(formatSessionDateFull('2026-09-16')).toBe('Wed 16 Sept');
  });

  it('formats the same date the same way as the ledger label', () => {
    expect(formatSessionDateFull('2026-10-02')).toBe(formatSessionDate('2026-10-02', '2026-09-16'));
  });

  it('passes through a date it cannot parse', () => {
    expect(formatSessionDateFull('')).toBe('');
    expect(formatSessionDateFull('not-a-date')).toBe('not-a-date');
  });
});
