import { describe, it, expect } from 'vitest';
import {
  formatDayMonth,
  formatBudapestClock,
  formatBudapestHm,
  formatClock,
  formatDDMMYYYY,
  formatHHMM,
  formatMinuteOfDay,
  formatTime,
  formatYMD,
  getDayName,
  getShortDayName,
  isValidCalendarDate,
  isYmdString,
  nowInBudapest,
} from './date-utils';

describe('nowInBudapest', () => {
  it('converts a UTC instant after 23:00Z to the next Budapest day (CET)', () => {
    // 2026-01-15T23:30:00Z = 2026-01-16 00:30 in Budapest (+01:00)
    const now = nowInBudapest(new Date('2026-01-15T23:30:00Z'));
    expect(now.ymd).toBe('2026-01-16');
    expect(now.dayOfWeek).toBe(5); // Friday
    expect(now.hours).toBe(0);
    expect(now.minutes).toBe(30);
  });

  it('handles summer time (CEST, +02:00)', () => {
    // 2026-07-01T22:30:00Z = 2026-07-02 00:30 in Budapest
    const now = nowInBudapest(new Date('2026-07-01T22:30:00Z'));
    expect(now.ymd).toBe('2026-07-02');
    expect(now.hours).toBe(0);
    expect(now.minutes).toBe(30);
  });

  it('reports the weekday of the Budapest date, not the device date', () => {
    // Device in UTC sees Sunday 23:00; Budapest is already Monday.
    const now = nowInBudapest(new Date('2026-01-18T23:00:00Z'));
    expect(now.ymd).toBe('2026-01-19');
    expect(now.dayOfWeek).toBe(1); // Monday
  });

  it('defaults to the current time and returns a valid shape', () => {
    const now = nowInBudapest();
    expect(now.year).toBeGreaterThan(2020);
    expect(now.month).toBeGreaterThanOrEqual(1);
    expect(now.month).toBeLessThanOrEqual(12);
    expect(now.dayOfWeek).toBeGreaterThanOrEqual(0);
    expect(now.dayOfWeek).toBeLessThanOrEqual(6);
    expect(now.ymd).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
});

describe('formatHHMM', () => {
  it('pads single-digit hours and preserves minutes', () => {
    expect(formatHHMM('8:00')).toBe('08:00');
    expect(formatHHMM('8:5')).toBe('08:05');
    expect(formatHHMM('14:30')).toBe('14:30');
  });

  it('strips seconds if present', () => {
    expect(formatHHMM('08:00:00')).toBe('08:00');
    expect(formatHHMM('15:45:30')).toBe('15:45');
  });

  it('handles null, undefined, or empty strings gracefully', () => {
    expect(formatHHMM(null)).toBe('');
    expect(formatHHMM(undefined)).toBe('');
    expect(formatHHMM('')).toBe('');
  });
});

describe('formatTime', () => {
  it('formats as 24-hour HH:mm by default', () => {
    expect(formatTime('08:00')).toBe('08:00');
    expect(formatTime('8:15')).toBe('08:15');
    expect(formatTime('13:45')).toBe('13:45');
    expect(formatTime('00:00')).toBe('00:00');
    expect(formatTime('23:59')).toBe('23:59');
  });

  it('formats as 24-hour when format is explicitly 24h', () => {
    expect(formatTime('09:30', '24h')).toBe('09:30');
    expect(formatTime('16:20', '24h')).toBe('16:20');
  });

  it('formats as 12-hour AM/PM when format is 12h', () => {
    expect(formatTime('08:00', '12h')).toBe('8:00 AM');
    expect(formatTime('8:05', '12h')).toBe('8:05 AM');
    expect(formatTime('12:00', '12h')).toBe('12:00 PM');
    expect(formatTime('13:45', '12h')).toBe('1:45 PM');
    expect(formatTime('00:00', '12h')).toBe('12:00 AM');
    expect(formatTime('23:59', '12h')).toBe('11:59 PM');
  });

  it('returns empty string for null, undefined, or empty input', () => {
    expect(formatTime(null)).toBe('');
    expect(formatTime(undefined)).toBe('');
    expect(formatTime('')).toBe('');
    expect(formatTime(null, '12h')).toBe('');
  });
});

describe('Budapest clock', () => {
  // 2026-01-15T13:05:00Z = 14:05 in Budapest (CET, +01:00)
  const winter = Date.parse('2026-01-15T13:05:00Z');
  // 2026-07-15T12:05:00Z = 14:05 in Budapest (CEST, +02:00)
  const summer = Date.parse('2026-07-15T12:05:00Z');

  it('renders 24h Budapest time regardless of the runtime timezone', () => {
    expect(formatBudapestClock(winter)).toBe('14:05');
    expect(formatBudapestClock(summer)).toBe('14:05');
  });

  it('renders a 12h variant', () => {
    expect(formatBudapestClock(winter, { hour12: true })).toMatch(/^2:05\s?PM$/);
  });

  it('formatBudapestHm zero-pads 24h', () => {
    expect(formatBudapestHm(Date.parse('2026-01-15T05:03:00Z'))).toBe('06:03');
  });
});

describe('formatClock', () => {
  // 2026-09-07 14:30:00 CEST (UTC+2) -> 1788784200000 ms
  const afternoonMs = new Date('2026-09-07T14:30:00+02:00').getTime();
  // 2026-09-07 08:05:00 CEST (UTC+2) -> 1788761100000 ms
  const morningMs = new Date('2026-09-07T08:05:00+02:00').getTime();

  it('formats 24-hour time by default', () => {
    expect(formatClock(afternoonMs)).toBe('14:30');
    expect(formatClock(morningMs)).toBe('08:05');
  });

  it('formats 12-hour AM/PM when requested', () => {
    expect(formatClock(afternoonMs, '12h')).toBe('2:30 PM');
    expect(formatClock(morningMs, '12h')).toBe('8:05 AM');
  });
});

describe('day names', () => {
  it('maps 0-6 to full and short names', () => {
    expect(getDayName(1)).toBe('Monday');
    expect(getDayName(6)).toBe('Saturday');
    expect(getDayName(9)).toBe('');
    expect(getShortDayName(0)).toBe('Sun');
    expect(getShortDayName(5)).toBe('Fri');
  });
});

describe('date helpers', () => {
  it('formatYMD zero-pads the local calendar date', () => {
    expect(formatYMD(new Date(2026, 0, 5))).toBe('2026-01-05');
  });

  it('formatDDMMYYYY formats and preserves unparseable input', () => {
    expect(formatDDMMYYYY('2026-01-05')).toBe('05/01/2026');
    expect(formatDDMMYYYY(null)).toBe('');
    expect(formatDDMMYYYY('not-a-date')).toBe('not-a-date');
  });

  it('isValidCalendarDate rejects impossible dates', () => {
    expect(isValidCalendarDate('2026-02-28')).toBe(true);
    expect(isValidCalendarDate('2024-02-29')).toBe(true);
    expect(isValidCalendarDate('2025-02-29')).toBe(false);
    expect(isValidCalendarDate('2026-02-31')).toBe(false);
    expect(isValidCalendarDate('2026-2-3')).toBe(false);
  });

  it('isYmdString is the regex type guard', () => {
    expect(isYmdString('2026-02-31')).toBe(true);
    expect(isYmdString(null)).toBe(false);
    expect(isYmdString('2026-2-3')).toBe(false);
  });

  it('formatMinuteOfDay clamps and folds midnight', () => {
    expect(formatMinuteOfDay(0)).toBe('00:00');
    expect(formatMinuteOfDay(485)).toBe('08:05');
    expect(formatMinuteOfDay(1439)).toBe('23:59');
    expect(formatMinuteOfDay(1440)).toBe('24:00');
    expect(formatMinuteOfDay(-5)).toBe('00:00');
  });
});

describe('formatDayMonth', () => {
  it('reads a calendar date as day and full month name', () => {
    expect(formatDayMonth('2026-10-12')).toBe('12 October');
    expect(formatDayMonth('2026-01-01')).toBe('1 January');
  });

  it('passes through anything that is not YYYY-MM-DD', () => {
    expect(formatDayMonth('not a date')).toBe('not a date');
  });
});
