import { describe, it, expect } from 'vitest';
import {
  getHungarianHolidays,
  getUpcomingHolidays,
  getNextHoliday,
  isHungarianPublicHoliday,
} from './hungarian-holidays';

describe('getHungarianHolidays', () => {
  it('returns 13 holidays for 2026', () => {
    const holidays = getHungarianHolidays(2026);
    expect(holidays).toHaveLength(13);
  });

  it('includes fixed holidays', () => {
    const holidays = getHungarianHolidays(2026);
    const names = holidays.map((h) => h.name);
    expect(names).toContain("New Year's Day");
    expect(names).toContain('Revolution of 1848');
    expect(names).toContain('Labour Day');
    expect(names).toContain('State Foundation Day');
    expect(names).toContain('Revolution of 1956');
    expect(names).toContain("All Saints' Day");
    expect(names).toContain('Christmas Day');
    expect(names).toContain('Second Day of Christmas');
  });

  it('includes Easter-based holidays', () => {
    const holidays = getHungarianHolidays(2026);
    const names = holidays.map((h) => h.name);
    expect(names).toContain('Good Friday');
    expect(names).toContain('Easter Sunday');
    expect(names).toContain('Easter Monday');
    expect(names).toContain('Whit Sunday');
    expect(names).toContain('Whit Monday');
  });

  it('computes correct Easter 2026 (April 5)', () => {
    const holidays = getHungarianHolidays(2026);
    const easter = holidays.find((h) => h.name === 'Easter Sunday');
    expect(easter).toEqual({ month: 4, day: 5, name: 'Easter Sunday', hungarian: 'Húsvétvasárnap' });
  });

  it('computes Good Friday as Easter Sunday - 2 days', () => {
    const holidays = getHungarianHolidays(2026);
    const goodFriday = holidays.find((h) => h.name === 'Good Friday');
    expect(goodFriday?.month).toBe(4);
    expect(goodFriday?.day).toBe(3);
  });

  it('computes Whit Sunday as Easter Sunday + 49 days', () => {
    const holidays = getHungarianHolidays(2026);
    const whitSunday = holidays.find((h) => h.name === 'Whit Sunday');
    // April 5 + 49 = May 24
    expect(whitSunday?.month).toBe(5);
    expect(whitSunday?.day).toBe(24);
  });

  it('each holiday has both English and Hungarian names', () => {
    const holidays = getHungarianHolidays(2026);
    for (const h of holidays) {
      expect(h.name).toBeTruthy();
      expect(h.hungarian).toBeTruthy();
    }
  });
});

