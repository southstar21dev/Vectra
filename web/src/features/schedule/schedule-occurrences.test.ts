import { describe, expect, it } from 'vitest';
import { getScheduleItemOccurrences, getScheduleItemOccurrencesForDate } from './schedule-occurrences';
import type { ScheduleItem } from './schedule-conflicts';

const item = (overrides: Partial<ScheduleItem> = {}): ScheduleItem => ({
  id: '11111111-1111-4111-8111-111111111111',
  type: 'shift',
  title: 'Work',
  date: '2026-09-07',
  startTime: '22:00',
  endTime: '02:00',
  acknowledgedConflictKeys: [],
  ...overrides,
});

describe('schedule item occurrences', () => {
  it('expands a one-off item only on its own date', () => {
    const task = item({ type: 'task', startTime: '09:00', endTime: '10:00' });
    expect(getScheduleItemOccurrences([task], '2026-09-07', '2026-09-13')).toEqual([
      { item: task, date: '2026-09-07', startTime: '09:00', endTime: '10:00', continuation: false },
    ]);
  });

  it('shows overnight shifts on both dates, including when the range begins the next day', () => {
    const overnight = item();
    expect(getScheduleItemOccurrences([overnight], '2026-09-07', '2026-09-08')).toEqual([
      { item: overnight, date: '2026-09-07', startTime: '22:00', endTime: '24:00', continuation: false },
      { item: overnight, date: '2026-09-08', startTime: '00:00', endTime: '02:00', continuation: true },
    ]);
    expect(getScheduleItemOccurrencesForDate([overnight], '2026-09-08')[0]).toMatchObject({
      date: '2026-09-08',
      continuation: true,
    });
  });

  it('keeps completed tasks visible and sorts same-day items chronologically', () => {
    const late = item({ id: 'late', type: 'task', title: 'Done', date: '2026-09-08', startTime: '12:00', endTime: '13:00', completed: true });
    const early = item({ id: 'early', date: '2026-09-08', startTime: '08:00', endTime: '09:00' });
    expect(getScheduleItemOccurrences([late, early], '2026-09-08', '2026-09-08').map((entry) => entry.item.id)).toEqual(['early', 'late']);
  });

  it('rejects invalid and reversed date ranges', () => {
    expect(getScheduleItemOccurrences([item()], '2026-02-30', '2026-03-01')).toEqual([]);
    expect(getScheduleItemOccurrences([item()], '2026-09-08', '2026-09-07')).toEqual([]);
  });
});