describe('getNextHoliday', () => {
  it('returns today if today is a public holiday with daysUntil=0', () => {
    // January 1 is New Year's Day
    const now = new Date(2026, 0, 1);
    const result = getNextHoliday(now);
    expect(result).not.toBeNull();
    expect(result!.holiday.name).toBe("New Year's Day");
    expect(result!.daysUntil).toBe(0);
  });

  it('returns the next upcoming holiday within 14 days', () => {
    // March 5 — March 15 is 10 days away
    const now = new Date(2026, 2, 5);
    const result = getNextHoliday(now);
    expect(result).not.toBeNull();
    expect(result!.holiday.name).toBe('Revolution of 1848');
    expect(result!.daysUntil).toBe(10);
  });

  it('returns null when no holiday falls within the next 14 days', () => {
    // Feb 10 — next is March 15 (33 days away, exceeds 14 days)
    const now = new Date(2026, 1, 10);
    const result = getNextHoliday(now);
    expect(result).toBeNull();
  });

  it('handles year boundary correctly', () => {
    // December 27 — Christmas passed, next is Jan 1 next year (5 days)
    const now = new Date(2026, 11, 27);
    const result = getNextHoliday(now);
    expect(result).not.toBeNull();
    expect(result!.holiday.name).toBe("New Year's Day");
    expect(result!.daysUntil).toBe(5);
  });

  it('returns Christmas on December 25 with daysUntil=0', () => {
    const now = new Date(2026, 11, 25);
    const result = getNextHoliday(now);
    expect(result).not.toBeNull();
    expect(result!.holiday.name).toBe('Christmas Day');
    expect(result!.daysUntil).toBe(0);
  });

  it('returns daysUntil=1 when the next holiday is tomorrow', () => {
    // Dec 24 — Christmas Day (Dec 25) is tomorrow
    const now = new Date(2026, 11, 24);
    const result = getNextHoliday(now);
    expect(result).not.toBeNull();
    expect(result!.holiday.name).toBe('Christmas Day');
    expect(result!.daysUntil).toBe(1);
  });

  it('correctly returns Good Friday in 2026 within 14 days', () => {
    // March 25, 2026 — next is Good Friday on April 3 (9 days away)
    const now = new Date(2026, 2, 25);
    const result = getNextHoliday(now);
    expect(result).not.toBeNull();
    expect(result!.holiday.name).toBe('Good Friday');
    expect(result!.holiday.month).toBe(4);
    expect(result!.holiday.day).toBe(3);
    expect(result!.daysUntil).toBe(9);
  });

  it('correctly transitions through Easter and Pentecost in 2026 within 14 days', () => {
    // April 4 — Easter Sunday (April 5) is tomorrow
    const dayBeforeEaster = getNextHoliday(new Date(2026, 3, 4));
    expect(dayBeforeEaster?.holiday.name).toBe('Easter Sunday');
    expect(dayBeforeEaster?.daysUntil).toBe(1);

    // April 6 — Easter Monday is today
    const easterMonday = getNextHoliday(new Date(2026, 3, 6));
    expect(easterMonday?.holiday.name).toBe('Easter Monday');
    expect(easterMonday?.daysUntil).toBe(0);

    // April 20 — next is Labour Day on May 1 (11 days away)
    const beforeLabour = getNextHoliday(new Date(2026, 3, 20));
    expect(beforeLabour?.holiday.name).toBe('Labour Day');
    expect(beforeLabour?.daysUntil).toBe(11);

    // May 15 — next is Whit Sunday on May 24 (9 days away)
    const beforeWhitSunday = getNextHoliday(new Date(2026, 4, 15));
    expect(beforeWhitSunday?.holiday.name).toBe('Whit Sunday');
    expect(beforeWhitSunday?.daysUntil).toBe(9);
  });
});

describe('getUpcomingHolidays', () => {
  it('returns multiple holidays if they fall within the 14-day window (e.g. Easter season)', () => {
    // April 2, 2026 is 1 day before Good Friday (April 3), Easter Sunday (April 5), Easter Monday (April 6)
    const now = new Date(2026, 3, 2);
    const results = getUpcomingHolidays(now, 14);
    expect(results.length).toBe(3);
    expect(results[0].holiday.name).toBe('Good Friday');
    expect(results[1].holiday.name).toBe('Easter Sunday');
    expect(results[2].holiday.name).toBe('Easter Monday');
  });

  it('returns Christmas Day, Second Day of Christmas, and New Year Day within the 14-day window', () => {
    // Dec 20, 2026: Christmas Day is 5 days away, Boxing Day is 6 days, New Year's Day is 12 days away
    const now = new Date(2026, 11, 20);
    const results = getUpcomingHolidays(now, 14);
    expect(results.length).toBe(3);
    expect(results[0].holiday.name).toBe('Christmas Day');
    expect(results[1].holiday.name).toBe('Second Day of Christmas');
    expect(results[2].holiday.name).toBe("New Year's Day");
  });
});


describe('Budapest calendar dates', () => {
  it('counts from the Budapest date even when the device clock is in another zone', () => {
    // 23:30 UTC on Oct 22 is already Oct 23 in Budapest: the holiday is today.
    const result = getNextHoliday(new Date(Date.UTC(2026, 9, 22, 23, 30)));
    expect(result?.holiday.month).toBe(10);
    expect(result?.holiday.day).toBe(23);
    expect(result?.daysUntil).toBe(0);
  });

  it('recognises public holidays by Budapest YMD', () => {
    expect(isHungarianPublicHoliday('2026-10-23')).toBe(true);
    expect(isHungarianPublicHoliday('2026-04-06')).toBe(true); // Easter Monday 2026
    expect(isHungarianPublicHoliday('2026-05-25')).toBe(true); // Whit Monday 2026
    expect(isHungarianPublicHoliday('2026-10-22')).toBe(false);
    expect(isHungarianPublicHoliday('not-a-date')).toBe(false);
  });
});
